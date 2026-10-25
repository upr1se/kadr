package com.upr1se.kadr.testing

import com.upr1se.kadr.data.db.BookCatalog
import com.upr1se.kadr.data.db.BookEntity
import com.upr1se.kadr.data.db.ContentState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeBookCatalog : BookCatalog {
  val rows = MutableStateFlow<List<BookEntity>>(emptyList())

  override suspend fun insert(book: BookEntity) {
    check(rows.value.none { it.id == book.id })
    rows.value = rows.value + book
  }

  override suspend fun update(book: BookEntity) {
    rows.value = rows.value.map { if (it.id == book.id) book else it }
  }

  override suspend fun findById(bookId: String): BookEntity? =
    rows.value.firstOrNull { it.id == bookId }

  override suspend fun findReadyBySourceHash(hash: String): BookEntity? =
    rows.value.firstOrNull { it.contentState == ContentState.READY && it.sourceHash == hash }

  override suspend fun findReadyByContentHash(hash: String): BookEntity? =
    rows.value.firstOrNull { it.contentState == ContentState.READY && it.contentHash == hash }

  override suspend fun listLibrary(): List<BookEntity> =
    rows.value.filter { it.contentState == ContentState.READY || it.contentState == ContentState.BROKEN }

  override suspend fun listImporting(): List<BookEntity> =
    rows.value.filter { it.contentState == ContentState.IMPORTING }

  override suspend fun listAll(): List<BookEntity> = rows.value

  override suspend fun markBroken(bookId: String, errorCode: String) {
    rows.value =
      rows.value.map {
        if (it.id == bookId) {
          it.copy(contentState = ContentState.BROKEN, lastErrorCode = errorCode)
        } else {
          it
        }
      }
  }

  override suspend fun markDeleting(bookId: String) {
    rows.value =
      rows.value.map {
        if (it.id == bookId) it.copy(contentState = ContentState.DELETING) else it
      }
  }

  override suspend fun findDeleting(bookId: String): BookEntity? =
    rows.value.firstOrNull { it.id == bookId && it.contentState == ContentState.DELETING }

  override suspend fun deleteDeleting(bookId: String) {
    rows.value = rows.value.filterNot { it.id == bookId && it.contentState == ContentState.DELETING }
  }

  override suspend fun deleteImporting(bookId: String) {
    rows.value = rows.value.filterNot { it.id == bookId && it.contentState == ContentState.IMPORTING }
  }

  override suspend fun markOpened(bookId: String, openedAt: Long) {
    rows.value = rows.value.map { if (it.id == bookId) it.copy(lastOpenedAt = openedAt) else it }
  }

  override suspend fun markOpenedAndStarted(bookId: String, openedAt: Long) {
    rows.value =
      rows.value.map {
        if (it.id == bookId) {
          it.copy(
            readingState = com.upr1se.kadr.data.db.ReadingState.READING,
            lastOpenedAt = openedAt,
          )
        } else {
          it
        }
      }
  }

  override suspend fun saveBookSpeed(bookId: String, wpm: Int) {
    rows.value = rows.value.map { if (it.id == bookId) it.copy(lastSpeedWpm = wpm) else it }
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
    readingState: com.upr1se.kadr.data.db.ReadingState,
    savedAt: Long,
  ): Int {
    var changed = 0
    rows.value =
      rows.value.map {
        if (it.id == bookId && it.positionRevision <= revision) {
          changed = 1
          it.copy(
            currentItemOrdinal = itemOrdinal,
            currentCharOffset = charOffset,
            currentWordIndex = wordIndex,
            currentSentenceId = sentenceId,
            currentChapterKey = chapterKey,
            currentChapterTitle = chapterTitle,
            positionFingerprint = fingerprint,
            positionRevision = revision,
            progressPercent = progressPercent,
            readingState = readingState,
            lastSavedAt = savedAt,
          )
        } else {
          it
        }
      }
    return changed
  }

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
  ): Int {
    var changed = 0
    rows.value =
      rows.value.map {
        if (it.id == bookId && it.positionRevision <= revision) {
          changed = 1
          it.copy(
            currentItemOrdinal = firstItemOrdinal,
            currentCharOffset = firstCharOffset,
            currentWordIndex = firstWordIndex,
            currentSentenceId = firstSentenceId,
            currentChapterKey = firstChapterKey,
            currentChapterTitle = firstChapterTitle,
            positionFingerprint = fingerprint,
            positionRevision = revision,
            progressPercent = 0,
            readingState = com.upr1se.kadr.data.db.ReadingState.UNREAD,
            lastSavedAt = savedAt,
          )
        } else {
          it
        }
      }
    return changed
  }

  override fun observeLibrary(): Flow<List<BookEntity>> = rows.map { list ->
    list.filter { it.contentState == ContentState.READY || it.contentState == ContentState.BROKEN }
  }

  override fun observeContinueReading(): Flow<BookEntity?> = rows.map { list ->
    list.filter {
      it.contentState == ContentState.READY &&
        it.readingState == com.upr1se.kadr.data.db.ReadingState.READING
    }.maxByOrNull { it.lastOpenedAt ?: Long.MIN_VALUE }
  }
}
