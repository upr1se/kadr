package com.upr1se.kadr.fb2

data class Fb2Limits(
  val maxSourceBytes: Long = 50L * 1024 * 1024,
  val maxUncompressedZipBytes: Long = 250L * 1024 * 1024,
  val maxZipEntries: Int = 2_000,
  val maxZipEntryBytes: Long = 100L * 1024 * 1024,
  val maxZipEntryNameChars: Int = 1_024,
  val maxXmlDepth: Int = 256,
  val maxNormalizedTextCodeUnits: Int = 50_000_000,
  val maxWords: Int = 5_000_000,
  val maxAttributesPerElement: Int = 64,
  val maxXmlEventChars: Int = 1024 * 1024,
  val maxNotes: Int = 20_000,
  val maxNotesTextCodeUnits: Int = 8 * 1024 * 1024,
  val maxNoteCodeUnits: Int = 256_000,
  val maxSectionTitleGraphemes: Int = 512,
  val maxAuthorGraphemes: Int = 1_024,
  val maxSourceNameGraphemes: Int = 255,
  val maxLanguageChars: Int = 64,
  val maxWarnings: Int = 10_000,
  val maxCoverBytes: Int = 10 * 1024 * 1024,
  val maxCoverDimension: Int = 4_096,
  val maxCoverPixels: Long = 16_000_000,
)

object Fb2Versions {
  const val PARSER_VERSION = 1
  const val INDEX_VERSION = 1
  const val LAYOUT_VERSION = 1
  const val MAX_TOKEN_GRAPHEMES = 96
  const val LARGE_CHAPTER_MIN_WORDS = 600
}
