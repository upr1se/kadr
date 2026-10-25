package com.upr1se.kadr.ui.reader

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.upr1se.kadr.data.db.ReadingState
import com.upr1se.kadr.data.settings.AppSettings
import com.upr1se.kadr.data.settings.SettingsRepository
import com.upr1se.kadr.data.storage.BookInfo
import com.upr1se.kadr.data.storage.BookRepository
import com.upr1se.kadr.data.storage.OpenedBook
import com.upr1se.kadr.data.storage.OpenBookResult
import com.upr1se.kadr.data.storage.SavedPosition
import com.upr1se.kadr.domain.reader.PlaybackStatus
import com.upr1se.kadr.domain.reader.ReaderCheckpoint
import com.upr1se.kadr.domain.reader.ReaderCheckpointSink
import com.upr1se.kadr.domain.reader.ReaderController
import com.upr1se.kadr.domain.reader.ReaderPlaybackState
import com.upr1se.kadr.domain.reader.ReaderReadingState
import com.upr1se.kadr.domain.reader.ReadingDocument
import com.upr1se.kadr.fb2.ParsedSection
import com.upr1se.kadr.fb2.Fb2Warning
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ReaderNotice {
  SAVE_FAILED,
  RESTORE_INEXACT,
  BOOK_INFO_FAILED,
}

sealed interface ReaderUiEvent {
  data object NavigateBack : ReaderUiEvent

  data object NavigateSettings : ReaderUiEvent

  data object NavigateLibraryForMissingBook : ReaderUiEvent

  data class Notice(val notice: ReaderNotice) : ReaderUiEvent
}

data class ReaderScreenState(
  val loading: Boolean = true,
  val showLoadingIndicator: Boolean = false,
  val bookTitle: String = "",
  val authorDisplay: String = "",
  val errorCode: String? = null,
  val playback: ReaderPlaybackState? = null,
  val sections: List<ParsedSection> = emptyList(),
  val noteBlocks: List<String> = emptyList(),
  val showSentence: Boolean = true,
  val wordSizeSp: Int = SettingsRepository.DEFAULT_WORD_SIZE_SP,
  val chapterAutoPause: Boolean = true,
  val firstHintVisible: Boolean = false,
  val saving: Boolean = false,
  val showSaveIndicator: Boolean = false,
  val leaveSaveFailed: Boolean = false,
  val bookInfo: BookInfo? = null,
  val loadingBookInfo: Boolean = false,
)

