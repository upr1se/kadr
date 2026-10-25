package com.upr1se.kadr.domain.reader

import android.os.SystemClock
import com.upr1se.kadr.data.storage.IndexItemKind
import com.upr1se.kadr.fb2.BoundaryKind
import com.upr1se.kadr.fb2.UnicodeText
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class PlaybackStatus {
  PAUSED,
  PLAYING,
  CHAPTER_BREAK,
  COMPLETED,
}

enum class ReaderReadingState {
  UNREAD,
  READING,
  COMPLETED,
}

data class ReaderPlaybackState(
  val status: PlaybackStatus,
  val item: ReaderItem,
  val sentencePreview: String,
  val speedWpm: Int,
  val progressPercent: Int,
  val chapterNumber: Int?,
  val chapterCount: Int,
  val canShowContents: Boolean,
  val canPreviousSentence: Boolean,
  val canNextSentence: Boolean,
  val nextChapterTitle: String?,
  val nextChapterNumber: Int?,
  val noteIds: List<String>,
  val restoreWasInexact: Boolean,
  val saveFailed: Boolean,
)

data class ReaderCheckpoint(
  val itemOrdinal: Int,
  val charOffset: Int,
  val wordIndex: Int,
  val sentenceId: Int?,
  val chapterKey: String?,
  val chapterTitle: String?,
  val fingerprint: String?,
  val revision: Long,
  val readingState: ReaderReadingState,
)

interface ReaderCheckpointSink {
  suspend fun markStarted()
  suspend fun saveSpeed(wpm: Int)
  suspend fun saveCheckpoint(checkpoint: ReaderCheckpoint): Boolean
}

fun interface MonotonicClock {
  fun nowMs(): Long
}

class AndroidMonotonicClock : MonotonicClock {
  override fun nowMs(): Long = SystemClock.elapsedRealtime()
}

data class DwellDurations(
  val baseMs: Int,
  val resumeMs: Int,
  val visualMs: Int,
  val finalWordMs: Int,
)

object ReaderDurations {
  const val MIN_SPEED_WPM = 100
  const val MAX_SPEED_WPM = 800
  const val SPEED_STEP_WPM = 25
  const val DEFAULT_SPEED_WPM = 300
  const val LONG_WORD_MIN_GRAPHEMES = 40
  const val LONG_WORD_MIN_MS = 600
  const val LARGE_CHAPTER_MIN_WORDS = 600

  fun normalizeSpeed(wpm: Int): Int {
    val clamped = wpm.coerceIn(MIN_SPEED_WPM, MAX_SPEED_WPM)
    return ((clamped / SPEED_STEP_WPM.toDouble()).roundToInt() * SPEED_STEP_WPM)
      .coerceIn(MIN_SPEED_WPM, MAX_SPEED_WPM)
  }

  fun baseMs(wpm: Int): Int =
    (60_000.0 / normalizeSpeed(wpm)).roundToInt().coerceAtLeast(1)

  fun wordDwell(
    wpm: Int,
    boundary: BoundaryKind,
    text: String,
    lastWord: Boolean,
  ): Int {
    val base = baseMs(wpm)
    val boundaryMs =
      when (boundary) {
        BoundaryKind.CLAUSE -> max(base * 0.20, 25.0).roundToInt()
        BoundaryKind.SENTENCE -> max(base * 0.60, 60.0).roundToInt()
        BoundaryKind.VERSE_LINE -> max(base * 0.40, 40.0).roundToInt()
        BoundaryKind.VERSE_STANZA,
        BoundaryKind.PARAGRAPH -> max(base.toDouble(), 100.0).roundToInt()
        BoundaryKind.TABLE_ROW -> max(base * 0.60, 60.0).roundToInt()
        BoundaryKind.NONE -> 0
      }
    var total = base + boundaryMs
    if (UnicodeText.graphemes(text).size > LONG_WORD_MIN_GRAPHEMES) {
      total = max(total, LONG_WORD_MIN_MS)
    }
    if (lastWord) {
      total = max(total, max(400.0, base * 1.5).roundToInt())
    }
    return total.coerceAtLeast(1)
  }

