package com.upr1se.kadr.ui.screen

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.upr1se.kadr.R
import com.upr1se.kadr.data.db.ContentState
import com.upr1se.kadr.data.db.ReadingState
import com.upr1se.kadr.data.settings.AppSettings
import com.upr1se.kadr.data.storage.IndexItemKind
import com.upr1se.kadr.data.storage.LibraryBook
import com.upr1se.kadr.fb2.ZipBookCandidate
import com.upr1se.kadr.domain.reader.PlaybackStatus
import com.upr1se.kadr.domain.reader.ReaderItem
import com.upr1se.kadr.domain.reader.ReaderPlaybackState
import com.upr1se.kadr.fb2.ParsedSection
import com.upr1se.kadr.ui.library.LibraryScreen
import com.upr1se.kadr.ui.library.LibraryScreenState
import com.upr1se.kadr.ui.library.ImportUiState
import com.upr1se.kadr.ui.reader.ReaderScreen
import com.upr1se.kadr.ui.reader.ReaderScreenState
import com.upr1se.kadr.ui.settings.SettingsScreen
import com.upr1se.kadr.ui.theme.KadrTheme
import org.junit.Rule
import org.junit.Test

class ScreenAcceptanceTest {
  @get:Rule val composeRule = createComposeRule()

  private val context = InstrumentationRegistry.getInstrumentation().targetContext

  @Test
  fun filledLibraryShowsContinueCardAndVisibleMenu() {
    val book = libraryBook(readingState = ReadingState.READING, progress = 43)
    composeRule.setContent {
      KadrTheme {
        LibraryScreen(
          state =
            LibraryScreenState(
              isLoading = false,
              books = listOf(book),
              continueReading = book,
            ),
          snackbarHostState = SnackbarHostState(),
          onOpenSettings = {},
          onAddBook = {},
          onOpenBook = {},
          onDeleteRequested = {},
          onResetRequested = {},
          onInfoRequested = {},
          onDismissDialog = {},
          onConfirmDelete = {},
          onConfirmReset = {},
          onZipSelected = {},
          onDuplicateDecision = {},
          onCancelImport = {},
        )
      }
    }

    composeRule.onNodeWithText(context.getString(R.string.library_continue)).assertIsDisplayed()
    composeRule.onAllNodesWithText(book.title)[0].assertIsDisplayed()
    composeRule
      .onAllNodesWithContentDescription(context.getString(R.string.library_book_menu, book.title))[0]
      .assertIsDisplayed()
  }

  @Test
  fun importProgressAndZipSelectionExposeRequiredControls() {
    composeRule.setContent {
      KadrTheme {
        LibraryScreen(
          state =
            LibraryScreenState(
              isLoading = false,
              books = listOf(libraryBook(ReadingState.READING, 20)),
              importState =
                ImportUiState.ZipSelection(
                  listOf(
                    ZipBookCandidate(
                      entryIndex = 0,
                      path = "one.fb2",
                      sizeBytes = 1024,
                      displayPath = "one.fb2",
                    ),
                  ),
                ),
            ),
          snackbarHostState = SnackbarHostState(),
          onOpenSettings = {},
          onAddBook = {},
          onOpenBook = {},
          onDeleteRequested = {},
          onResetRequested = {},
          onInfoRequested = {},
          onDismissDialog = {},
          onConfirmDelete = {},
          onConfirmReset = {},
          onZipSelected = {},
          onDuplicateDecision = {},
          onCancelImport = {},
        )
      }
    }

    composeRule
      .onNodeWithText(context.getString(R.string.import_zip_title))
      .assertIsDisplayed()
    composeRule.onNodeWithText("one.fb2", substring = true).assertIsDisplayed()
    composeRule
      .onNodeWithText(context.getString(R.string.import_zip_choose))
      .assertIsDisplayed()
  }

  @Test
  fun pausedReaderShowsSentenceWordControlsAndChapterProgress() {
    setReader(playback = playback(PlaybackStatus.PAUSED))

    composeRule.onNodeWithText("Первый пример.").assertIsDisplayed()
    composeRule
      .onNodeWithContentDescription(context.getString(R.string.reader_word_accessibility, "Чтение"))
      .assertIsDisplayed()
    composeRule
      .onNodeWithContentDescription(context.getString(R.string.reader_play))
      .assertIsDisplayed()
    composeRule.onNodeWithText("Глава 1 из 2 · 50%").assertIsDisplayed()
  }

