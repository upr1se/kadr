package com.upr1se.kadr.data.storage

import com.upr1se.kadr.data.db.ContentState
import com.upr1se.kadr.data.db.ReadingState
import com.upr1se.kadr.fb2.BookFormat
import com.upr1se.kadr.fb2.ZipBookCandidate
import com.upr1se.kadr.testing.FakeBookCatalog
import com.upr1se.kadr.testing.SyntheticFb2
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ImportCoordinatorTest {
  @get:Rule val temporaryFolder = TemporaryFolder()

  private fun harness(
    availableBytes: Long = 1024L * 1024 * 1024,
  ): Harness {
    val root = temporaryFolder.newFolder()
    val store =
      BookFileStore(
        filesDir = root,
        availableSpace = FixedAvailableSpace(availableBytes),
        coverProcessor = CoverBytesProcessor { it },
      )
    val catalog = FakeBookCatalog()
    return Harness(
      store = store,
      catalog = catalog,
      coordinator =
        ImportCoordinator(
          files = store,
          parser = SyntheticFb2.parser(),
          catalog = catalog,
          clock = { 1_000L },
        ),
    )
  }

  @Test
  fun importPersistsSourceAndReadableArtifacts() = runTest {
    val harness = harness()
    val external =
      temporaryFolder.newFile("external.fb2").also {
        it.writeText(book("Глава", "Текст для чтения."), Charsets.UTF_8)
      }
    val result =
      harness.coordinator.import(
        FileImportSource(external),
        interaction = NonInteractive,
      )
    check(result is ImportResult.Imported)
    external.delete()

    val row = harness.catalog.findById(result.bookId)
    assertNotNull(row)
    requireNotNull(row)
    assertEquals(ContentState.READY, row.contentState)
    assertEquals(ReadingState.UNREAD, row.readingState)
    assertEquals(3, row.wordCount)
    assertEquals(1, row.chapterCount)
    assertEquals(0, row.currentWordIndex)
    assertEquals(1, row.currentItemOrdinal)

    val folder = harness.store.bookFolder(result.bookId)
    val committed = harness.store.loadCommitted(folder, result.bookId, verifyHashes = true)
    assertEquals("Глава", committed.document.metadata.title)
    assertEquals("Текст для чтения.", File(folder, "reading.txt").readText())
    val indexPath = File(folder, "reading_index.bin")
    ReadingIndexReader.open(indexPath).use { index ->
      assertTrue(index.verifyChecksum())
      assertEquals(3, index.header.wordCount)
      assertEquals(1, index.header.chapterCount)
      assertEquals("Текст", index.readWord(0).let {
        File(folder, "reading.txt").readText().substring(it.startChar, it.endChar)
      })
      assertEquals(IndexItemKind.HEADING, index.readItem(0).kind)
      assertEquals(IndexItemKind.WORD, index.readItem(1).kind)
    }

    val afterRestart =
      ImportCoordinator(
        files = BookFileStore(
          filesDir = harness.store.root(),
          availableSpace = FixedAvailableSpace(1024L * 1024 * 1024),
          coverProcessor = CoverBytesProcessor { it },
        ),
        parser = SyntheticFb2.parser(),
        catalog = harness.catalog,
      )
    assertEquals(0, afterRestart.recoverOnStartup().cleanedImports)
    assertEquals("Текст для чтения.", File(folder, "reading.txt").readText())
  }

  @Test
  fun duplicateCanBeOpenedOrImportedAsIndependentCopy() = runTest {
    val harness = harness()
    val source = ByteArrayImportSource("duplicate.fb2", book("A", "Один и тот же текст.").toByteArray())
    val first = harness.coordinator.import(source, NonInteractive)
    check(first is ImportResult.Imported)

    var exactFlag = false
    val copyResult =
      harness.coordinator.import(
        source,
        object : ImportInteraction {
          override suspend fun chooseZipBook(candidates: List<ZipBookCandidate>): ZipBookCandidate? =
            candidates.first()

          override suspend fun resolveDuplicate(
            existing: com.upr1se.kadr.data.db.BookEntity,
            exactSourceMatch: Boolean,
          ): DuplicateDecision {
            exactFlag = exactSourceMatch
            return DuplicateDecision.IMPORT_COPY
          }
        },
      )
    check(copyResult is ImportResult.Imported)
    assertTrue(exactFlag)
    assertTrue(first.bookId != copyResult.bookId)
    assertEquals(2, harness.catalog.listLibrary().size)
  }

  @Test
  fun duplicateWithOpenExistingLeavesSingleBook() = runTest {
    val harness = harness()
    val source = ByteArrayImportSource("book.fb2", book("A", "Текст.").toByteArray())
    val first = harness.coordinator.import(source, NonInteractive)
    check(first is ImportResult.Imported)
    val second =
      harness.coordinator.import(
        source,
        object : ImportInteraction by NonInteractive {
          override suspend fun resolveDuplicate(
            existing: com.upr1se.kadr.data.db.BookEntity,
            exactSourceMatch: Boolean,
          ): DuplicateDecision = DuplicateDecision.OPEN_EXISTING
        },
      )
    assertEquals(ImportResult.OpenedExisting(first.bookId), second)
    assertEquals(1, harness.catalog.listLibrary().size)
    assertEquals(0, harness.store.listImports().size)
  }

  @Test
  fun contentDuplicateIsDetectedIndependentlyOfMetadata() = runTest {
    val harness = harness()
    harness.coordinator.import(
      ByteArrayImportSource("first.fb2", book("First", "Одинаковый текст.").toByteArray()),
      NonInteractive,
    )
    var observedExactSourceMatch = true
    val result =
      harness.coordinator.import(
        ByteArrayImportSource("second.fb2", book("Second", "Одинаковый текст.").toByteArray()),
        object : ImportInteraction by NonInteractive {
          override suspend fun resolveDuplicate(
            existing: com.upr1se.kadr.data.db.BookEntity,
            exactSourceMatch: Boolean,
          ): DuplicateDecision {
            observedExactSourceMatch = exactSourceMatch
            return DuplicateDecision.OPEN_EXISTING
          }
        },
      )
    check(result is ImportResult.OpenedExisting)
    assertFalse(observedExactSourceMatch)
    assertEquals(1, harness.catalog.listLibrary().size)
  }

  @Test
  fun multipleZipBooksRequireChoiceAndPreserveSelectedPath() = runTest {
    val harness = harness()
    val zipFile = temporaryFolder.newFile("collection.zip")
    ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
      for ((name, title) in listOf("first.fb2" to "First", "second.FB2" to "Second")) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(book(title, "Текст $title.").toByteArray())
        zip.closeEntry()
      }
    }
    var offered = emptyList<ZipBookCandidate>()
    val result =
      harness.coordinator.import(
        FileImportSource(zipFile),
        object : ImportInteraction {
          override suspend fun chooseZipBook(candidates: List<ZipBookCandidate>): ZipBookCandidate? {
            offered = candidates
            return candidates[1]
          }

          override suspend fun resolveDuplicate(
            existing: com.upr1se.kadr.data.db.BookEntity,
            exactSourceMatch: Boolean,
          ): DuplicateDecision = DuplicateDecision.CANCEL
        },
      )
    check(result is ImportResult.Imported)
    assertEquals(2, offered.size)
    val row = harness.catalog.findById(result.bookId)
    requireNotNull(row)
    assertEquals("Second", row.title)
    assertEquals("second.FB2", row.archiveEntryPath)
    assertEquals(1, row.archiveEntryIndex)
    assertEquals(BookFormat.FB2_ZIP.name, row.format)
  }

  @Test
  fun cancellingZipSelectionRemovesHiddenImport() = runTest {
    val harness = harness()
    val zipFile = temporaryFolder.newFile("cancel.zip")
    ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
      for (name in listOf("a.fb2", "b.fb2")) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(book(name, "Текст.").toByteArray())
        zip.closeEntry()
      }
    }
    val result =
      harness.coordinator.import(
        FileImportSource(zipFile),
        object : ImportInteraction {
          override suspend fun chooseZipBook(candidates: List<ZipBookCandidate>): ZipBookCandidate? =
            null

          override suspend fun resolveDuplicate(
            existing: com.upr1se.kadr.data.db.BookEntity,
            exactSourceMatch: Boolean,
          ): DuplicateDecision = DuplicateDecision.CANCEL
        },
      )

    assertEquals(ImportResult.Cancelled, result)
    assertTrue(harness.catalog.listAll().isEmpty())
    assertTrue(harness.store.listImports().isEmpty())
  }

  @Test
  fun startupCompletesCommittedImportThatMissedRoomUpdate() = runTest {
    val harness = harness()
    val result = harness.coordinator.import(
      ByteArrayImportSource("book.fb2", book("A", "Текст.").toByteArray()),
      NonInteractive,
    )
    check(result is ImportResult.Imported)
    val row = requireNotNull(harness.catalog.findById(result.bookId))
    val finalFolder = harness.store.bookFolder(result.bookId)
    val stagedFolder = harness.store.importFolder(result.bookId)
    Files.move(finalFolder.toPath(), stagedFolder.toPath())
    harness.catalog.update(row.copy(contentState = ContentState.IMPORTING))

    val report = harness.coordinator.recoverOnStartup()

    assertEquals(1, report.completedImports)
    val recovered = requireNotNull(harness.catalog.findById(result.bookId))
    assertEquals(ContentState.READY, recovered.contentState)
    assertTrue(harness.store.bookFolder(result.bookId).isDirectory)
    assertFalse(stagedFolder.exists())
  }

  @Test
  fun startupRemovesUncommittedImportWithoutShowingBook() = runTest {
    val harness = harness()
    val initialRow =
      libraryRow(
        id = "importing-id",
        state = ContentState.IMPORTING,
      )
    harness.catalog.insert(initialRow)
    harness.store.createImportFolder(initialRow.id).resolve("upload.tmp").writeText("partial")

    val report = harness.coordinator.recoverOnStartup()

    assertEquals(1, report.cleanedImports)
    assertTrue(harness.catalog.listImporting().isEmpty())
    assertFalse(harness.store.importFolder(initialRow.id).exists())
  }

  @Test
  fun deleteBookRemovesFolderAndCatalogRow() = runTest {
    val harness = harness()
    val result = harness.coordinator.import(
      ByteArrayImportSource("book.fb2", book("A", "Текст.").toByteArray()),
      NonInteractive,
    )
    check(result is ImportResult.Imported)

    assertTrue(harness.coordinator.deleteBook(result.bookId))

    assertEquals(null, harness.catalog.findById(result.bookId))
    assertFalse(harness.store.bookFolder(result.bookId).exists())
    assertTrue(harness.store.listTrash().isEmpty())
  }

  @Test
  fun insufficientSpaceLeavesNoHiddenBookOrTemporaryFolder() = runTest {
    val harness = harness(availableBytes = 1024)
    val error =
      runCatching {
        harness.coordinator.import(
          ByteArrayImportSource("book.fb2", book("A", "Текст.").toByteArray()),
          NonInteractive,
        )
      }.exceptionOrNull()

    assertTrue(error is StorageException)
    assertEquals(StorageErrorCode.NOT_ENOUGH_SPACE, (error as StorageException).code)
    assertTrue(harness.catalog.listAll().isEmpty())
    assertTrue(harness.store.listImports().isEmpty())
  }

  @Test
  fun corruptedIndexFailsClosed() = runTest {
    val harness = harness()
    val result = harness.coordinator.import(
      ByteArrayImportSource("book.fb2", book("A", "Текст для индекса.").toByteArray()),
      NonInteractive,
    )
    check(result is ImportResult.Imported)
    val index = File(harness.store.bookFolder(result.bookId), "reading_index.bin")
    index.writeBytes(byteArrayOf(1, 2, 3, 4))

    val error = runCatching { ReadingIndexReader.open(index) }.exceptionOrNull()
    assertTrue(error is StorageException)
    assertEquals(StorageErrorCode.INDEX_INVALID, (error as StorageException).code)
  }

  @Test
  fun startupDoesNotDeleteUnknownPermanentBookFolder() = runTest {
    val harness = harness()
    val unknown = File(harness.store.root(), "books/unknown-book").apply { mkdirs() }
    File(unknown, "keep.txt").writeText("diagnostic")

    harness.coordinator.recoverOnStartup()

    assertTrue(unknown.isDirectory)
    assertEquals("diagnostic", File(unknown, "keep.txt").readText())
  }

  private fun book(title: String, paragraph: String): String =
    SyntheticFb2.xml(
      title = title,
      body = "<section><title>$title</title><p>$paragraph</p></section>",
    )

  private fun libraryRow(
    id: String,
    state: ContentState,
  ): com.upr1se.kadr.data.db.BookEntity =
    com.upr1se.kadr.data.db.BookEntity(
      id = id,
      title = "Hidden",
      authorDisplay = null,
      language = null,
      format = BookFormat.FB2.name,
      sourceFileName = "hidden.fb2",
      folderRelativePath = "books/$id",
      archiveEntryPath = null,
      archiveEntryIndex = null,
      sourceHash = "",
      contentHash = "",
      sourceSizeBytes = 0,
      wordCount = 0,
      chapterCount = 0,
      coverRelativePath = null,
      contentState = state,
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
      parserVersion = 1,
      indexVersion = 1,
      layoutVersion = 1,
      importedAt = 1,
      lastOpenedAt = null,
      lastSavedAt = null,
      lastErrorCode = null,
    )

  private data class Harness(
    val store: BookFileStore,
    val catalog: FakeBookCatalog,
    val coordinator: ImportCoordinator,
  )

  private object NonInteractive : ImportInteraction {
    override suspend fun chooseZipBook(candidates: List<ZipBookCandidate>): ZipBookCandidate? =
      candidates.firstOrNull()

    override suspend fun resolveDuplicate(
      existing: com.upr1se.kadr.data.db.BookEntity,
      exactSourceMatch: Boolean,
    ): DuplicateDecision = DuplicateDecision.IMPORT_COPY
  }
}
