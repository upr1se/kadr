package com.upr1se.kadr.data.storage

import com.upr1se.kadr.data.db.ContentState
import com.upr1se.kadr.data.db.ReadingState
import com.upr1se.kadr.testing.FakeBookCatalog
import com.upr1se.kadr.testing.SyntheticFb2
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BookRepositoryTest {
  @get:Rule val temporaryFolder = TemporaryFolder()

  @Test
  fun missingIndexIsRebuiltFromPrivateSource() = runTest {
    val harness = harness()
    val row = harness.importBook()
    File(harness.store.bookFolder(row.id), "reading_index.bin").delete()

    val opened = harness.repository.openBook(row.id)

    check(opened is OpenBookResult.Success)
    opened.book.use {
      assertEquals("Глава", it.metadata.title)
      assertEquals(3, it.wordCount)
      assertEquals("Текст для чтения.", it.readingText)
    }
    assertTrue(
      harness.store.bookFolder(row.id).listFiles()?.any {
        it.name.startsWith("reading_index.") && it.name.endsWith(".bin")
      } == true,
    )
    val refreshed = requireNotNull(harness.catalog.findById(row.id))
    assertEquals(ContentState.READY, refreshed.contentState)
  }

  @Test
  fun missingSourceMarksBookBrokenAndReturnsSafeError() = runTest {
    val harness = harness()
    val row = harness.importBook()
    File(harness.store.bookFolder(row.id), "source.fb2").delete()
    File(harness.store.bookFolder(row.id), "reading_index.bin").delete()

    val opened = harness.repository.openBook(row.id)

    assertTrue(opened is OpenBookResult.Unavailable)
    assertEquals("BOOK_INTEGRITY", (opened as OpenBookResult.Unavailable).errorCode)
    val refreshed = requireNotNull(harness.catalog.findById(row.id))
    assertEquals(ContentState.BROKEN, refreshed.contentState)
  }

  @Test
  fun positionRevisionPreventsOlderWriteFromRollingBack() = runTest {
    val harness = harness()
    val row = harness.importBook()
    val opened = harness.repository.openBook(row.id)
    check(opened is OpenBookResult.Success)

    opened.book.use { book ->
      assertTrue(
        harness.repository.savePosition(
          book,
          savedPosition(
            wordIndex = 2,
            itemOrdinal = book.index.readWord(2).itemOrdinal,
            revision = 2,
            state = ReadingState.READING,
          ),
        ),
      )
      assertFalse(
        harness.repository.savePosition(
          book,
          savedPosition(
            wordIndex = 1,
            itemOrdinal = book.index.readWord(1).itemOrdinal,
            revision = 1,
            state = ReadingState.READING,
          ),
        ),
      )
    }

    val refreshed = requireNotNull(harness.catalog.findById(row.id))
    assertEquals(2, refreshed.currentWordIndex)
    assertEquals(2, refreshed.positionRevision)
    assertEquals(ReadingState.READING, refreshed.readingState)
    assertEquals(100, BookRepository.progressPercent(ReadingState.COMPLETED, 2, 3))
    assertEquals(99, BookRepository.progressPercent(ReadingState.READING, 2, 3))
    assertEquals(0, BookRepository.progressPercent(ReadingState.UNREAD, 0, 3))
    assertEquals(33, BookRepository.progressPercent(ReadingState.READING, 0, 3))
  }

  @Test
  fun resetKeepsPerBookSpeedAndReturnsToFirstWord() = runTest {
    val harness = harness()
    val row = harness.importBook()
    harness.repository.saveBookSpeed(row.id, 425)
    val opened = harness.repository.openBook(row.id)
    check(opened is OpenBookResult.Success)
    opened.book.use { book ->
      harness.repository.savePosition(
        book,
        savedPosition(
          wordIndex = 2,
          itemOrdinal = book.index.readWord(2).itemOrdinal,
          revision = 3,
          state = ReadingState.READING,
        ),
      )
      assertTrue(harness.repository.resetProgress(book, revision = 4))
    }

    val refreshed = requireNotNull(harness.catalog.findById(row.id))
    assertEquals(0, refreshed.currentWordIndex)
    assertEquals(ReadingState.UNREAD, refreshed.readingState)
    assertEquals(0, refreshed.progressPercent)
    assertEquals(425, refreshed.lastSpeedWpm)
  }

  @Test
  fun missingCoverIsOptionalAndDoesNotBreakReading() = runTest {
    val harness = harness()
    val cover = java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4))
    val result =
      harness.coordinator.import(
        ByteArrayImportSource(
          "covered.fb2",
          SyntheticFb2.xml(
            body = "<section><title>Глава</title><p>Текст.</p></section>",
            coverBase64 = cover,
          ).toByteArray(),
        ),
        interaction =
          object : ImportInteraction {
            override suspend fun chooseZipBook(
              candidates: List<com.upr1se.kadr.fb2.ZipBookCandidate>,
            ): com.upr1se.kadr.fb2.ZipBookCandidate? = candidates.firstOrNull()

            override suspend fun resolveDuplicate(
              existing: com.upr1se.kadr.data.db.BookEntity,
              exactSourceMatch: Boolean,
            ): DuplicateDecision = DuplicateDecision.IMPORT_COPY
          },
      )
    check(result is ImportResult.Imported)
    val folder = harness.store.bookFolder(result.bookId)
    File(folder, "cover.webp").delete()

    val opened = harness.repository.openBook(result.bookId)

    check(opened is OpenBookResult.Success)
    opened.book.use { assertEquals("Текст.", it.readingText) }
    assertEquals(ContentState.READY, harness.catalog.findById(result.bookId)?.contentState)
  }

  private fun savedPosition(
    wordIndex: Int,
    itemOrdinal: Int,
    revision: Long,
    state: ReadingState,
  ): SavedPosition =
    SavedPosition(
      itemOrdinal = itemOrdinal,
      charOffset = wordIndex * 3,
      wordIndex = wordIndex,
      sentenceId = 0,
      chapterKey = "b0.s1",
      chapterTitle = "Глава",
      fingerprint = "fingerprint-$wordIndex",
      revision = revision,
      readingState = state,
    )

  private fun harness(): Harness {
    val store =
      BookFileStore(
        filesDir = temporaryFolder.newFolder(),
        availableSpace = FixedAvailableSpace(1024L * 1024 * 1024),
        coverProcessor = CoverBytesProcessor { it },
      )
    val catalog = FakeBookCatalog()
    val parser = SyntheticFb2.parser()
    val coordinator = ImportCoordinator(store, parser, catalog)
    return Harness(store, catalog, BookRepository(catalog, store, parser))
  }

  private suspend fun Harness.importBook(): com.upr1se.kadr.data.db.BookEntity {
    val result =
      coordinator.import(
        ByteArrayImportSource(
          "book.fb2",
          SyntheticFb2.xml(
            title = "Глава",
            body = "<section><title>Глава</title><p>Текст для чтения.</p></section>",
          ).toByteArray(),
        ),
        interaction =
          object : ImportInteraction {
            override suspend fun chooseZipBook(
              candidates: List<com.upr1se.kadr.fb2.ZipBookCandidate>,
            ): com.upr1se.kadr.fb2.ZipBookCandidate? = candidates.firstOrNull()

            override suspend fun resolveDuplicate(
              existing: com.upr1se.kadr.data.db.BookEntity,
              exactSourceMatch: Boolean,
            ): DuplicateDecision = DuplicateDecision.IMPORT_COPY
          },
      )
    check(result is ImportResult.Imported)
    return requireNotNull(catalog.findById(result.bookId))
  }

  private data class Harness(
    val store: BookFileStore,
    val catalog: FakeBookCatalog,
    val coordinator: ImportCoordinator,
    val repository: BookRepository,
  ) {
    constructor(
      store: BookFileStore,
      catalog: FakeBookCatalog,
      repository: BookRepository,
    ) : this(
      store,
      catalog,
      ImportCoordinator(store, SyntheticFb2.parser(), catalog),
      repository,
    )
  }
}