  @Test
  fun playingReaderHidesControlsAndTapPauses() {
    var paused = 0
    setReader(
      playback = playback(PlaybackStatus.PLAYING),
      onPause = { paused++ },
    )

    composeRule
      .onNodeWithContentDescription(context.getString(R.string.reader_word_accessibility, "Чтение"))
      .assertIsDisplayed()
    composeRule
      .onNodeWithContentDescription(context.getString(R.string.reader_play))
      .assertDoesNotExist()
    composeRule
      .onNodeWithContentDescription(context.getString(R.string.reader_word_accessibility, "Чтение"))
      .performClick()
    composeRule.runOnIdle { check(paused == 1) }
  }

  @Test
  fun readerSheetsExposeContentsAndNotesWithoutStartingPlayback() {
    val withNote =
      playback(PlaybackStatus.PAUSED).copy(
        noteIds = listOf("n1"),
      )
    setReader(
      playback = withNote,
      noteBlocks = listOf("Текст сноски."),
    )

    composeRule
      .onNodeWithContentDescription(context.getString(R.string.reader_contents_open))
      .performClick()
    composeRule.onNodeWithText(context.getString(R.string.contents_title)).assertIsDisplayed()
    composeRule.onNodeWithText("Начало").assertIsDisplayed()
    composeRule.onNodeWithText("Начало").performClick()
    composeRule
      .onNodeWithContentDescription(context.getString(R.string.reader_note_marker))
      .performClick()
    composeRule.onNodeWithText(context.getString(R.string.notes_title)).assertIsDisplayed()
    composeRule.onNodeWithText("Текст сноски.").assertIsDisplayed()
  }

  @Test
  fun speedValueOpensSheetWithoutStartingPlayback() {
    setReader(playback = playback(PlaybackStatus.PAUSED))

    composeRule.onNodeWithText("300 слов/мин").performClick()

    composeRule.onNodeWithText(context.getString(R.string.speed_title)).assertIsDisplayed()
    composeRule
      .onNodeWithText(context.getString(R.string.speed_above_recommended))
      .assertIsDisplayed()
  }

  @Test
  fun completedScreenShowsRequiredActions() {
    setReader(playback = playback(PlaybackStatus.COMPLETED))
    composeRule
      .onNodeWithText(context.getString(R.string.reader_completed_title))
      .assertIsDisplayed()
    composeRule
      .onNodeWithText(context.getString(R.string.reader_restart))
      .assertIsDisplayed()
  }

  @Test
  fun chapterBreakShowsNextChapterAndPlayAction() {
    setReader(playback = playback(PlaybackStatus.CHAPTER_BREAK))

    composeRule.onNodeWithText("Далее: Финал").assertIsDisplayed()
    composeRule
      .onNodeWithText(context.getString(R.string.reader_play))
      .assertIsDisplayed()
  }

  @Test
  fun errorScreenOffersSafeReturnToLibrary() {
    setReader(
      playback = playback(PlaybackStatus.PAUSED),
      errorCode = "BOOK_REBUILD_FAILED",
    )

    composeRule
      .onNodeWithText(context.getString(R.string.reader_error_title))
      .assertIsDisplayed()
    composeRule
      .onNodeWithText(context.getString(R.string.reader_return_library))
      .assertIsDisplayed()
  }

  @Test
  fun longWordAndFirstHintDoNotHideControlsAtTwoHundredPercentFont() {
    val longWord = "сверхдлинноесловобезпробелов".repeat(3).take(96)
    val longPlayback =
      playback(PlaybackStatus.PAUSED).copy(
        item =
          playback(PlaybackStatus.PAUSED).item.copy(
            text = longWord,
            endChar = longWord.length,
          ),
      )
    composeRule.setContent {
      val density = androidx.compose.ui.platform.LocalDensity.current
      androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides
          androidx.compose.ui.unit.Density(density.density, fontScale = 2f),
      ) {
        KadrTheme {
          ReaderScreen(
            state =
              ReaderScreenState(
                loading = false,
                bookTitle = "Длинное слово",
                playback = longPlayback,
                firstHintVisible = true,
                wordSizeSp = 72,
              ),
            snackbarHostState = SnackbarHostState(),
            onBack = {},
            onPlay = {},
            onPause = {},
            onTogglePlay = {},
            onPreviousSentence = {},
            onNextSentence = {},
            onSpeedDecrease = {},
            onSpeedIncrease = {},
            onSpeedSet = {},
            onSectionSelected = {},
            onDismissHint = {},
            onSettings = {},
            onRestart = {},
            onRequestInfo = {},
            onDismissInfo = {},
            onRetryLeave = {},
            onDismissLeaveFailure = {},
            onLeaveWithoutSaving = {},
            onLeaveError = {},
          )
        }
      }
    }