  fun visualDwell(wpm: Int): Int =
    max(1_200, baseMs(wpm) * 2)

  fun resumeDwell(wpm: Int, ownWordDwell: Int): Int =
    max(max(400, baseMs(wpm)), ownWordDwell)
}

class ReaderController(
  val document: ReadingDocument,
  private val scope: CoroutineScope,
  private val checkpointSink: ReaderCheckpointSink,
  private val clock: MonotonicClock = AndroidMonotonicClock(),
  initialItemOrdinal: Int,
  initialCurrentWordIndex: Int,
  initialCharOffset: Int,
  initialChapterKey: String?,
  initialFingerprint: String?,
  initialRevision: Long,
  initialReadingState: ReaderReadingState,
  initialSpeedWpm: Int,
  private var chapterAutoPause: Boolean,
) : AutoCloseable {
  private val mutex = Mutex()
  private val restored =
    document.restorePosition(
      itemOrdinal = initialItemOrdinal,
      currentWordIndex = initialCurrentWordIndex,
      currentCharOffset = initialCharOffset,
      chapterKey = initialChapterKey,
      fingerprint = initialFingerprint,
    )
  private var currentItem =
    if (initialReadingState == ReaderReadingState.COMPLETED) {
      document.item(document.lastWordItem())
    } else {
      document.item(restored.itemOrdinal)
    }
  private var lastShownWordIndex =
    if (initialReadingState == ReaderReadingState.COMPLETED) {
      document.wordCount - 1
    } else {
      currentItem.wordIndex?.takeIf { currentItem.kind == IndexItemKind.WORD }
        ?: initialCurrentWordIndex.coerceIn(-1, document.wordCount - 1)
    }
  private var readingState =
    if (initialReadingState == ReaderReadingState.COMPLETED) {
      ReaderReadingState.COMPLETED
    } else {
      initialReadingState
    }
  private var revision = initialRevision
  private var speed = ReaderDurations.normalizeSpeed(initialSpeedWpm)
  private var pendingChapterBreak: PendingChapterBreak? = null
  private var playbackJob: Job? = null
  private var generation = 0L
  private var lastSavedAtMs = clock.nowMs()
  private var saveFailed = false
  private val _state =
    MutableStateFlow(
      buildState(
        status =
          if (initialReadingState == ReaderReadingState.COMPLETED) {
            PlaybackStatus.COMPLETED
          } else {
            PlaybackStatus.PAUSED
          },
        restoreInexact = !restored.exact,
      ),
    )
  val state: StateFlow<ReaderPlaybackState> = _state.asStateFlow()
  @Volatile
  private var latestCheckpoint: ReaderCheckpoint = buildCheckpoint()

  suspend fun play() {
    mutex.withLock {
      if (_state.value.status == PlaybackStatus.PLAYING ||
        _state.value.status == PlaybackStatus.COMPLETED
      ) {
        return
      }
      if (readingState == ReaderReadingState.UNREAD) {
        try {
          checkpointSink.markStarted()
          readingState = ReaderReadingState.READING
          latestCheckpoint = buildCheckpoint()
        } catch (_: Exception) {
          updateSaveFailure(true)
        }
      }
      try {
        checkpointSink.saveSpeed(speed)
      } catch (_: Exception) {
        updateSaveFailure(true)
      }
      val wasChapterBreak = _state.value.status == PlaybackStatus.CHAPTER_BREAK
      if (!wasChapterBreak) {
        chapterBreakFor(currentItem)?.let { nextBreak ->
          pendingChapterBreak = nextBreak
          persistCheckpoint()
          _state.value =
            buildState(PlaybackStatus.CHAPTER_BREAK, restoreInexact = false)
          return
        }
      }
      pendingChapterBreak?.let {
        currentItem = document.item(it.firstWordItem)
        lastShownWordIndex = currentItem.wordIndex ?: lastShownWordIndex
        pendingChapterBreak = null
      }
      revision++
      latestCheckpoint = buildCheckpoint()
      if (wasChapterBreak) persistCheckpoint()
      _state.value = buildState(PlaybackStatus.PLAYING, restoreInexact = false)
      startTimer(resume = true)
    }
  }

  suspend fun pause(): Boolean =
    mutex.withLock {
      if (_state.value.status == PlaybackStatus.COMPLETED) {
        return@withLock if (saveFailed) persistCheckpoint() else true
      }
      stopTimer()
      val saved = persistCheckpoint()
      _state.value =
        buildState(PlaybackStatus.PAUSED, restoreInexact = _state.value.restoreWasInexact)
      saved
    }

  suspend fun onBackground(): Boolean =
    mutex.withLock {
      if (_state.value.status == PlaybackStatus.CHAPTER_BREAK) {
        return@withLock persistCheckpoint()
      }
      if (_state.value.status == PlaybackStatus.PLAYING ||
        _state.value.status == PlaybackStatus.PAUSED
      ) {
        stopTimer()
        revision++
        val saved = persistCheckpoint()
        _state.value =
          buildState(PlaybackStatus.PAUSED, restoreInexact = _state.value.restoreWasInexact)
        saved
      } else {
        true
      }
    }

  suspend fun previousSentence(): Boolean =
    mutex.withLock {
      if (_state.value.status == PlaybackStatus.COMPLETED ||
        _state.value.status == PlaybackStatus.CHAPTER_BREAK
      ) {
        return@withLock false
      }
      val target = document.previousSentenceItem(currentItem) ?: return@withLock false
      stopTimer()
      moveToItem(target)
      pendingChapterBreak = null
      _state.value = buildState(PlaybackStatus.PAUSED, restoreInexact = _state.value.restoreWasInexact)
      persistCheckpoint()
    }

  suspend fun nextSentence(): Boolean =
    mutex.withLock {
      if (_state.value.status == PlaybackStatus.COMPLETED ||
        _state.value.status == PlaybackStatus.CHAPTER_BREAK
      ) {
        return@withLock false
      }
      val target = document.nextSentenceItem(currentItem) ?: return@withLock false
      stopTimer()
      moveToItem(target)
      pendingChapterBreak = null
      _state.value = buildState(PlaybackStatus.PAUSED, restoreInexact = _state.value.restoreWasInexact)
      persistCheckpoint()
    }

  suspend fun openSection(chapterKey: String): Boolean =
    mutex.withLock {
      val section = document.sections.firstOrNull { it.chapterKey == chapterKey }
        ?: return@withLock false
      val item = document.sectionFirstItem(section) ?: return@withLock false
      stopTimer()
      moveToItem(item)
      pendingChapterBreak = null
      _state.value = buildState(PlaybackStatus.PAUSED, restoreInexact = _state.value.restoreWasInexact)
      persistCheckpoint()
    }

  suspend fun setSpeed(wpm: Int) {
    mutex.withLock {
      speed = ReaderDurations.normalizeSpeed(wpm)
      _state.value = buildState(_state.value.status, restoreInexact = _state.value.restoreWasInexact)
      if (_state.value.status != PlaybackStatus.PLAYING) {
        try {
          checkpointSink.saveSpeed(speed)
        } catch (_: Exception) {
          updateSaveFailure(true)
        }
      }
    }
  }

  suspend fun adjustSpeed(deltaWpm: Int) {
    setSpeed(speed + deltaWpm)
  }

  suspend fun setChapterAutoPause(enabled: Boolean) {
    mutex.withLock { chapterAutoPause = enabled }
  }

  suspend fun restart(): Boolean =
    mutex.withLock {
      stopTimer()
      pendingChapterBreak = null
      currentItem = document.item(document.firstWordItem())
      lastShownWordIndex = 0
      readingState = ReaderReadingState.UNREAD
      revision++
      _state.value = buildState(PlaybackStatus.PAUSED, restoreInexact = false)
      persistCheckpoint()
    }

  suspend fun pauseForLeave(): Boolean = pause()

  fun checkpoint(): ReaderCheckpoint = buildCheckpoint()

  fun currentCheckpoint(): ReaderCheckpoint = latestCheckpoint

  override fun close() {
    stopTimer()
  }

  private suspend fun startTimer(resume: Boolean) {
    stopTimer()
    val expectedGeneration = ++generation
    var dwell = currentDwell(resume)
    playbackJob =
      scope.launch {
        try {
          while (true) {
            currentCoroutineContext().ensureActive()
            delay(dwell.toLong())
            val nextDwell =
              mutex.withLock {
                if (generation != expectedGeneration ||
                  _state.value.status != PlaybackStatus.PLAYING
                ) {
                  return@withLock null
                }
                advanceTimerTick()
              } ?: break
            dwell = nextDwell
          }
        } catch (_: CancellationException) {
          // Pause, navigation and a new session intentionally cancel this job.
        }
      }
  }

  private fun stopTimer() {
    generation++
    playbackJob?.cancel()
    playbackJob = null
  }

  private suspend fun advanceTimerTick(): Int? {
    val item = currentItem
    if (item.kind == IndexItemKind.WORD && item.wordIndex == document.wordCount - 1) {
      readingState = ReaderReadingState.COMPLETED
      revision++
      latestCheckpoint = buildCheckpoint()
      _state.value =
        buildState(PlaybackStatus.COMPLETED, restoreInexact = _state.value.restoreWasInexact)
      persistCheckpoint()
      stopTimer()
      return null
    }

    val chapterBreak = chapterBreakFor(item)
    if (chapterBreak != null) {
      pendingChapterBreak = chapterBreak
      _state.value =
        buildState(PlaybackStatus.CHAPTER_BREAK, restoreInexact = _state.value.restoreWasInexact)
      stopTimer()
      return null
    }

    if (item.ordinal + 1 >= document.itemCount) {
      readingState = ReaderReadingState.COMPLETED
      revision++
      latestCheckpoint = buildCheckpoint()
      _state.value =
        buildState(PlaybackStatus.COMPLETED, restoreInexact = _state.value.restoreWasInexact)
      persistCheckpoint()
      stopTimer()
      return null
    }

    if (readingState == ReaderReadingState.UNREAD) readingState = ReaderReadingState.READING
    moveToItem(item.ordinal + 1)
    _state.value =
      buildState(PlaybackStatus.PLAYING, restoreInexact = _state.value.restoreWasInexact)
    maybePersistDuringPlayback()
    return currentDwell(resume = false)
  }

  private fun moveToItem(itemOrdinal: Int) {
    currentItem = document.item(itemOrdinal)
    if (currentItem.kind == IndexItemKind.WORD) {
      currentItem.wordIndex?.let { lastShownWordIndex = it }
    }
    revision++
    latestCheckpoint = buildCheckpoint()
  }

  private suspend fun maybePersistDuringPlayback() {
    val now = clock.nowMs()
    val currentWord = lastShownWordIndex.takeIf { it >= 0 }?.let(document::word)
    val sentenceBoundary = currentWord?.endsSentence == true
    if (sentenceBoundary || now - lastSavedAtMs >= 1_000) {
      persistCheckpoint()
      lastSavedAtMs = now
    }
  }

  private suspend fun persistCheckpoint(): Boolean =
    try {
      latestCheckpoint = buildCheckpoint()
      val saved = checkpointSink.saveCheckpoint(latestCheckpoint)
      updateSaveFailure(!saved)
      saved
    } catch (_: Exception) {
      updateSaveFailure(true)
      false
    }

  private fun updateSaveFailure(failed: Boolean) {
    saveFailed = failed
    if (_state.value.saveFailed != failed) {
      _state.value = _state.value.copy(saveFailed = failed)
    }
  }

  private fun buildCheckpoint(): ReaderCheckpoint {
    val wordIndex = lastShownWordIndex
    val wordRecord = wordIndex.takeIf { it >= 0 }?.let(document::word)
    val activeWord =
      currentItem.kind == IndexItemKind.WORD && currentItem.wordIndex == wordIndex
    return ReaderCheckpoint(
      itemOrdinal = currentItem.ordinal,
      charOffset =
        currentItem.startChar
          ?: wordRecord?.startChar
          ?: document.text.length,
      wordIndex = wordIndex,
      sentenceId = wordRecord?.sentenceId,
      chapterKey = currentItem.chapterKey,
      chapterTitle = currentItem.chapterTitle,
      fingerprint =
        wordIndex.takeIf { it >= 0 }?.let(document::fingerprintForWord)
          ?.takeIf { activeWord || currentItem.kind != IndexItemKind.WORD },
      revision = revision,
      readingState = readingState,
    )
  }

  private fun currentDwell(resume: Boolean): Int {
    val item = currentItem
    if (item.kind != IndexItemKind.WORD) {
      return ReaderDurations.visualDwell(speed)
    }
    val wordIndex = item.wordIndex ?: return ReaderDurations.visualDwell(speed)
    val record = document.word(wordIndex)
    val own =
      ReaderDurations.wordDwell(
        wpm = speed,
        boundary = record.boundary,
        text = item.text,
        lastWord = wordIndex == document.wordCount - 1,
      )
    return if (resume) ReaderDurations.resumeDwell(speed, own) else own
  }

  private fun chapterBreakFor(item: ReaderItem): PendingChapterBreak? {
    if (!chapterAutoPause || item.kind != IndexItemKind.WORD) return null
    val nextChapter = document.nextTopLevelChapter(item) ?: return null
    if (!document.nextWordStartsDifferentTopLevelChapter(item)) return null
    val topSection = document.topLevelSectionFor(item) ?: return null
    if (topSection.wordCount < ReaderDurations.LARGE_CHAPTER_MIN_WORDS) return null
    return PendingChapterBreak(
      title = nextChapter.first.title,
      chapterNumber = (nextChapter.first.topLevelChapterIndex ?: 0) + 1,
      firstWordItem = nextChapter.second,
    )
  }

  private fun buildState(
    status: PlaybackStatus,
    restoreInexact: Boolean,
  ): ReaderPlaybackState {
    val topSection = document.topLevelSectionFor(currentItem)
    val sentence =
      lastShownWordIndex.takeIf { it >= 0 }?.let { document.sentencePreview(it) }.orEmpty()
    val progress =
      when {
        readingState == ReaderReadingState.COMPLETED -> 100
        readingState == ReaderReadingState.UNREAD || lastShownWordIndex < 0 -> 0
        else ->
          ((100L * (lastShownWordIndex + 1)) / document.wordCount)
            .toInt()
            .coerceIn(1, 99)
      }
    return ReaderPlaybackState(
      status = status,
      item = currentItem,
      sentencePreview = sentence,
      speedWpm = speed,
      progressPercent = progress,
      chapterNumber = topSection?.topLevelChapterIndex?.plus(1),
      chapterCount = document.chapterCount,
      canShowContents = document.realTopLevelSections().isNotEmpty(),
      canPreviousSentence = document.canGoToPreviousSentence(currentItem),
      canNextSentence = document.canGoToNextSentence(currentItem),
      nextChapterTitle = pendingChapterBreak?.title,
      nextChapterNumber = pendingChapterBreak?.chapterNumber,
      noteIds = currentItem.noteIds,
      restoreWasInexact = restoreInexact,
      saveFailed = saveFailed,
    )
  }

  private data class PendingChapterBreak(
    val title: String,
    val chapterNumber: Int,
    val firstWordItem: Int,
  )
}
