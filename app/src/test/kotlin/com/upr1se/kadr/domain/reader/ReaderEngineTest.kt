package com.upr1se.kadr.domain.reader

import com.upr1se.kadr.data.db.ReadingState
import com.upr1se.kadr.data.storage.BookFileStore
import com.upr1se.kadr.data.storage.BookRepository
import com.upr1se.kadr.data.storage.ByteArrayImportSource
import com.upr1se.kadr.data.storage.CoverBytesProcessor
import com.upr1se.kadr.data.storage.DuplicateDecision
import com.upr1se.kadr.data.storage.FixedAvailableSpace
import com.upr1se.kadr.data.storage.ImportCoordinator
import com.upr1se.kadr.data.storage.ImportInteraction
import com.upr1se.kadr.data.storage.ImportResult
import com.upr1se.kadr.data.storage.OpenBookResult
import com.upr1se.kadr.fb2.BoundaryKind
import com.upr1se.kadr.fb2.ZipBookCandidate
import com.upr1se.kadr.testing.FakeBookCatalog
import com.upr1se.kadr.testing.SyntheticFb2
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.math.roundToInt

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderEngineTest {
  @get:Rule val temporaryFolder = TemporaryFolder()

  @Test
  fun durationsCoverSpeedRangeBoundaryPriorityAndVisualMinimums() {
    for (speed in 100..800 step 25) {
      assertEquals((60_000.0 / speed).roundToInt().coerceAtLeast(1), ReaderDurations.baseMs(speed))
    }
    assertEquals(600, ReaderDurations.baseMs(100))
    assertEquals(75, ReaderDurations.baseMs(800))
    assertEquals(800, ReaderDurations.normalizeSpeed(813))
    assertEquals(100, ReaderDurations.normalizeSpeed(87))

    val none = ReaderDurations.wordDwell(300, BoundaryKind.NONE, "слово", false)
    val clause = ReaderDurations.wordDwell(300, BoundaryKind.CLAUSE, "слово,", false)
    val sentence = ReaderDurations.wordDwell(300, BoundaryKind.SENTENCE, "слово.", false)
    val paragraph = ReaderDurations.wordDwell(300, BoundaryKind.PARAGRAPH, "слово", false)
    assertTrue(clause > none)
    assertTrue(sentence > clause)
    assertTrue(paragraph > sentence)
    assertEquals(paragraph, ReaderDurations.wordDwell(300, BoundaryKind.VERSE_STANZA, "слово", false))
    assertTrue(ReaderDurations.visualDwell(800) >= 1_200)
    assertTrue(ReaderDurations.visualDwell(100) >= 1_200)
    assertEquals(400, ReaderDurations.resumeDwell(800, 75))
    assertTrue(ReaderDurations.wordDwell(100, BoundaryKind.NONE, "x".repeat(41), false) >= 600)
    assertTrue(ReaderDurations.wordDwell(800, BoundaryKind.NONE, "last", true) >= 400)
  }

  @Test
  fun focusIndexUsesUnicodeGraphemesAndPunctuationBounds() {
    assertEquals(0, FocusCalculator.calculate("я").focusIndex)
    assertEquals(1, FocusCalculator.calculate("кот").focusIndex)
    assertEquals(1, FocusCalculator.calculate("слово!").focusIndex)
    assertEquals(3, FocusCalculator.calculate("двенадцать").focusIndex)
    assertEquals(3, FocusCalculator.calculate("четырнадцать").focusIndex)
    assertEquals(4, FocusCalculator.calculate("преобразованный").focusIndex)
    assertEquals(0, FocusCalculator.calculate("«…»").focusIndex)
    assertEquals(2, FocusCalculator.calculate("«ёж»").focusIndex)
    val combining = FocusCalculator.calculate("е\u0301жик")
    assertEquals(1, combining.focusIndex)
    assertTrue(combining.graphemes.first() == "е\u0301")
    val emoji = FocusCalculator.calculate("👨‍👩‍👧‍👦")
    assertEquals(1, emoji.graphemes.size)
    assertEquals(0, emoji.focusIndex)
    assertTrue(FocusCalculator.calculate("кто-то").focusIndex in 0..4)
    assertTrue(FocusCalculator.calculate("42").focusIndex >= 0)
  }

  @Test
  fun playPauseAndResumeUseFreshDwellWithoutSkipping() = runTest {
    val harness = openHarness("<p>Один два три.</p>")
    val controller = harness.controller
    val initialState = controller.state.value
    assertEquals(PlaybackStatus.PAUSED, initialState.status)
    assertEquals("Один", initialState.item.text)

    controller.play()
    controller.play()
    advanceTimeBy(399)
    runCurrent()
    assertEquals("Один", controller.state.value.item.text)
    advanceTimeBy(1)
    runCurrent()
    assertEquals("два", controller.state.value.item.text)

    advanceTimeBy(100)
    controller.pause()
    assertEquals(PlaybackStatus.PAUSED, controller.state.value.status)
    advanceTimeBy(10_000)
    runCurrent()
    assertEquals("два", controller.state.value.item.text)

    controller.play()
    advanceTimeBy(399)
    runCurrent()
    assertEquals("два", controller.state.value.item.text)
    advanceTimeBy(1)
    runCurrent()
    assertEquals("три.", controller.state.value.item.text)
    controller.close()
    harness.opened.close()
  }

  @Test
  fun speedChangeAppliesAfterCurrentDwell() = runTest {
    val harness = openHarness("<p>Один два три.</p>")
    val controller = harness.controller
    controller.play()
    controller.setSpeed(600)

    advanceTimeBy(399)
    runCurrent()
    assertEquals("Один", controller.state.value.item.text)
    advanceTimeBy(1)
    runCurrent()
    assertEquals("два", controller.state.value.item.text)
    assertEquals(600, controller.state.value.speedWpm)
    controller.close()
    harness.opened.close()
  }

  @Test
  fun completionHappensOnlyAfterFinalDelayAndCheckpointIsCompleted() = runTest {
    val harness = openHarness("<p>Одно.</p>")
    val controller = harness.controller
    controller.play()
    advanceTimeBy(399)
    runCurrent()
    assertEquals(PlaybackStatus.PLAYING, controller.state.value.status)
    assertEquals("Одно.", controller.state.value.item.text)

    advanceTimeBy(1)
    runCurrent()
    assertEquals(PlaybackStatus.COMPLETED, controller.state.value.status)
    assertEquals(100, controller.state.value.progressPercent)
    assertEquals(ReaderReadingState.COMPLETED, harness.sink.checkpoints.last().readingState)
    controller.close()
    harness.opened.close()
  }

  @Test
  fun sentenceNavigationStaysPausedAndCrossesTextBoundaries() = runTest {
    val harness = openHarness("<p>Один. Два! Три?</p>")
    val controller = harness.controller
    assertFalse(controller.state.value.canPreviousSentence)
    assertTrue(controller.state.value.canNextSentence)

    assertTrue(controller.nextSentence())
    assertEquals("Два!", controller.state.value.item.text)
    assertEquals(PlaybackStatus.PAUSED, controller.state.value.status)
    assertTrue(controller.nextSentence())
    assertEquals("Три?", controller.state.value.item.text)
    assertFalse(controller.state.value.canNextSentence)
    assertTrue(controller.previousSentence())
    assertEquals("Два!", controller.state.value.item.text)
    assertTrue(controller.previousSentence())
    assertEquals("Один.", controller.state.value.item.text)
    assertFalse(controller.previousSentence())
    assertEquals("Один.", controller.state.value.item.text)
    controller.close()
    harness.opened.close()
  }

  @Test
  fun sixHundredWordChapterCreatesBreakAndPlaySkipsDuplicateHeading() = runTest {
    val first = List(600) { "слово$it" }.joinToString(" ")
    val xml =
      "<section><title>Первая</title><p>$first</p></section>" +
        "<section><title>Вторая</title><p>Следующая глава.</p></section>"
    val harness = openHarness(xml, initialSpeedWpm = 800)
    val controller = harness.controller
    controller.play()
    advanceTimeBy(90_000)
    runCurrent()

    assertEquals(PlaybackStatus.CHAPTER_BREAK, controller.state.value.status)
    assertEquals("Вторая", controller.state.value.nextChapterTitle)
    assertEquals(2, controller.state.value.nextChapterNumber)
    assertEquals("слово599", controller.state.value.item.text)

    val checkpoint = controller.checkpoint()
    controller.close()
    val reopened =
      ReaderController(
        document = controller.document,
        scope = this,
        checkpointSink = harness.sink,
        clock = MonotonicClock { testScheduler.currentTime },
        initialItemOrdinal = checkpoint.itemOrdinal,
        initialCurrentWordIndex = checkpoint.wordIndex,
        initialCharOffset = checkpoint.charOffset,
        initialChapterKey = checkpoint.chapterKey,
        initialFingerprint = checkpoint.fingerprint,
        initialRevision = checkpoint.revision,
        initialReadingState = checkpoint.readingState,
        initialSpeedWpm = 800,
        chapterAutoPause = true,
      )
    reopened.play()
    runCurrent()
    assertEquals(PlaybackStatus.CHAPTER_BREAK, reopened.state.value.status)

    reopened.play()
    runCurrent()
    assertEquals(PlaybackStatus.PLAYING, reopened.state.value.status)
    assertEquals("Следующая", reopened.state.value.item.text)
    assertTrue(reopened.state.value.item.kind == com.upr1se.kadr.data.storage.IndexItemKind.WORD)
    reopened.close()
    harness.opened.close()
  }

  @Test
  fun fiveHundredNinetyNineWordChapterContinuesAutomatically() = runTest {
    val first = List(599) { "слово$it" }.joinToString(" ")
    val xml =
      "<section><title>Первая</title><p>$first</p></section>" +
        "<section><title>Вторая</title><p>Дальше.</p></section>"
    val harness = openHarness(xml, initialSpeedWpm = 800)
    val controller = harness.controller
    controller.play()
    advanceTimeBy(90_000)
    runCurrent()

    assertTrue(controller.state.value.status != PlaybackStatus.CHAPTER_BREAK)
    assertTrue(controller.state.value.item.text in setOf("Вторая", "Дальше."))
    controller.close()
    harness.opened.close()
  }

  @Test
  fun disabledChapterAutoPauseNeverCreatesBreak() = runTest {
    val first = List(600) { "слово$it" }.joinToString(" ")
    val xml =
      "<section><title>Первая</title><p>$first</p></section>" +
        "<section><title>Вторая</title><p>Дальше.</p></section>"
    val harness = openHarness(xml, initialSpeedWpm = 800, autoPause = false)
    val controller = harness.controller
    controller.play()
    advanceTimeBy(90_000)
    runCurrent()

    assertTrue(controller.state.value.status != PlaybackStatus.CHAPTER_BREAK)
    controller.close()
    harness.opened.close()
  }

  @Test
  fun visualItemsDoNotAdvanceWordProgressAndHaveTheirOwnDwell() = runTest {
    val harness =
      openHarness(
        "<section><title>Глава</title><p>Слово.</p></section>" +
          "<section><title>Далее</title><p>Ещё.</p></section>",
        initialSpeedWpm = 800,
      )
    val controller = harness.controller
    controller.play()
    advanceTimeBy(400)
    runCurrent()
    assertEquals("Далее", controller.state.value.item.text)
    assertEquals(
      com.upr1se.kadr.data.storage.IndexItemKind.HEADING,
      controller.state.value.item.kind,
    )
    val progressAtHeading = controller.state.value.progressPercent
    advanceTimeBy(1_199)
    runCurrent()
    assertEquals("Далее", controller.state.value.item.text)
    advanceTimeBy(1)
    runCurrent()
    assertEquals("Ещё.", controller.state.value.item.text)
    assertTrue(progressAtHeading >= 1)
    assertTrue(controller.state.value.progressPercent > progressAtHeading)
    controller.close()
    harness.opened.close()
  }

  @Test
  fun tenMinuteAcceleratedReadingShowsEveryWordInOrder() = runTest {
    val words = List(7_300) { "слово$it" }
    val harness =
      openHarness(
        "<section><title>Длинная</title><p>${words.joinToString(" ")}</p></section>",
        initialSpeedWpm = 750,
      )
    val controller = harness.controller
    val seen = mutableListOf<Int>()
    val collector =
      launch(UnconfinedTestDispatcher(testScheduler)) {
        controller.state.collect { state ->
          state.item.wordIndex?.let { wordIndex ->
            if (state.item.kind == com.upr1se.kadr.data.storage.IndexItemKind.WORD &&
              seen.lastOrNull() != wordIndex
            ) {
              seen += wordIndex
            }
          }
        }
      }

    controller.play()
    advanceTimeBy(10 * 60 * 1_000L)
    runCurrent()

    assertEquals(PlaybackStatus.COMPLETED, controller.state.value.status)
    assertEquals((0 until 7_300).toList(), seen)
    collector.cancel()
    controller.close()
    harness.opened.close()
  }

  @Test
  fun failedCheckpointLeavesReaderPausedAndExposesNonBlockingWarning() = runTest {
    val harness = openHarness("<p>Один два.</p>", failSaves = true)
    val controller = harness.controller

    assertFalse(controller.pause())

    assertEquals(PlaybackStatus.PAUSED, controller.state.value.status)
    assertTrue(controller.state.value.saveFailed)
    assertEquals("Один", controller.state.value.item.text)
    controller.close()
    harness.opened.close()
  }

  @Test
  fun invalidSavedItemUsesFingerprintWithinSavedChapterAndReportsInexactRestore() = runTest {
    val source =
      SyntheticFb2.xml(
        body = "<section><title>Глава</title><p>Первый второй третий.</p></section>",
      )
    val base = openHarness(source = source)
    val expectedFingerprint = base.opened.index.readWord(1).let {
      ReadingFingerprint.calculate(base.opened.readingText, it.startChar, it.endChar)
    }
    val doc =
      ReadingDocument(
        base.opened.readingText,
        base.opened.index,
        base.opened.sections,
        base.opened.notes,
      )
    val restored =
      doc.restorePosition(
        itemOrdinal = 999,
        currentWordIndex = 99,
        currentCharOffset = 0,
        chapterKey = "b0.s1",
        fingerprint = expectedFingerprint,
      )
    assertFalse(restored.exact)
    assertEquals("второй", doc.item(restored.itemOrdinal).text)
    base.controller.close()
    base.opened.close()
  }

  private suspend fun TestScope.openHarness(
    body: String = "",
    source: String = SyntheticFb2.xml(body = body),
    initialSpeedWpm: Int = 300,
    autoPause: Boolean = true,
    failSaves: Boolean = false,
  ): Harness {
    val store =
      BookFileStore(
        filesDir = temporaryFolder.newFolder(),
        availableSpace = FixedAvailableSpace(1024L * 1024 * 1024),
        coverProcessor = CoverBytesProcessor { it },
      )
    val catalog = FakeBookCatalog()
    val parser = SyntheticFb2.parser()
    val coordinator = ImportCoordinator(store, parser, catalog)
    val result =
      coordinator.import(
        ByteArrayImportSource("book.fb2", source.toByteArray()),
        interaction =
          object : ImportInteraction {
            override suspend fun chooseZipBook(candidates: List<ZipBookCandidate>): ZipBookCandidate? =
              candidates.firstOrNull()

            override suspend fun resolveDuplicate(
              existing: com.upr1se.kadr.data.db.BookEntity,
              exactSourceMatch: Boolean,
            ): DuplicateDecision = DuplicateDecision.IMPORT_COPY
          },
      )
    check(result is ImportResult.Imported)
    val repository = BookRepository(catalog, store, parser)
    if (initialSpeedWpm != 300) repository.saveBookSpeed(result.bookId, initialSpeedWpm)
    val opened = repository.openBook(result.bookId)
    check(opened is OpenBookResult.Success)
    val book = opened.book
    val entity = book.entity
    val document =
      ReadingDocument(book.readingText, book.index, book.sections, book.notes)
    val sink = FakeSink(failSaves)
    val controller =
      ReaderController(
        document = document,
        scope = this,
        checkpointSink = sink,
        clock = MonotonicClock { testScheduler.currentTime },
        initialItemOrdinal = entity.currentItemOrdinal,
        initialCurrentWordIndex = entity.currentWordIndex,
        initialCharOffset = entity.currentCharOffset,
        initialChapterKey = entity.currentChapterKey,
        initialFingerprint = entity.positionFingerprint,
        initialRevision = entity.positionRevision,
        initialReadingState =
          when (entity.readingState) {
            ReadingState.UNREAD -> ReaderReadingState.UNREAD
            ReadingState.READING -> ReaderReadingState.READING
            ReadingState.COMPLETED -> ReaderReadingState.COMPLETED
          },
        initialSpeedWpm = entity.lastSpeedWpm ?: initialSpeedWpm,
        chapterAutoPause = autoPause,
      )
    return Harness(book, controller, sink)
  }

  private data class Harness(
    val opened: com.upr1se.kadr.data.storage.OpenedBook,
    val controller: ReaderController,
    val sink: FakeSink,
  )

  private class FakeSink(
    private val failSaves: Boolean,
  ) : ReaderCheckpointSink {
    var startedCount = 0
    val savedSpeeds = mutableListOf<Int>()
    val checkpoints = mutableListOf<ReaderCheckpoint>()

    override suspend fun markStarted() {
      startedCount++
    }

    override suspend fun saveSpeed(wpm: Int) {
      savedSpeeds += wpm
    }

    override suspend fun saveCheckpoint(checkpoint: ReaderCheckpoint): Boolean {
      checkpoints += checkpoint
      return !failSaves
    }
  }
}
