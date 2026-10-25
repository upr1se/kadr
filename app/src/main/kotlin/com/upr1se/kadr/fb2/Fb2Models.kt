package com.upr1se.kadr.fb2

enum class BookFormat {
  FB2,
  FB2_ZIP,
}

enum class ImportErrorCode {
  SOURCE_UNAVAILABLE,
  SOURCE_TOO_LARGE,
  UNSUPPORTED_ENCODING,
  BAD_ENCODING,
  BAD_ZIP,
  ZIP_ENCRYPTED,
  ZIP_HAS_NO_FB2,
  ZIP_LIMIT_EXCEEDED,
  UNSAFE_ZIP_PATH,
  DOCTYPE_FORBIDDEN,
  BAD_XML,
  NOT_FB2,
  XML_DEPTH_EXCEEDED,
  XML_VALUE_TOO_LARGE,
  TEXT_LIMIT_EXCEEDED,
  WORD_LIMIT_EXCEEDED,
  NO_TEXT,
  CANCELLED,
  STORAGE_WRITE_FAILED,
}

class Fb2ImportException(
  val code: ImportErrorCode,
  cause: Throwable? = null,
) : Exception(code.name, cause)

enum class Fb2WarningCode {
  ENCODING_DECLARATION_CONFLICT,
  METADATA_TRUNCATED,
  NOTE_TRUNCATED,
  MISSING_NOTE,
  LONG_TOKEN_SPLIT,
  COVER_REJECTED,
  UNSUPPORTED_ELEMENT,
  DUPLICATE_NOTE_ID,
}

data class Fb2Warning(
  val code: Fb2WarningCode,
  val detail: String? = null,
)

data class BookMetadata(
  val title: String,
  val authorDisplay: String,
  val language: String?,
)

enum class BlockKind {
  PARAGRAPH,
  VERSE_LINE,
  TABLE_ROW,
  SUBTITLE,
}

enum class BoundaryKind {
  NONE,
  CLAUSE,
  VERSE_LINE,
  TABLE_ROW,
  SENTENCE,
  PARAGRAPH,
  VERSE_STANZA;

  fun strongest(other: BoundaryKind): BoundaryKind =
    if (priority >= other.priority) this else other

  private val priority: Int
    get() =
      when (this) {
        NONE -> 0
        CLAUSE -> 1
        VERSE_LINE -> 2
        TABLE_ROW -> 3
        SENTENCE -> 4
        PARAGRAPH,
        VERSE_STANZA -> 5
      }
}

data class ParsedWord(
  val wordIndex: Int,
  val startChar: Int,
  val endChar: Int,
  var sentenceId: Int,
  val chapterKey: String,
  var topLevelChapterIndex: Int,
  var boundary: BoundaryKind,
  var endsSentence: Boolean,
  val noteIds: List<String>,
)

enum class VisualKind {
  HEADING,
  IMAGE,
}

data class ParsedVisual(
  val kind: VisualKind,
  val text: String,
  val chapterKey: String,
  val anchorCharOffset: Int,
  val anchorWordIndex: Int,
)

data class ParsedSection(
  val chapterKey: String,
  val parentKey: String?,
  val level: Int,
  val title: String,
  val firstWordIndex: Int?,
  val wordCount: Int,
  val topLevelChapterIndex: Int?,
  val isSynthetic: Boolean,
) {
  val isEmpty: Boolean
    get() = firstWordIndex == null || wordCount == 0
}

data class ParsedNote(
  val noteId: String,
  val blocks: List<String>,
  val warnings: List<Fb2Warning> = emptyList(),
)

data class ParsedFb2(
  val sourceFileName: String,
  val format: BookFormat,
  val archiveEntryPath: String?,
  val archiveEntryIndex: Int?,
  val metadata: BookMetadata,
  val readingText: String,
  val words: List<ParsedWord>,
  val sections: List<ParsedSection>,
  val notes: List<ParsedNote>,
  val visualItems: List<ParsedVisual>,
  val warnings: List<Fb2Warning>,
  val coverBytes: ByteArray?,
) {
  val wordCount: Int
    get() = words.size

  val sentenceCount: Int
    get() = (words.maxOfOrNull { it.sentenceId } ?: -1) + 1

  val chapterCount: Int
    get() = sections.count { !it.isSynthetic && it.level == 0 && !it.isEmpty }
}

data class ZipBookCandidate(
  val entryIndex: Int,
  val path: String,
  val sizeBytes: Long,
  val displayPath: String,
)

sealed interface Fb2Inspection {
  val candidates: List<ZipBookCandidate>

  data class SingleBook(
    val format: BookFormat,
    val selectedCandidate: ZipBookCandidate?,
  ) : Fb2Inspection {
    override val candidates: List<ZipBookCandidate>
      get() = listOfNotNull(selectedCandidate)
  }

  data class NeedsSelection(
    override val candidates: List<ZipBookCandidate>,
  ) : Fb2Inspection
}
