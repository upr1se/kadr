package com.upr1se.kadr.data.storage

enum class StorageErrorCode {
  SOURCE_UNAVAILABLE,
  SOURCE_TOO_LARGE,
  NOT_ENOUGH_SPACE,
  INVALID_DERIVED_DATA,
  BOOK_JSON_TOO_LARGE,
  INVALID_MANIFEST,
  INVALID_PATH,
  MISSING_FILE,
  HASH_MISMATCH,
  INDEX_INVALID,
  IMPORT_CANCELLED,
  DUPLICATE_IMPORT,
  IO_FAILURE,
}

class StorageException(
  val code: StorageErrorCode,
  cause: Throwable? = null,
) : Exception(code.name, cause)

enum class ImportStage {
  COPYING,
  INSPECTING,
  PARSING,
  PREPARING_TEXT,
  PREPARING_COVER,
  SAVING,
  COMPLETE,
}

data class ImportProgress(
  val stage: ImportStage,
  val bytesCopied: Long = 0,
  val totalBytes: Long? = null,
)