    composeRule
      .onNodeWithContentDescription(
        context.getString(R.string.reader_word_accessibility, longWord),
      )
      .assertIsDisplayed()
    composeRule
      .onNodeWithContentDescription(context.getString(R.string.reader_play))
      .assertIsDisplayed()
    composeRule.onNodeWithText(context.getString(R.string.reader_hint)).assertIsDisplayed()
  }

  @Test
  fun settingsExposesAllFixedOptionsAndVersion() {
    var sentenceEnabled = true
    composeRule.setContent {
      KadrTheme {
        SettingsScreen(
          settings = AppSettings(),
          versionName = "1.0.0",
          onBack = {},
          onThemeSelected = {},
          onWordSizeChanged = {},
          onDefaultSpeedChanged = {},
          onShowSentenceChanged = { sentenceEnabled = it },
          onChapterAutoPauseChanged = {},
          saveFailure = false,
          onSaveFailureShown = {},
        )
      }
    }

    composeRule.onNodeWithText(context.getString(R.string.settings_theme_sepia)).assertIsDisplayed()
    composeRule
      .onNodeWithText(context.getString(R.string.settings_show_sentence))
      .performClick()
    composeRule.runOnIdle { check(!sentenceEnabled) }
    composeRule.onNodeWithText("Версия 1.0.0").performScrollTo().assertIsDisplayed()
  }

  private fun setReader(
    playback: ReaderPlaybackState,
    noteBlocks: List<String> = emptyList(),
    onPause: () -> Unit = {},
    errorCode: String? = null,
  ) {
    composeRule.setContent {
      KadrTheme {
        ReaderScreen(
          state =
            ReaderScreenState(
              loading = false,
              bookTitle = "Тестовая книга",
              errorCode = errorCode,
              playback = playback,
              sections =
                listOf(
                  section("b0.s1", "Начало", 0),
                  section("b0.s2", "Финал", 1),
                ),
              noteBlocks = noteBlocks,
              showSentence = true,
            ),
          snackbarHostState = SnackbarHostState(),
          onBack = {},
          onPlay = {},
          onPause = onPause,
          onTogglePlay = {},
          onPreviousSentence = {},
          onNextSentence = {},
          onSpeedDecrease = {},
          onSpeedIncrease = {},
          onSpeedSet = {},
          onSectionSelected = {},
          onDismissHint = {},
          onSettings = {},
          onRestart = {},
          onRequestInfo = {},
          onDismissInfo = {},
          onRetryLeave = {},
          onDismissLeaveFailure = {},
          onLeaveWithoutSaving = {},
          onLeaveError = {},
        )
      }
    }
  }

  private fun playback(status: PlaybackStatus): ReaderPlaybackState =
    ReaderPlaybackState(
      status = status,
      item =
        ReaderItem(
          ordinal = 1,
          kind = IndexItemKind.WORD,
          text = "Чтение",
          visualKind = null,
          wordIndex = 0,
          startChar = 0,
          endChar = 7,
          sentenceId = 0,
          chapterKey = "b0.s1",
          chapterTitle = "Начало",
          topLevelChapterIndex = 0,
          noteIds = emptyList(),
        ),
      sentencePreview = "Первый пример.",
      speedWpm = 300,
      progressPercent = if (status == PlaybackStatus.COMPLETED) 100 else 50,
      chapterNumber = 1,
      chapterCount = 2,
      canShowContents = true,
      canPreviousSentence = false,
      canNextSentence = true,
      nextChapterTitle = "Финал",
      nextChapterNumber = 2,
      noteIds = emptyList(),
      restoreWasInexact = false,
      saveFailed = false,
    )

  private fun section(
    key: String,
    title: String,
    top: Int,
  ): ParsedSection =
    ParsedSection(
      chapterKey = key,
      parentKey = null,
      level = 0,
      title = title,
      firstWordIndex = top * 10,
      wordCount = 10,
      topLevelChapterIndex = top,
      isSynthetic = false,
    )

  private fun libraryBook(
    readingState: ReadingState,
    progress: Int,
  ): LibraryBook =
    LibraryBook(
      id = "book-id",
      title = "Тестовая книга",
      authorDisplay = "Анна Тестова",
      language = "ru",
      format = "FB2",
      coverFile = null,
      contentState = ContentState.READY,
      readingState = readingState,
      progressPercent = progress,
      currentChapterTitle = "Начало",
      wordCount = 100,
      chapterCount = 2,
      lastErrorCode = null,
      importedAt = 1,
      lastOpenedAt = 2,
    )
}
