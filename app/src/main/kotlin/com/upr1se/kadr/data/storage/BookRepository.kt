package com.upr1se.kadr.data.storage

import com.upr1se.kadr.data.db.BookCatalog
import com.upr1se.kadr.data.db.BookEntity
import com.upr1se.kadr.data.db.ContentState
import com.upr1se.kadr.data.db.ReadingState
import com.upr1se.kadr.core.UserTextFallbacks
import com.upr1se.kadr.fb2.BookMetadata
import com.upr1se.kadr.fb2.Fb2Warning
import com.upr1se.kadr.fb2.Fb2WarningCode
import com.upr1se.kadr.fb2.Fb2Versions
import com.upr1se.kadr.fb2.ParsedNote
import com.upr1se.kadr.fb2.ParsedSection
import com.upr1se.kadr.domain.reader.ReadingFingerprint
import java.io.File
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

data class LibraryBook(
  val id: String,
  val title: String,
  val authorDisplay: String,
  val language: String?,
  val format: String,
  val coverFile: File?,
  val contentState: ContentState,
  val readingState: ReadingState,
  val progressPercent: Int,
  val currentChapterTitle: String?,
  val wordCount: Int,
  val chapterCount: Int,
  val lastErrorCode: String?,
  val importedAt: Long,
  val lastOpenedAt: Long?,
)

data class OpenedBook(
  val entity: BookEntity,
  val metadata: BookMetadata,
  val readingText: String,
  val sections: List<ParsedSection>,
  val notes: Map<String, ParsedNote>,
  val warnings: List<Fb2Warning>,
  val coverFile: File?,
  val folder: File,
  val manifest: StorageManifest,
  val index: ReadingIndexReader,
) : AutoCloseable {
  val wordCount: Int
    get() = index.header.wordCount

  val itemCount: Int
    get() = index.header.itemCount

  override fun close() {
    index.close()
  }
}

sealed interface OpenBookResult {
  data class Success(val book: OpenedBook) : OpenBookResult

  data class Unavailable(val errorCode: String) : OpenBookResult
}

data class SavedPosition(
  val itemOrdinal: Int,
  val charOffset: Int,
  val wordIndex: Int,
  val sentenceId: Int?,
  val chapterKey: String?,
  val chapterTitle: String?,
  val fingerprint: String?,
  val revision: Long,
  val readingState: ReadingState,
)

data class BookInfo(
  val id: String,
  val title: String,
  val authorDisplay: String,
  val language: String?,
  val format: String,
  val wordCount: Int,
  val chapterCount: Int,
  val importedAt: Long,
  val sizeBytes: Long,
  val warnings: List<Fb2Warning>,
  val errorCode: String?,
)

