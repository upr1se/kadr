package com.upr1se.kadr.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
  @Insert(onConflict = OnConflictStrategy.ABORT)
  suspend fun insert(book: BookEntity)

  @Update
  suspend fun update(book: BookEntity)

  @Query("SELECT * FROM books WHERE id = :bookId LIMIT 1")
  suspend fun findById(bookId: String): BookEntity?

  @Query(
    """
    SELECT * FROM books
    WHERE contentState IN ('READY', 'BROKEN')
    ORDER BY
      CASE WHEN lastOpenedAt IS NULL THEN 1 ELSE 0 END,
      lastOpenedAt DESC,
      importedAt DESC
    """,
  )
  fun observeLibrary(): Flow<List<BookEntity>>

  @Query(
    """
    SELECT * FROM books
    WHERE contentState IN ('READY', 'BROKEN')
    ORDER BY
      CASE WHEN lastOpenedAt IS NULL THEN 1 ELSE 0 END,
      lastOpenedAt DESC,
      importedAt DESC
    """,
  )
  suspend fun listLibrary(): List<BookEntity>

  @Query(
    """
    SELECT * FROM books
    WHERE contentState = 'READY' AND readingState = 'READING'
    ORDER BY lastOpenedAt DESC
    LIMIT 1
    """,
  )
  fun observeContinueReading(): Flow<BookEntity?>

  @Query("SELECT * FROM books WHERE contentState = 'READY' AND sourceHash = :hash LIMIT 1")
  suspend fun findReadyBySourceHash(hash: String): BookEntity?

  @Query("SELECT * FROM books WHERE contentState = 'READY' AND contentHash = :hash LIMIT 1")
  suspend fun findReadyByContentHash(hash: String): BookEntity?

  @Query("SELECT * FROM books WHERE contentState = :state")
  suspend fun findAllByContentState(state: ContentState): List<BookEntity>

  @Query("SELECT * FROM books WHERE id = :bookId AND contentState = 'DELETING' LIMIT 1")
  suspend fun findDeleting(bookId: String): BookEntity?

  @Query("DELETE FROM books WHERE id = :bookId AND contentState = 'DELETING'")
  suspend fun deleteDeleting(bookId: String): Int

  @Query("DELETE FROM books WHERE id = :bookId AND contentState = 'IMPORTING'")
  suspend fun deleteImporting(bookId: String): Int

  @Query(
    """
    UPDATE books SET
      currentItemOrdinal = :itemOrdinal,
      currentCharOffset = :charOffset,
      currentWordIndex = :wordIndex,
      currentSentenceId = :sentenceId,
      currentChapterKey = :chapterKey,
      currentChapterTitle = :chapterTitle,
      positionFingerprint = :fingerprint,
      positionRevision = :revision,
      progressPercent = :progressPercent,
      readingState = :readingState,
      lastSavedAt = :savedAt
    WHERE id = :bookId AND positionRevision <= :revision
    """,
  )
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

  @Query(
    """
    UPDATE books SET
      readingState = 'READING',
      lastOpenedAt = :openedAt
    WHERE id = :bookId AND contentState = 'READY'
    """,
  )
  suspend fun markOpenedAndStarted(bookId: String, openedAt: Long): Int

  @Query(
    """
    UPDATE books SET lastOpenedAt = :openedAt
    WHERE id = :bookId AND contentState IN ('READY', 'BROKEN')
    """,
  )
  suspend fun markOpened(bookId: String, openedAt: Long): Int

  @Query(
    """
    UPDATE books SET
      lastSpeedWpm = :wpm
    WHERE id = :bookId AND contentState = 'READY'
    """,
  )
  suspend fun saveBookSpeed(bookId: String, wpm: Int): Int

  @Query(
    """
    UPDATE books SET
      currentItemOrdinal = :firstItemOrdinal,
      currentCharOffset = :firstCharOffset,
      currentWordIndex = :firstWordIndex,
      currentSentenceId = :firstSentenceId,
      currentChapterKey = :firstChapterKey,
      currentChapterTitle = :firstChapterTitle,
      positionFingerprint = :fingerprint,
      positionRevision = :revision,
      progressPercent = 0,
      readingState = 'UNREAD',
      lastSavedAt = :savedAt
    WHERE id = :bookId AND positionRevision <= :revision
    """,
  )
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

  @Query(
    """
    UPDATE books SET
      contentState = 'BROKEN',
      lastErrorCode = :errorCode
    WHERE id = :bookId
    """,
  )
  suspend fun markBroken(bookId: String, errorCode: String): Int

  @Query(
    """
    UPDATE books SET
      contentState = 'DELETING'
    WHERE id = :bookId AND contentState IN ('READY', 'BROKEN')
    """,
  )
  suspend fun markDeleting(bookId: String): Int

  @Query("SELECT * FROM books WHERE contentState = 'IMPORTING'")
  suspend fun listImporting(): List<BookEntity>

  @Query("SELECT * FROM books")
  suspend fun listAll(): List<BookEntity>
}
