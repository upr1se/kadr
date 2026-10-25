package com.upr1se.kadr.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomBookDaoTest {
  private lateinit var database: KadrDatabase
  private lateinit var dao: BookDao

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    database =
      Room.inMemoryDatabaseBuilder(context, KadrDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    dao = database.bookDao()
  }

  @After
  fun tearDown() {
    database.close()
  }

  @Test
  fun hiddenStatesAreFilteredAndReadyBooksAreOrdered() = runBlocking {
    val hidden = book("hidden", ContentState.IMPORTING, lastOpened = null)
    val older = book("older", ContentState.READY, lastOpened = 10)
    val newer = book("newer", ContentState.READY, lastOpened = 20)
    val unopened = book("unopened", ContentState.READY, lastOpened = null)
    dao.insert(hidden)
    dao.insert(older)
    dao.insert(newer)
    dao.insert(unopened)

    val library = dao.observeLibrary().first()

    assertEquals(listOf("newer", "older", "unopened"), library.map { it.id })
    assertTrue((dao.observeContinueReading().first()) == null)
  }

  @Test
  fun revisionGuardCreatesSingleLatestPositionWrite() = runBlocking {
    dao.insert(book("book", ContentState.READY, lastOpened = null))

    val latest =
      dao.savePositionIfNewer(
        bookId = "book",
        itemOrdinal = 5,
        charOffset = 50,
        wordIndex = 5,
        sentenceId = 1,
        chapterKey = "b0.s1",
        chapterTitle = "Глава",
        fingerprint = "new",
        revision = 2,
        progressPercent = 60,
        readingState = ReadingState.READING,
        savedAt = 200,
      )
    val stale =
      dao.savePositionIfNewer(
        bookId = "book",
        itemOrdinal = 2,
        charOffset = 20,
        wordIndex = 2,
        sentenceId = 0,
        chapterKey = "b0.s1",
        chapterTitle = "Глава",
        fingerprint = "old",
        revision = 1,
        progressPercent = 30,
        readingState = ReadingState.READING,
        savedAt = 100,
      )

    assertEquals(1, latest)
    assertEquals(0, stale)
    val stored = requireNotNull(dao.findById("book"))
    assertEquals(5, stored.currentWordIndex)
    assertEquals(2, stored.positionRevision)
    assertEquals("new", stored.positionFingerprint)
  }

  @Test
  fun resetPreservesBookSpeedAndReturnsUnread() = runBlocking {
    dao.insert(
      book("book", ContentState.READY, lastOpened = null).copy(
        lastSpeedWpm = 450,
        currentWordIndex = 9,
        progressPercent = 70,
        readingState = ReadingState.READING,
        positionRevision = 4,
      ),
    )

    dao.resetProgress(
      bookId = "book",
      firstItemOrdinal = 1,
      firstCharOffset = 0,
      firstWordIndex = 0,
      firstSentenceId = 0,
      firstChapterKey = "b0.s1",
      firstChapterTitle = "Глава",
      fingerprint = "first",
      revision = 5,
      savedAt = 500,
    )

    val stored = requireNotNull(dao.findById("book"))
    assertEquals(ReadingState.UNREAD, stored.readingState)
    assertEquals(0, stored.currentWordIndex)
    assertEquals(0, stored.progressPercent)
    assertEquals(450, stored.lastSpeedWpm)
    assertEquals(5, stored.positionRevision)
  }

  private fun book(
    id: String,
    state: ContentState,
    lastOpened: Long?,
  ): BookEntity =
    BookEntity(
      id = id,
      title = "Книга $id",
      authorDisplay = "Автор",
      language = "ru",
      format = "FB2",
      sourceFileName = "$id.fb2",
      folderRelativePath = "books/$id",
      archiveEntryPath = null,
      archiveEntryIndex = null,
      sourceHash = id.repeat(32).take(64),
      contentHash = (id + id).repeat(22).take(64),
      sourceSizeBytes = 100,
      wordCount = 10,
      chapterCount = 1,
      coverRelativePath = null,
      contentState = state,
      readingState = ReadingState.UNREAD,
      currentItemOrdinal = 1,
      currentCharOffset = 0,
      currentWordIndex = 0,
      currentSentenceId = 0,
      currentChapterKey = "b0.s1",
      currentChapterTitle = "Глава",
      positionFingerprint = "fingerprint",
      positionRevision = 0,
      progressPercent = 0,
      lastSpeedWpm = null,
      parserVersion = 1,
      indexVersion = 1,
      layoutVersion = 1,
      importedAt = 1,
      lastOpenedAt = lastOpened,
      lastSavedAt = null,
      lastErrorCode = null,
    )
}