class BookRepository(
  private val catalog: BookCatalog,
  private val files: BookFileStore,
  private val parser: com.upr1se.kadr.fb2.Fb2Parser,
  private val clock: () -> Long = System::currentTimeMillis,
) {
  private val integrity = BookIntegrityService(files, parser, catalog)

  fun observeLibrary(): Flow<List<LibraryBook>> = catalog.observeLibrary().map { entities ->
    entities.map(::toLibraryBook)
  }

  fun observeContinueReading(): Flow<LibraryBook?> =
    catalog.observeContinueReading().map { it?.let(::toLibraryBook) }

  suspend fun findBook(bookId: String): BookEntity? = catalog.findById(bookId)

  suspend fun openBook(bookId: String): OpenBookResult =
    withContext(Dispatchers.IO) {
      val initial = catalog.findById(bookId)
        ?: return@withContext OpenBookResult.Unavailable("BOOK_INTEGRITY")
      if (initial.contentState == ContentState.IMPORTING ||
        initial.contentState == ContentState.DELETING
      ) {
        return@withContext OpenBookResult.Unavailable("BOOK_INTEGRITY")
      }
      val opened = openOrRepair(initial)
      if (opened is OpenBookResult.Success) {
        val synced = syncEntityFromManifest(opened.book.entity, opened.book)
        if (synced != opened.book.entity) {
          catalog.update(synced)
        }
        catalog.markOpened(bookId, clock())
        val refreshed = catalog.findById(bookId) ?: initial
        return@withContext OpenBookResult.Success(
          opened.book.copy(entity = refreshed),
        )
      }
      opened
    }

  suspend fun markStarted(bookId: String) {
    catalog.markOpenedAndStarted(bookId, clock())
  }

  suspend fun saveBookSpeed(bookId: String, wpm: Int) {
    catalog.saveBookSpeed(bookId, wpm)
  }

  suspend fun savePosition(
    book: OpenedBook,
    position: SavedPosition,
  ): Boolean {
    val progress =
      progressPercent(
        readingState = position.readingState,
        wordIndex = position.wordIndex,
        wordCount = book.wordCount,
      )
    return catalog.savePositionIfNewer(
      bookId = book.entity.id,
      itemOrdinal = position.itemOrdinal,
      charOffset = position.charOffset,
      wordIndex = position.wordIndex,
      sentenceId = position.sentenceId,
      chapterKey = position.chapterKey,
      chapterTitle = position.chapterTitle,
      fingerprint = position.fingerprint,
      revision = position.revision,
      progressPercent = progress,
      readingState = position.readingState,
      savedAt = clock(),
    ) > 0
  }

  suspend fun resetProgress(
    book: OpenedBook,
    revision: Long,
  ): Boolean {
    val first = book.index.readWord(0)
    val section = book.index.sections()[first.sectionIndex]
    return catalog.resetProgress(
      bookId = book.entity.id,
      firstItemOrdinal = first.itemOrdinal,
      firstCharOffset = first.startChar,
      firstWordIndex = 0,
      firstSentenceId = first.sentenceId,
      firstChapterKey = section.chapterKey,
      firstChapterTitle = section.title,
      fingerprint =
        ReadingFingerprint.calculate(book.readingText, first.startChar, first.endChar),
      revision = revision,
      savedAt = clock(),
    ) > 0
  }

  suspend fun sourceSizeOnDisk(bookId: String): Long =
    withContext(Dispatchers.IO) {
      files.bookFolder(bookId).walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

  suspend fun loadBookInfo(bookId: String): BookInfo? =
    withContext(Dispatchers.IO) {
      val entity = catalog.findById(bookId) ?: return@withContext null
      val committed =
        runCatching { files.loadCommitted(files.bookFolder(bookId), bookId, verifyHashes = false) }
          .getOrNull()
      val warnings =
        committed?.document?.warnings.orEmpty().mapNotNull { warning ->
          runCatching {
            Fb2Warning(Fb2WarningCode.valueOf(warning.code), warning.detail)
          }.getOrNull()
        }
      BookInfo(
        id = entity.id,
        title = entity.title,
        authorDisplay = entity.authorDisplay ?: UserTextFallbacks.UNKNOWN_AUTHOR,
        language = entity.language,
        format = entity.format,
        wordCount = entity.wordCount,
        chapterCount = entity.chapterCount,
        importedAt = entity.importedAt,
        sizeBytes = files.bookFolder(bookId).walkTopDown().filter { it.isFile }.sumOf { it.length() },
        warnings = warnings,
        errorCode = entity.lastErrorCode,
      )
    }

  suspend fun resetProgress(bookId: String): Boolean =
    when (val opened = openBook(bookId)) {
      is OpenBookResult.Success ->
        opened.book.use { book ->
          resetProgress(book, book.entity.positionRevision + 1)
        }
      is OpenBookResult.Unavailable -> false
    }

  suspend fun loadCover(
    bookId: String,
    targetSizePx: Int = 320,
  ): android.graphics.Bitmap? =
    withContext(Dispatchers.IO) {
      val entity = catalog.findById(bookId) ?: return@withContext null
      val file =
        entity.coverRelativePath?.let { File(files.bookFolder(bookId), it) }?.takeIf { it.isFile }
          ?: return@withContext null
      runCatching {
        val bounds =
          android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (
          bounds.outWidth / sample > targetSizePx * 2 ||
            bounds.outHeight / sample > targetSizePx * 2
        ) {
          sample *= 2
        }
        android.graphics.BitmapFactory.decodeFile(
          file.absolutePath,
          android.graphics.BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
          },
        )
      }.getOrNull()
    }

  private suspend fun openOrRepair(entity: BookEntity): OpenBookResult {
    val firstAttempt = openArtifacts(entity)
    if (firstAttempt != null) return firstAttempt
    return when (val repaired = integrity.repair(entity.id)) {
      is RepairResult.Repaired -> openArtifacts(repaired.book)
        ?: OpenBookResult.Unavailable(repaired.book.lastErrorCode ?: "BOOK_REBUILD_FAILED")
      is RepairResult.Failed -> OpenBookResult.Unavailable(repaired.code)
    }
  }

  private fun openArtifacts(entity: BookEntity): OpenBookResult? {
    if (entity.contentState != ContentState.READY && entity.contentState != ContentState.BROKEN) {
      return OpenBookResult.Unavailable("BOOK_INTEGRITY")
    }
    val folder = files.bookFolder(entity.id)
    val committed =
      try {
        files.loadCommitted(folder, entity.id, verifyHashes = false)
      } catch (_: Exception) {
        return null
      }
    val manifest = committed.manifest
    if (
      entity.parserVersion > manifest.parserVersion ||
        entity.indexVersion > manifest.indexVersion ||
        entity.layoutVersion > manifest.layoutVersion
    ) {
      return null
    }
    if (
      manifest.parserVersion != Fb2Versions.PARSER_VERSION ||
        manifest.indexVersion != Fb2Versions.INDEX_VERSION ||
        manifest.layoutVersion != Fb2Versions.LAYOUT_VERSION
    ) {
      return null
    }
    val readingEntry = manifest.files.first { it.role == "reading_text" }
    val readingFile = File(folder, readingEntry.name)
    val indexEntry = manifest.files.first { it.role == "reading_index" }
    val index =
      try {
        ReadingIndexReader.open(File(folder, indexEntry.name))
      } catch (_: Exception) {
        return null
      }
    try {
      if (
        index.header.textSize != readingEntry.size ||
          !index.header.textHash.equals(manifest.contentHash, ignoreCase = true) ||
          index.header.parserVersion != manifest.parserVersion ||
          index.header.layoutVersion != manifest.layoutVersion ||
          index.header.wordCount != manifest.summary.wordCount ||
          index.header.itemCount != manifest.summary.itemCount ||
          index.header.sentenceCount != manifest.summary.sentenceCount ||
          index.header.chapterCount != manifest.summary.chapterCount
      ) {
        index.close()
        return null
      }
      val textBytes = readingFile.readBytes()
      if (textBytes.size.toLong() != index.header.textSize) {
        index.close()
        return null
      }
      val text =
        runCatching { decodeStrictUtf8(textBytes) }
          .getOrElse {
            index.close()
            return null
          }
      val metadata = BookJsonCodec.metadata(committed.document)
      val warnings =
        committed.document.warnings.mapNotNull { warning ->
          runCatching {
            Fb2Warning(Fb2WarningCode.valueOf(warning.code), warning.detail)
          }.getOrNull()
        }
      val notes = committed.document.notes.associate { note ->
        note.noteId to
          ParsedNote(
            noteId = note.noteId,
            blocks = note.blocks,
            warnings =
              note.warnings.mapNotNull { warning ->
                runCatching {
                  com.upr1se.kadr.fb2.Fb2Warning(
                    com.upr1se.kadr.fb2.Fb2WarningCode.valueOf(warning.code),
                    warning.detail,
                  )
                }.getOrNull()
              },
          )
      }
      val coverName = ManifestCodec.coverFileName(manifest)
      return OpenBookResult.Success(
        OpenedBook(
          entity = entity,
          metadata = metadata,
          readingText = text,
          sections = index.sections(),
          notes = notes,
          warnings = warnings,
          coverFile = coverName?.let { File(folder, it) }?.takeIf { it.isFile },
          folder = folder,
          manifest = manifest,
          index = index,
        ),
      )
    } catch (_: Exception) {
      index.close()
      return null
    }
  }

  private fun decodeStrictUtf8(bytes: ByteArray): String {
    val decoder =
      Charsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
  }

  private fun toLibraryBook(entity: BookEntity): LibraryBook =
    LibraryBook(
      id = entity.id,
      title = entity.title,
      authorDisplay = entity.authorDisplay ?: UserTextFallbacks.UNKNOWN_AUTHOR,
      language = entity.language,
      format = entity.format,
      coverFile = entity.coverRelativePath?.let { File(files.bookFolder(entity.id), it) },
      contentState = entity.contentState,
      readingState = entity.readingState,
      progressPercent = entity.progressPercent,
      currentChapterTitle = entity.currentChapterTitle,
      wordCount = entity.wordCount,
      chapterCount = entity.chapterCount,
      lastErrorCode = entity.lastErrorCode,
      importedAt = entity.importedAt,
      lastOpenedAt = entity.lastOpenedAt,
    )

  private fun syncEntityFromManifest(entity: BookEntity, book: OpenedBook): BookEntity =
    entity.copy(
      title = book.metadata.title,
      authorDisplay = book.metadata.authorDisplay,
      language = book.metadata.language,
      format = book.manifest.format,
      archiveEntryPath = book.manifest.archiveEntryPath,
      archiveEntryIndex = book.manifest.archiveEntryIndex,
      sourceHash = book.manifest.sourceHash,
      contentHash = book.manifest.contentHash,
      wordCount = book.manifest.summary.wordCount,
      chapterCount = book.manifest.summary.chapterCount,
      coverRelativePath = ManifestCodec.coverFileName(book.manifest),
      parserVersion = book.manifest.parserVersion,
      indexVersion = book.manifest.indexVersion,
      layoutVersion = book.manifest.layoutVersion,
    )

  companion object {
    fun progressPercent(
      readingState: ReadingState,
      wordIndex: Int,
      wordCount: Int,
    ): Int =
      when {
        readingState == ReadingState.COMPLETED -> 100
        readingState == ReadingState.UNREAD || wordIndex < 0 || wordCount <= 0 -> 0
        else -> ((100L * (wordIndex + 1)) / wordCount).toInt().coerceIn(1, 99)
      }
  }
}
