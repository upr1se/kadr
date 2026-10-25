package com.upr1se.kadr.data.storage

import com.upr1se.kadr.data.db.BookCatalog
import com.upr1se.kadr.data.db.BookEntity
import com.upr1se.kadr.data.db.ContentState
import com.upr1se.kadr.data.db.ReadingState
import com.upr1se.kadr.core.UserTextFallbacks
import com.upr1se.kadr.domain.reader.ReadingFingerprint
import com.upr1se.kadr.fb2.BookFormat
import com.upr1se.kadr.fb2.Fb2ImportException
import com.upr1se.kadr.fb2.Fb2Inspection
import com.upr1se.kadr.fb2.Fb2Parser
import com.upr1se.kadr.fb2.Fb2SelectionRequiredException
import com.upr1se.kadr.fb2.Fb2Versions
import com.upr1se.kadr.fb2.ZipBookCandidate
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class DuplicateDecision {
  OPEN_EXISTING,
  IMPORT_COPY,
  CANCEL,
}

interface ImportInteraction {
  suspend fun chooseZipBook(candidates: List<ZipBookCandidate>): ZipBookCandidate?

  suspend fun resolveDuplicate(
    existing: BookEntity,
    exactSourceMatch: Boolean,
  ): DuplicateDecision
}

fun interface ImportProgressListener {
  fun onProgress(progress: ImportProgress)
}

sealed interface ImportResult {
  data class Imported(val bookId: String) : ImportResult

  data class OpenedExisting(val bookId: String) : ImportResult

  data object Cancelled : ImportResult
}

data class RecoveryReport(
  val completedImports: Int,
  val completedDeletions: Int,
  val cleanedImports: Int,
  val cleanedTrash: Int,
)

