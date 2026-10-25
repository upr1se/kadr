package com.upr1se.kadr.data.db

import kotlinx.coroutines.flow.Flow

interface BookCatalog {
  suspend fun insert(book: BookEntity)
  suspend fun update(book: BookEntity)
  suspend fun findById(bookId: String): BookEntity?
  suspend fun findReadyBySourceHash(hash: String): BookEntity?
  suspend fun findReadyByContentHash(hash: String): BookEntity?
  suspend fun listLibrary(): List<BookEntity>
  suspend fun listImporting(): List<BookEntity>
  suspend fun listAll(): List<BookEntity>
  suspend fun markBroken(bookId: String, errorCode: String)
  suspend fun markDeleting(bookId: String)
  suspend fun findDeleting(bookId: String): BookEntity?
  suspend fun deleteDeleting(bookId: String)
  suspend fun deleteImporting(bookId: String)
  suspend fun markOpened(bookId: String, openedAt: Long)
  suspend fun markOpenedAndStarted(bookId: String, openedAt: Long)
  suspend fun saveBookSpeed(bookId: String, wpm: Int)
  suspend fun savePositionIfNewer(
    bookId: String,
    itemOrdinal: Int,
    charOffset: Int,
    wordIndex: Int,
    sentenceId: Int?,
    chapterKey: String?,
    chapterTitle: String?,
    fingerprint: String?,
    revision: Long,
    progressPercent: Int,
    readingState: ReadingState,
    savedAt: Long,
  ): Int
  suspend fun resetProgress(
    bookId: String,
    firstItemOrdinal: Int,
    firstCharOffset: Int,
    firstWordIndex: Int,
    firstSentenceId: Int,
    firstChapterKey: String?,
    firstChapterTitle: String?,
    fingerprint: String?,
    revision: Long,
    savedAt: Long,
  ): Int
  fun observeLibrary(): Flow<List<BookEntity>>
  fun observeContinueReading(): Flow<BookEntity?>
}

class RoomBookCatalog(
  private val dao: BookDao,
) : BookCatalog {
  override suspend fun insert(book: BookEntity) = dao.insert(book)

  override suspend fun update(book: BookEntity) = dao.update(book)

  override suspend fun findById(bookId: String): BookEntity? = dao.findById(bookId)

  override suspend fun findReadyBySourceHash(hash: String): BookEntity? =
    dao.findReadyBySourceHash(hash)

  override suspend fun findReadyByContentHash(hash: String): BookEntity? =
    dao.findReadyByContentHash(hash)

  override suspend fun listLibrary(): List<BookEntity> = dao.listLibrary()

  override suspend fun listImporting(): List<BookEntity> = dao.listImporting()

  override suspend fun listAll(): List<BookEntity> = dao.listAll()

  override suspend fun markBroken(bookId: String, errorCode: String) {
    dao.markBroken(bookId, errorCode)
  }

  override suspend fun markDeleting(bookId: String) {
    dao.markDeleting(bookId)
  }

  override suspend fun findDeleting(bookId: String): BookEntity? = dao.findDeleting(bookId)

  override suspend fun deleteDeleting(bookId: String) {
    dao.deleteDeleting(bookId)
  }

  override suspend fun deleteImporting(bookId: String) {
    dao.deleteImporting(bookId)
  }

  override suspend fun markOpened(bookId: String, openedAt: Long) {
    dao.markOpened(bookId, openedAt)
  }

  override suspend fun markOpenedAndStarted(bookId: String, openedAt: Long) {
    dao.markOpenedAndStarted(bookId, openedAt)
  }

  override suspend fun saveBookSpeed(bookId: String, wpm: Int) {
    dao.saveBookSpeed(bookId, wpm)
  }

  override suspend fun savePositionIfNewer(
    bookId: String,
    itemOrdinal: Int,
    charOffset: Int,
    wordIndex: Int,
    sentenceId: Int?,
    chapterKey: String?,
    chapterTitle: String?,
    fingerprint: String?,
    revision: Long,
    progressPercent: Int,
    readingState: ReadingState,
    savedAt: Long,
  ): Int =
    dao.savePositionIfNewer(
      bookId,
      itemOrdinal,
      charOffset,
      wordIndex,
      sentenceId,
      chapterKey,
      chapterTitle,
      fingerprint,
      revision,
      progressPercent,
      readingState,
      savedAt,
    )

  override suspend fun resetProgress(
    bookId: String,
    firstItemOrdinal: Int,
    firstCharOffset: Int,
    firstWordIndex: Int,
    firstSentenceId: Int,
    firstChapterKey: String?,
    firstChapterTitle: String?,
    fingerprint: String?,
    revision: Long,
    savedAt: Long,
  ): Int =
    dao.resetProgress(
      bookId,
      firstItemOrdinal,
      firstCharOffset,
      firstWordIndex,
      firstSentenceId,
      firstChapterKey,
      firstChapterTitle,
      fingerprint,
      revision,
      savedAt,
    )

  override fun observeLibrary(): Flow<List<BookEntity>> = dao.observeLibrary()

  override fun observeContinueReading(): Flow<BookEntity?> = dao.observeContinueReading()
}
