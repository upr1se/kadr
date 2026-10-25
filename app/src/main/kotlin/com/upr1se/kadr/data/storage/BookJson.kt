package com.upr1se.kadr.data.storage

import com.upr1se.kadr.fb2.BookMetadata
import com.upr1se.kadr.fb2.Fb2Warning
import com.upr1se.kadr.fb2.Fb2WarningCode
import com.upr1se.kadr.fb2.ParsedFb2
import com.upr1se.kadr.fb2.ParsedSection
import com.upr1se.kadr.fb2.ParsedVisual
import com.upr1se.kadr.fb2.VisualKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class BookDocument(
  val schemaVersion: Int,
  val metadata: BookJsonMetadata,
  val toc: List<BookJsonSection>,
  val notes: List<BookJsonNote>,
  val warnings: List<BookJsonWarning>,
  val visualItems: List<BookJsonVisual>,
)

@Serializable
data class BookJsonMetadata(
  val title: String,
  val authorDisplay: String,
  val language: String?,
  val format: String,
)

@Serializable
data class BookJsonSection(
  val chapterKey: String,
  val parentKey: String?,
  val level: Int,
  val title: String,
  val firstWordIndex: Int?,
  val wordCount: Int,
  val topLevelChapterIndex: Int?,
  val isSynthetic: Boolean,
)

@Serializable
data class BookJsonNote(
  val noteId: String,
  val blocks: List<String>,
  val warnings: List<BookJsonWarning>,
)

@Serializable
data class BookJsonWarning(
  val code: String,
  val detail: String?,
)

@Serializable
data class BookJsonVisual(
  val kind: String,
  val text: String,
  val chapterKey: String,
  val anchorCharOffset: Int,
  val anchorWordIndex: Int,
)

object BookJsonCodec {
  const val SCHEMA_VERSION = 1
  const val MAX_BOOK_JSON_BYTES = 16 * 1024 * 1024

  private val json =
    Json {
      ignoreUnknownKeys = true
      encodeDefaults = true
      isLenient = false
      explicitNulls = true
    }

  fun encode(book: ParsedFb2): ByteArray {
    val document =
      BookDocument(
        schemaVersion = SCHEMA_VERSION,
        metadata =
          BookJsonMetadata(
            title = book.metadata.title,
            authorDisplay = book.metadata.authorDisplay,
            language = book.metadata.language,
            format = book.format.name,
          ),
        toc = book.sections.map { it.toJson() },
        notes =
          book.notes.map {
            BookJsonNote(
              noteId = it.noteId,
              blocks = it.blocks,
              warnings = it.warnings.map { warning -> warning.toJson() },
            )
          },
        warnings = book.warnings.map { it.toJson() },
        visualItems =
          book.visualItems.map {
            BookJsonVisual(
              kind = it.kind.name,
              text = it.text,
              chapterKey = it.chapterKey,
              anchorCharOffset = it.anchorCharOffset,
              anchorWordIndex = it.anchorWordIndex,
            )
          },
      )
    val bytes = json.encodeToString(BookDocument.serializer(), document).toByteArray(Charsets.UTF_8)
    if (bytes.size > MAX_BOOK_JSON_BYTES) {
      throw StorageException(StorageErrorCode.BOOK_JSON_TOO_LARGE)
    }
    return bytes
  }

  fun decode(bytes: ByteArray): BookDocument {
    if (bytes.size > MAX_BOOK_JSON_BYTES) {
      throw StorageException(StorageErrorCode.INVALID_DERIVED_DATA)
    }
    val document =
      try {
        json.decodeFromString(BookDocument.serializer(), bytes.toString(Charsets.UTF_8))
      } catch (exception: Exception) {
        throw StorageException(StorageErrorCode.INVALID_DERIVED_DATA, exception)
      }
    if (document.schemaVersion != SCHEMA_VERSION) {
      throw StorageException(StorageErrorCode.INVALID_DERIVED_DATA)
    }
    return document
  }

  fun metadata(document: BookDocument): BookMetadata =
    BookMetadata(
      title = document.metadata.title,
      authorDisplay = document.metadata.authorDisplay,
      language = document.metadata.language,
    )

  fun sections(document: BookDocument): List<ParsedSection> =
    document.toc.map {
      ParsedSection(
        chapterKey = it.chapterKey,
        parentKey = it.parentKey,
        level = it.level,
        title = it.title,
        firstWordIndex = it.firstWordIndex,
        wordCount = it.wordCount,
        topLevelChapterIndex = it.topLevelChapterIndex,
        isSynthetic = it.isSynthetic,
      )
    }

  fun visuals(document: BookDocument): List<ParsedVisual> =
    document.visualItems.mapNotNull {
      val kind = runCatching { VisualKind.valueOf(it.kind) }.getOrNull() ?: return@mapNotNull null
      ParsedVisual(
        kind = kind,
        text = it.text,
        chapterKey = it.chapterKey,
        anchorCharOffset = it.anchorCharOffset,
        anchorWordIndex = it.anchorWordIndex,
      )
    }

  private fun ParsedSection.toJson() =
    BookJsonSection(
      chapterKey = chapterKey,
      parentKey = parentKey,
      level = level,
      title = title,
      firstWordIndex = firstWordIndex,
      wordCount = wordCount,
      topLevelChapterIndex = topLevelChapterIndex,
      isSynthetic = isSynthetic,
    )

  private fun Fb2Warning.toJson() =
    BookJsonWarning(
      code = code.name,
      detail = detail,
    )
}