class ImportCoordinator(
  private val files: BookFileStore,
  private val parser: Fb2Parser,
  private val catalog: BookCatalog,
  private val clock: () -> Long = System::currentTimeMillis,
) {
  private val importMutex = Mutex()

  suspend fun import(
    source: ImportSource,
    interaction: ImportInteraction?,
    progressListener: ImportProgressListener = ImportProgressListener {},
  ): ImportResult =
    withContext(Dispatchers.IO) {
      importMutex.withLock {
      val bookId = UUID.randomUUID().toString()
      val importTask = currentCoroutineContext()[Job]
      val cancellationCheck = { importTask?.isCancelled == true }
      val importedAt = clock()
      val safeSourceName =
        com.upr1se.kadr.fb2.UnicodeText.sanitizeDisplayFileName(source.displayName, 255)
          .ifBlank { "book.fb2" }
      val importedTitle =
        com.upr1se.kadr.fb2.UnicodeText.stripBookExtensions(safeSourceName).ifBlank {
          UserTextFallbacks.UNTITLED
        }
      val initial =
        BookEntity(
          id = bookId,
          title = importedTitle,
          authorDisplay = null,
          language = null,
          format = BookFormat.FB2.name,
          sourceFileName = safeSourceName,
          folderRelativePath = "books/$bookId",
          archiveEntryPath = null,
          archiveEntryIndex = null,
          sourceHash = "",
          contentHash = "",
          sourceSizeBytes = 0,
          wordCount = 0,
          chapterCount = 0,
          coverRelativePath = null,
          contentState = ContentState.IMPORTING,
          readingState = ReadingState.UNREAD,
          currentItemOrdinal = 0,
          currentCharOffset = 0,
          currentWordIndex = -1,
          currentSentenceId = null,
          currentChapterKey = null,
          currentChapterTitle = null,
          positionFingerprint = null,
          positionRevision = 0,
          progressPercent = 0,
          lastSpeedWpm = null,
          parserVersion = Fb2Versions.PARSER_VERSION,
          indexVersion = Fb2Versions.INDEX_VERSION,
          layoutVersion = Fb2Versions.LAYOUT_VERSION,
          importedAt = importedAt,
          lastOpenedAt = null,
          lastSavedAt = null,
          lastErrorCode = null,
        )
      catalog.insert(initial)
      val importFolder = files.createImportFolder(bookId)
      var commitPointReached = false
      try {
        progressListener.onProgress(ImportProgress(ImportStage.COPYING, 0, source.sizeHint))
        val copied =
          source.openStream().use { input ->
            files.copySource(
              input = input,
              destinationFolder = importFolder,
              sizeHint = source.sizeHint,
              maxBytes = Fb2LimitsDefaults.MAX_SOURCE_BYTES,
            ) { copiedBytes ->
              progressListener.onProgress(
                ImportProgress(ImportStage.COPYING, copiedBytes, source.sizeHint),
              )
            }
          }
        currentCoroutineContext().ensureActive()
        catalog.update(
          initial.copy(
            sourceHash = copied.sourceHash,
            sourceSizeBytes = copied.sizeBytes,
          ),
        )

        val exactDuplicate = catalog.findReadyBySourceHash(copied.sourceHash)
        var duplicateAccepted = false
        if (exactDuplicate != null) {
          val decision = interaction?.resolveDuplicate(exactDuplicate, exactSourceMatch = true)
          when (decision) {
            DuplicateDecision.OPEN_EXISTING -> {
              cleanupUncommitted(bookId, importFolder)
              return@withLock ImportResult.OpenedExisting(exactDuplicate.id)
            }
            DuplicateDecision.IMPORT_COPY -> duplicateAccepted = true
            DuplicateDecision.CANCEL,
            null -> {
              cleanupUncommitted(bookId, importFolder)
              return@withLock ImportResult.Cancelled
            }
          }
        }

        progressListener.onProgress(ImportProgress(ImportStage.INSPECTING))
        val inspection =
          parser.inspect(copied.file, safeSourceName, cancellationCheck)
        var selectedEntryIndex: Int? = null
        if (inspection is Fb2Inspection.NeedsSelection) {
          val selector =
            interaction ?: throw Fb2SelectionRequiredException(inspection.candidates)
          val choice = selector.chooseZipBook(inspection.candidates)
          if (choice == null) {
            cleanupUncommitted(bookId, importFolder)
            return@withLock ImportResult.Cancelled
          }
          selectedEntryIndex = choice.entryIndex
        }
        currentCoroutineContext().ensureActive()

        progressListener.onProgress(ImportProgress(ImportStage.PARSING))
        val parsed =
          parser.parse(
            file = copied.file,
            sourceFileName = safeSourceName,
            inspection = inspection,
            selectedEntryIndex = selectedEntryIndex,
            cancellationCheck = cancellationCheck,
          )
        currentCoroutineContext().ensureActive()

        if (!duplicateAccepted) {
          val contentHash = Hashing.sha256(parsed.readingText)
          val contentDuplicate = catalog.findReadyByContentHash(contentHash)
          if (contentDuplicate != null) {
            val decision = interaction?.resolveDuplicate(contentDuplicate, exactSourceMatch = false)
            when (decision) {
              DuplicateDecision.OPEN_EXISTING -> {
                cleanupUncommitted(bookId, importFolder)
                return@withLock ImportResult.OpenedExisting(contentDuplicate.id)
              }
              DuplicateDecision.IMPORT_COPY -> Unit
              DuplicateDecision.CANCEL,
              null -> {
                cleanupUncommitted(bookId, importFolder)
                return@withLock ImportResult.Cancelled
              }
            }
          }
        }

        progressListener.onProgress(ImportProgress(ImportStage.PREPARING_TEXT))
        val sourceFile =
          files.finalizeSource(
            stagingFolder = importFolder,
            copied = copied,
            format = parsed.format,
            displayName = safeSourceName,
          )
        progressListener.onProgress(ImportProgress(ImportStage.PREPARING_COVER))
        progressListener.onProgress(ImportProgress(ImportStage.SAVING))
        val committed =
          files.writeCommittedArtifacts(
            stagingFolder = importFolder,
            bookId = bookId,
            book = parsed,
            sourceFile = sourceFile,
            sourceHash = copied.sourceHash,
          )
        commitPointReached = true
        val finalFolder = files.moveCommittedToBooks(committed)
        val verified = files.loadCommitted(finalFolder, bookId, verifyHashes = false)
        val ready = readyEntity(initial.copy(sourceSizeBytes = copied.sizeBytes), verified)
        catalog.update(ready)
        progressListener.onProgress(ImportProgress(ImportStage.COMPLETE))
        ImportResult.Imported(bookId)
      } catch (cancelled: CancellationException) {
        if (!commitPointReached) cleanupUncommitted(bookId, importFolder)
        throw cancelled
      } catch (exception: Exception) {
        if (!commitPointReached) cleanupUncommitted(bookId, importFolder)
        throw exception
      }
      }
    }

  suspend fun recoverOnStartup(): RecoveryReport =
    withContext(Dispatchers.IO) {
      importMutex.withLock {
      var completedImports = 0
      var completedDeletions = 0
      var cleanedImports = 0
      var cleanedTrash = 0
      val importing = catalog.listImporting()
      val importingIds = importing.mapTo(mutableSetOf()) { it.id }
      for (row in importing) {
        val finalFolder = files.bookFolder(row.id)
        val stagedFolder = files.importFolder(row.id)
        var committed = files.findCommitted(finalFolder, row.id)
        if (committed == null) {
          committed = files.findCommitted(stagedFolder, row.id)
          if (committed != null) {
            committed =
              try {
                val moved = files.moveCommittedToBooks(committed)
                files.loadCommitted(moved, row.id, verifyHashes = false)
              } catch (_: Exception) {
                null
              }
          }
        }
        if (committed != null) {
          runCatching { catalog.update(readyEntity(row, committed)) }
            .onSuccess { completedImports++ }
        } else {
          files.deleteImport(row.id)
          catalog.deleteImporting(row.id)
          cleanedImports++
        }
      }

      val deletingRows = catalog.listAll().filter { it.contentState == ContentState.DELETING }
      for (row in deletingRows) {
        val trash = files.moveBookToTrash(row.id)
        if (trash != null) files.deleteTrash(trash)
        catalog.deleteDeleting(row.id)
        completedDeletions++
      }

      for (folder in files.listImports()) {
        if (folder.name in importingIds) continue
        val committed = files.findCommitted(folder, folder.name)
        if (committed == null) {
          runCatching { folder.deleteRecursively() }.onSuccess { cleanedImports++ }
        }
      }
      for (trash in files.listTrash()) {
        if (files.hasValidTrashMarker(trash)) {
          runCatching { files.deleteTrash(trash) }.onSuccess { cleanedTrash++ }
        }
      }
        RecoveryReport(completedImports, completedDeletions, cleanedImports, cleanedTrash)
      }
    }

  suspend fun deleteBook(bookId: String): Boolean =
    withContext(Dispatchers.IO) {
      importMutex.withLock {
      val row = catalog.findById(bookId) ?: return@withLock false
      if (row.contentState !in setOf(ContentState.READY, ContentState.BROKEN)) return@withLock false
      catalog.markDeleting(bookId)
      try {
        val trash = files.moveBookToTrash(bookId)
        catalog.deleteDeleting(bookId)
        if (trash != null) files.deleteTrash(trash)
        true
      } catch (exception: Exception) {
        // The DELETING state remains recoverable; startup retries the same idempotent operation.
        throw StorageException(StorageErrorCode.IO_FAILURE, exception)
      }
      }
    }

  private suspend fun cleanupUncommitted(bookId: String, importFolder: File) {
    runCatching { files.deleteImport(bookId) }
    runCatching { catalog.deleteImporting(bookId) }
    if (importFolder.exists()) runCatching { importFolder.deleteRecursively() }
  }

  private fun readyEntity(base: BookEntity, committed: CommittedBook): BookEntity {
    val document = committed.document
    val manifest = committed.manifest
    val folder = files.bookFolder(manifest.bookId)
    val firstItem = firstWordPosition(folder, manifest)
    val metadata = document.metadata
    return base.copy(
      title = metadata.title,
      authorDisplay = metadata.authorDisplay,
      language = metadata.language,
      format = manifest.format,
      archiveEntryPath = manifest.archiveEntryPath,
      archiveEntryIndex = manifest.archiveEntryIndex,
      sourceHash = manifest.sourceHash,
      contentHash = manifest.contentHash,
      wordCount = manifest.summary.wordCount,
      chapterCount = manifest.summary.chapterCount,
      coverRelativePath = ManifestCodec.coverFileName(manifest),
      contentState = ContentState.READY,
      readingState = ReadingState.UNREAD,
      currentItemOrdinal = firstItem.itemOrdinal,
      currentCharOffset = firstItem.charOffset,
      currentWordIndex = firstItem.wordIndex,
      currentSentenceId = firstItem.sentenceId,
      currentChapterKey = firstItem.chapterKey,
      currentChapterTitle = firstItem.chapterTitle,
      positionFingerprint = firstItem.fingerprint,
      positionRevision = 0,
      progressPercent = 0,
      lastSpeedWpm = null,
      parserVersion = manifest.parserVersion,
      indexVersion = manifest.indexVersion,
      layoutVersion = manifest.layoutVersion,
      lastOpenedAt = null,
      lastSavedAt = null,
      lastErrorCode = null,
    )
  }

  private fun firstWordPosition(folder: File, manifest: StorageManifest): FirstWordPosition {
    val indexPath =
      File(folder, manifest.files.first { it.role == "reading_index" }.name)
    ReadingIndexReader.open(indexPath).use { index ->
      val word = index.readWord(0)
      val section = index.sections()[word.sectionIndex]
      val text =
        File(folder, manifest.files.first { it.role == "reading_text" }.name)
          .reader(Charsets.UTF_8)
          .use { reading ->
            var remainingSkip = word.startChar.toLong()
            while (remainingSkip > 0) {
              val skipped = reading.skip(remainingSkip)
              if (skipped <= 0) break
              remainingSkip -= skipped
            }
            CharArray(word.endChar - word.startChar).also { reading.read(it) }.concatToString()
          }
      return FirstWordPosition(
        itemOrdinal = word.itemOrdinal,
        charOffset = word.startChar,
        wordIndex = 0,
        sentenceId = word.sentenceId,
        chapterKey = section.chapterKey,
        chapterTitle = section.title,
        fingerprint =
          ReadingFingerprint.calculate(
            readingText(folder, manifest),
            word.startChar,
            word.endChar,
          ),
      )
    }
  }

  private fun readingText(file: File, manifest: StorageManifest): String =
    File(file, manifest.files.first { it.role == "reading_text" }.name).readText(Charsets.UTF_8)

  private data class FirstWordPosition(
    val itemOrdinal: Int,
    val charOffset: Int,
    val wordIndex: Int,
    val sentenceId: Int,
    val chapterKey: String,
    val chapterTitle: String,
    val fingerprint: String,
  )

  private object Fb2LimitsDefaults {
    const val MAX_SOURCE_BYTES = 50L * 1024 * 1024
  }
}