class ReaderViewModel(
  private val savedStateHandle: SavedStateHandle,
  private val repository: BookRepository,
  private val settingsRepository: SettingsRepository,
) : ViewModel() {
  private val bookId: String = checkNotNull(savedStateHandle["bookId"])
  private val _state = MutableStateFlow(ReaderScreenState())
  val state: StateFlow<ReaderScreenState> = _state.asStateFlow()
  private val eventChannel = Channel<ReaderUiEvent>(Channel.BUFFERED)
  val events = eventChannel.receiveAsFlow()
  private var openedBook: OpenedBook? = null
  private var document: ReadingDocument? = null
  private var controller: ReaderController? = null
  private var settings = AppSettings()
  private var settingsJob: Job? = null
  private var loadingIndicatorJob: Job? = null
  private var saveIndicatorJob: Job? = null
  private var saveFailureReported = false
  private var restoreWarningReported = false
  private var pendingNavigation: ReaderUiEvent = ReaderUiEvent.NavigateBack
  private var foreground = true

  init {
    loadingIndicatorJob =
      viewModelScope.launch {
        delay(300)
        if (_state.value.loading) {
          _state.update { it.copy(showLoadingIndicator = true) }
        }
      }
    viewModelScope.launch {
      val routeBook = repository.findBook(bookId)
      if (
        routeBook == null ||
          routeBook.contentState == com.upr1se.kadr.data.db.ContentState.IMPORTING ||
          routeBook.contentState == com.upr1se.kadr.data.db.ContentState.DELETING
      ) {
        _state.update { it.copy(loading = false, errorCode = "BOOK_NOT_FOUND") }
        eventChannel.send(ReaderUiEvent.NavigateLibraryForMissingBook)
        return@launch
      }
      settings = settingsRepository.settings.first()
      when (val opened = repository.openBook(bookId)) {
        is OpenBookResult.Success -> {
          runCatching { withContext(Dispatchers.Default) { initialize(opened.book) } }
            .onFailure {
              opened.book.close()
              loadingIndicatorJob?.cancel()
              _state.update {
                it.copy(
                  loading = false,
                  showLoadingIndicator = false,
                  errorCode = "BOOK_INTEGRITY",
                )
              }
            }
        }
        is OpenBookResult.Unavailable -> {
          loadingIndicatorJob?.cancel()
          _state.update {
            it.copy(
              loading = false,
              showLoadingIndicator = false,
              errorCode = opened.errorCode,
            )
          }
        }
      }
    }
    settingsJob =
      viewModelScope.launch {
        settingsRepository.settings.collect { latest ->
          settings = latest
          _state.update {
            it.copy(
              showSentence = latest.showSentenceOnPause,
              wordSizeSp = latest.wordSizeSp,
              chapterAutoPause = latest.chapterAutoPause,
            )
          }
          controller?.setChapterAutoPause(latest.chapterAutoPause)
        }
      }
  }

  private suspend fun initialize(book: OpenedBook) {
    openedBook = book
    val doc = ReadingDocument(book.readingText, book.index, book.sections, book.notes)
    document = doc
    val hintVisible = !settings.firstReaderHintShown
    if (hintVisible) {
      runCatching { settingsRepository.markFirstReaderHintShown() }
    }
    val readingState =
      when (book.entity.readingState) {
        ReadingState.UNREAD -> ReaderReadingState.UNREAD
        ReadingState.READING -> ReaderReadingState.READING
        ReadingState.COMPLETED -> ReaderReadingState.COMPLETED
      }
    val savedCheckpoint = readSavedCheckpoint()
    val restoredCheckpoint =
      savedCheckpoint?.takeIf { it.revision >= book.entity.positionRevision }
    val controller =
      ReaderController(
        document = doc,
        scope = CoroutineScope(viewModelScope.coroutineContext + Dispatchers.Default),
        checkpointSink = RepositoryCheckpointSink(),
        initialItemOrdinal = restoredCheckpoint?.itemOrdinal ?: book.entity.currentItemOrdinal,
        initialCurrentWordIndex =
          restoredCheckpoint?.wordIndex ?: book.entity.currentWordIndex,
        initialCharOffset =
          restoredCheckpoint?.charOffset ?: book.entity.currentCharOffset,
        initialChapterKey =
          restoredCheckpoint?.chapterKey ?: book.entity.currentChapterKey,
        initialFingerprint =
          restoredCheckpoint?.fingerprint ?: book.entity.positionFingerprint,
        initialRevision = restoredCheckpoint?.revision ?: book.entity.positionRevision,
        initialReadingState =
          restoredCheckpoint?.readingState ?: readingState,
        initialSpeedWpm = book.entity.lastSpeedWpm ?: settings.defaultSpeedWpm,
        chapterAutoPause = settings.chapterAutoPause,
      )
    this.controller = controller
    loadingIndicatorJob?.cancel()
    _state.update {
      it.copy(
        loading = false,
        showLoadingIndicator = false,
        bookTitle = book.metadata.title,
        authorDisplay = book.metadata.authorDisplay,
        sections = book.sections,
        showSentence = settings.showSentenceOnPause,
        wordSizeSp = settings.wordSizeSp,
        chapterAutoPause = settings.chapterAutoPause,
        firstHintVisible = hintVisible,
      )
    }
    controller.state.collect { playback ->
      _state.update {
        it.copy(
          playback = playback,
          noteBlocks = document?.noteBlocks(playback.noteIds).orEmpty(),
        )
      }
      if (playback.restoreWasInexact && !restoreWarningReported) {
        restoreWarningReported = true
        eventChannel.send(ReaderUiEvent.Notice(ReaderNotice.RESTORE_INEXACT))
      }
      if (playback.saveFailed && !saveFailureReported) {
        saveFailureReported = true
        eventChannel.send(ReaderUiEvent.Notice(ReaderNotice.SAVE_FAILED))
      } else if (!playback.saveFailed) {
        saveFailureReported = false
      }
    }
  }

  fun play() {
    if (!foreground) return
    _state.update { it.copy(firstHintVisible = false) }
    viewModelScope.launch(Dispatchers.Default) { controller?.play() }
  }

  fun pause() {
    viewModelScope.launch(Dispatchers.Default) { controller?.pause() }
  }

  fun togglePlay() {
    val playback = _state.value.playback ?: return
    if (playback.status == PlaybackStatus.PLAYING) pause() else play()
  }

  fun previousSentence() {
    viewModelScope.launch(Dispatchers.Default) { controller?.previousSentence() }
  }

  fun nextSentence() {
    viewModelScope.launch(Dispatchers.Default) { controller?.nextSentence() }
  }

  fun adjustSpeed(delta: Int) {
    viewModelScope.launch(Dispatchers.Default) { controller?.adjustSpeed(delta) }
  }

  fun setSpeed(value: Int) {
    viewModelScope.launch(Dispatchers.Default) { controller?.setSpeed(value) }
  }

  fun openSection(chapterKey: String) {
    viewModelScope.launch(Dispatchers.Default) { controller?.openSection(chapterKey) }
  }

  fun dismissHint() {
    _state.update { it.copy(firstHintVisible = false) }
  }

  fun onBackground() {
    controller?.currentCheckpoint()?.let(::writeSavedCheckpoint)
    viewModelScope.launch(Dispatchers.Default) {
      controller?.onBackground()
    }
  }

  fun setForeground(active: Boolean) {
    foreground = active
    if (!active) onBackground()
  }

  fun requestLeave() {
    saveThenNavigate(ReaderUiEvent.NavigateBack)
  }

  fun requestSettings() {
    saveThenNavigate(ReaderUiEvent.NavigateSettings)
  }

  fun retryLeave() {
    _state.update { it.copy(leaveSaveFailed = false) }
    saveThenNavigate(pendingNavigation)
  }

  fun dismissLeaveFailure() {
    _state.update { it.copy(leaveSaveFailed = false) }
  }

  fun leaveWithoutSaving() {
    _state.update { it.copy(leaveSaveFailed = false, saving = false, showSaveIndicator = false) }
    viewModelScope.launch { eventChannel.send(pendingNavigation) }
  }

  fun requestBookInfo() {
    if (_state.value.loadingBookInfo) return
    viewModelScope.launch {
      _state.update { it.copy(loadingBookInfo = true) }
      val info = repository.loadBookInfo(bookId)
      _state.update { it.copy(loadingBookInfo = false, bookInfo = info) }
      if (info == null) eventChannel.send(ReaderUiEvent.Notice(ReaderNotice.BOOK_INFO_FAILED))
    }
  }

  fun dismissBookInfo() {
    _state.update { it.copy(bookInfo = null) }
  }

  fun restart() {
    viewModelScope.launch(Dispatchers.Default) { controller?.restart() }
  }

  private fun saveThenNavigate(event: ReaderUiEvent) {
    pendingNavigation = event
    val activeController = controller ?: run {
      viewModelScope.launch { eventChannel.send(event) }
      return
    }
    viewModelScope.launch(Dispatchers.Default) {
      _state.update { it.copy(saving = true, showSaveIndicator = false) }
      saveIndicatorJob?.cancel()
      saveIndicatorJob =
        launch {
          delay(300)
          if (_state.value.saving) _state.update { it.copy(showSaveIndicator = true) }
        }
      val saved = activeController.pauseForLeave()
      saveIndicatorJob?.cancel()
      saveIndicatorJob = null
      _state.update {
        it.copy(
          saving = false,
          showSaveIndicator = false,
          leaveSaveFailed = !saved,
        )
      }
      if (saved) eventChannel.send(event)
    }
  }

  fun openLibraryFromError() {
    viewModelScope.launch { eventChannel.send(ReaderUiEvent.NavigateBack) }
  }

  override fun onCleared() {
    saveIndicatorJob?.cancel()
    loadingIndicatorJob?.cancel()
    settingsJob?.cancel()
    controller?.close()
    openedBook?.close()
  }

  private inner class RepositoryCheckpointSink : ReaderCheckpointSink {
    override suspend fun markStarted() {
      repository.markStarted(bookId)
    }

    override suspend fun saveSpeed(wpm: Int) {
      repository.saveBookSpeed(bookId, wpm)
    }

    override suspend fun saveCheckpoint(checkpoint: ReaderCheckpoint): Boolean {
      writeSavedCheckpoint(checkpoint)
      val book = openedBook ?: return false
      return repository.savePosition(
        book,
        SavedPosition(
          itemOrdinal = checkpoint.itemOrdinal,
          charOffset = checkpoint.charOffset,
          wordIndex = checkpoint.wordIndex,
          sentenceId = checkpoint.sentenceId,
          chapterKey = checkpoint.chapterKey,
          chapterTitle = checkpoint.chapterTitle,
          fingerprint = checkpoint.fingerprint,
          revision = checkpoint.revision,
          readingState =
            when (checkpoint.readingState) {
              ReaderReadingState.UNREAD -> ReadingState.UNREAD
              ReaderReadingState.READING -> ReadingState.READING
              ReaderReadingState.COMPLETED -> ReadingState.COMPLETED
            },
        ),
      )
    }
  }

  private fun writeSavedCheckpoint(checkpoint: ReaderCheckpoint) {
    savedStateHandle[SAVED_ITEM_ORDINAL] = checkpoint.itemOrdinal
    savedStateHandle[SAVED_CHAR_OFFSET] = checkpoint.charOffset
    savedStateHandle[SAVED_WORD_INDEX] = checkpoint.wordIndex
    putOptional(SAVED_SENTENCE_ID, checkpoint.sentenceId)
    putOptional(SAVED_CHAPTER_KEY, checkpoint.chapterKey)
    putOptional(SAVED_CHAPTER_TITLE, checkpoint.chapterTitle)
    putOptional(SAVED_FINGERPRINT, checkpoint.fingerprint)
    savedStateHandle[SAVED_REVISION] = checkpoint.revision
    savedStateHandle[SAVED_READING_STATE] = checkpoint.readingState.name
  }

  private fun readSavedCheckpoint(): ReaderCheckpoint? {
    val itemOrdinal = savedStateHandle.get<Int>(SAVED_ITEM_ORDINAL) ?: return null
    val revision = savedStateHandle.get<Long>(SAVED_REVISION) ?: return null
    val readingState =
      savedStateHandle.get<String>(SAVED_READING_STATE)
        ?.let { runCatching { ReaderReadingState.valueOf(it) }.getOrNull() }
        ?: return null
    return ReaderCheckpoint(
      itemOrdinal = itemOrdinal,
      charOffset = savedStateHandle.get<Int>(SAVED_CHAR_OFFSET) ?: return null,
      wordIndex = savedStateHandle.get<Int>(SAVED_WORD_INDEX) ?: return null,
      sentenceId = savedStateHandle[SAVED_SENTENCE_ID],
      chapterKey = savedStateHandle[SAVED_CHAPTER_KEY],
      chapterTitle = savedStateHandle[SAVED_CHAPTER_TITLE],
      fingerprint = savedStateHandle[SAVED_FINGERPRINT],
      revision = revision,
      readingState = readingState,
    )
  }

  private fun <T> putOptional(key: String, value: T?) {
    if (value == null) {
      savedStateHandle.remove<T>(key)
    } else {
      savedStateHandle[key] = value
    }
  }

  private companion object {
    const val SAVED_ITEM_ORDINAL = "reader_item_ordinal"
    const val SAVED_CHAR_OFFSET = "reader_char_offset"
    const val SAVED_WORD_INDEX = "reader_word_index"
    const val SAVED_SENTENCE_ID = "reader_sentence_id"
    const val SAVED_CHAPTER_KEY = "reader_chapter_key"
    const val SAVED_CHAPTER_TITLE = "reader_chapter_title"
    const val SAVED_FINGERPRINT = "reader_fingerprint"
    const val SAVED_REVISION = "reader_revision"
    const val SAVED_READING_STATE = "reader_reading_state"
  }
}
