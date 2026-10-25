package com.upr1se.kadr.ui.library

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.upr1se.kadr.app.IntentImportError
import com.upr1se.kadr.app.IntentImportQueue
import com.upr1se.kadr.data.db.ReadingState
import com.upr1se.kadr.data.storage.BookInfo
import com.upr1se.kadr.data.storage.BookRepository
import com.upr1se.kadr.data.storage.ContentUriImportSource
import com.upr1se.kadr.data.storage.DuplicateDecision
import com.upr1se.kadr.data.storage.ImportCoordinator
import com.upr1se.kadr.data.storage.ImportProgress
import com.upr1se.kadr.data.storage.ImportResult
import com.upr1se.kadr.data.storage.ImportStage
import com.upr1se.kadr.data.storage.LibraryBook
import com.upr1se.kadr.data.storage.StorageErrorCode
import com.upr1se.kadr.data.storage.StorageException
import com.upr1se.kadr.fb2.Fb2ImportException
import com.upr1se.kadr.fb2.Fb2SelectionRequiredException
import com.upr1se.kadr.fb2.ImportErrorCode
import com.upr1se.kadr.fb2.ZipBookCandidate
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

enum class LibraryNoticeKind {
  BOOK_ADDED,
  BOOK_DELETED,
  PROGRESS_RESET,
  ONE_AT_A_TIME,
  CURRENT_IMPORT_RUNNING,
  IMPORT_SOURCE_UNAVAILABLE,
  IMPORT_FILE_TOO_LARGE,
  IMPORT_NOT_FB2,
  IMPORT_BAD_XML,
  IMPORT_BAD_ZIP,
  IMPORT_ZIP_ENCRYPTED,
  IMPORT_ZIP_WITHOUT_BOOK,
  IMPORT_ENCODING,
  IMPORT_NO_TEXT,
  IMPORT_NOT_ENOUGH_SPACE,
  IMPORT_FAILED,
  BOOK_OPEN_FAILED,
}

sealed interface LibraryEvent {
  data class OpenReader(val bookId: String) : LibraryEvent

  data class Notice(
    val kind: LibraryNoticeKind,
    val openBookId: String? = null,
  ) : LibraryEvent
}

sealed interface ImportUiState {
  data object Idle : ImportUiState

  data class Working(
    val progress: ImportProgress,
    val indicatorVisible: Boolean,
    val canCancel: Boolean,
  ) : ImportUiState

  data class ZipSelection(
    val candidates: List<ZipBookCandidate>,
  ) : ImportUiState

  data class Duplicate(
    val existingTitle: String,
    val exactSourceMatch: Boolean,
  ) : ImportUiState
}

sealed interface LibraryDialog {
  data class Delete(val book: LibraryBook) : LibraryDialog

  data class Reset(val book: LibraryBook) : LibraryDialog

  data class Info(val info: BookInfo) : LibraryDialog
}

data class LibraryScreenState(
  val isLoading: Boolean = true,
  val books: List<LibraryBook> = emptyList(),
  val continueReading: LibraryBook? = null,
  val covers: Map<String, Bitmap> = emptyMap(),
  val importState: ImportUiState = ImportUiState.Idle,
  val dialog: LibraryDialog? = null,
)

@SuppressLint("StaticFieldLeak")
class LibraryViewModel(
  private val context: Context,
  private val repository: BookRepository,
  private val importCoordinator: ImportCoordinator,
  private val intentImportQueue: IntentImportQueue,
) : ViewModel() {
  private val books = MutableStateFlow<List<LibraryBook>?>(null)
  private val covers = MutableStateFlow<Map<String, Bitmap>>(emptyMap())
  private val importState = MutableStateFlow<ImportUiState>(ImportUiState.Idle)
  private val dialog = MutableStateFlow<LibraryDialog?>(null)
  private val _state = MutableStateFlow(LibraryScreenState())
  val state: StateFlow<LibraryScreenState> = _state.asStateFlow()
  private val eventChannel = Channel<LibraryEvent>(Channel.BUFFERED)
  val events = eventChannel.receiveAsFlow()
  private val coverJobs = mutableMapOf<String, Job>()
  private var importJob: Job? = null
  private var indicatorJob: Job? = null
  private var zipChoice: CompletableDeferred<ZipBookCandidate?>? = null
  private var duplicateChoice: CompletableDeferred<DuplicateDecision>? = null
  private var lastProgress: ImportProgress = ImportProgress(ImportStage.COPYING)
  private var importStartedAt = 0L
  private var libraryWasEmpty = false
  private val generation = AtomicLong(0)

  init {
    viewModelScope.launch {
      repository.observeLibrary().collect { latest ->
        books.value = latest
        updateCovers(latest)
        publishState()
      }
    }
    viewModelScope.launch {
      intentImportQueue.requests.collect { request ->
        if (request == null) return@collect
        intentImportQueue.consume(request.id)
        when (request.error) {
          IntentImportError.NO_STREAM,
          IntentImportError.MULTIPLE_STREAMS ->
            sendNotice(LibraryNoticeKind.ONE_AT_A_TIME)
          null -> request.uri?.let(::startImport)
        }
      }
    }
    viewModelScope.launch {
      importState.collect { publishState() }
    }
    viewModelScope.launch {
      dialog.collect { publishState() }
    }
  }

  fun startImport(uri: Uri) {
    if (importJob?.isActive == true) {
      sendNotice(LibraryNoticeKind.CURRENT_IMPORT_RUNNING)
      return
    }
    libraryWasEmpty = books.value.orEmpty().isEmpty()
    val jobGeneration = generation.incrementAndGet()
    lastProgress = ImportProgress(ImportStage.COPYING)
    importStartedAt = android.os.SystemClock.elapsedRealtime()
    importState.value =
      ImportUiState.Working(
        progress = lastProgress,
        indicatorVisible = false,
        canCancel = true,
      )
    indicatorJob?.cancel()
    indicatorJob =
      viewModelScope.launch {
        delay(300)
        val current = importState.value
        if (current is ImportUiState.Working && generation.get() == jobGeneration) {
          importState.value = current.copy(indicatorVisible = true)
        }
      }
    importJob =
      viewModelScope.launch {
        try {
          val result =
            importCoordinator.import(
              source = ContentUriImportSource(context, uri),
              interaction = Interaction(jobGeneration),
              progressListener = { progress ->
                lastProgress = progress
                if (generation.get() == jobGeneration) {
                  val current = importState.value
                  if (current is ImportUiState.Working) {
                    importState.value =
                      current.copy(
                        progress = progress,
                        canCancel =
                          progress.stage != ImportStage.SAVING &&
                            progress.stage != ImportStage.COMPLETE,
                      )
                  }
                }
              },
            )
          when (result) {
            is ImportResult.Imported -> {
              if (libraryWasEmpty) {
                eventChannel.send(LibraryEvent.OpenReader(result.bookId))
              } else {
                eventChannel.send(
                  LibraryEvent.Notice(LibraryNoticeKind.BOOK_ADDED, result.bookId),
                )
              }
            }
            is ImportResult.OpenedExisting ->
              eventChannel.send(LibraryEvent.OpenReader(result.bookId))
            ImportResult.Cancelled -> Unit
          }
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (exception: Fb2ImportException) {
          sendNotice(noticeFor(exception.code))
        } catch (exception: Fb2SelectionRequiredException) {
          sendNotice(LibraryNoticeKind.CURRENT_IMPORT_RUNNING)
        } catch (exception: StorageException) {
          sendNotice(noticeFor(exception.code))
        } catch (_: Exception) {
          sendNotice(LibraryNoticeKind.IMPORT_FAILED)
        } finally {
          if (generation.get() == jobGeneration) {
            importState.value = ImportUiState.Idle
            zipChoice = null
            duplicateChoice = null
          }
        }
      }
  }

  fun selectZipBook(candidate: ZipBookCandidate?) {
    val deferred = zipChoice ?: return
    zipChoice = null
    importState.value =
      ImportUiState.Working(lastProgress, indicatorVisible = true, canCancel = true)
    deferred.complete(candidate)
  }

  fun chooseDuplicate(decision: DuplicateDecision) {
    val deferred = duplicateChoice ?: return
    duplicateChoice = null
    importState.value =
      ImportUiState.Working(lastProgress, indicatorVisible = true, canCancel = true)
    deferred.complete(decision)
  }

  fun cancelImport() {
    zipChoice?.complete(null)
    zipChoice = null
    duplicateChoice?.complete(DuplicateDecision.CANCEL)
    duplicateChoice = null
    importJob?.cancel()
    indicatorJob?.cancel()
    generation.incrementAndGet()
    importState.value = ImportUiState.Idle
  }

  fun openBook(bookId: String) {
    viewModelScope.launch { eventChannel.send(LibraryEvent.OpenReader(bookId)) }
  }

  fun requestDelete(book: LibraryBook) {
    viewModelScope.launch { dialog.emit(LibraryDialog.Delete(book)) }
  }

  fun requestReset(book: LibraryBook) {
    viewModelScope.launch { dialog.emit(LibraryDialog.Reset(book)) }
  }

  fun requestInfo(bookId: String) {
    viewModelScope.launch {
      val info = repository.loadBookInfo(bookId)
      if (info == null) {
        sendNotice(LibraryNoticeKind.BOOK_OPEN_FAILED)
      } else {
        dialog.value = LibraryDialog.Info(info)
      }
    }
  }

  fun dismissDialog() {
    dialog.value = null
  }

  fun confirmDelete() {
    val active = dialog.value as? LibraryDialog.Delete ?: return
    dialog.value = null
    viewModelScope.launch {
      try {
        if (importCoordinator.deleteBook(active.book.id)) {
          sendNotice(LibraryNoticeKind.BOOK_DELETED)
        }
      } catch (_: Exception) {
        sendNotice(LibraryNoticeKind.IMPORT_FAILED)
      }
    }
  }

  fun confirmReset() {
    val active = dialog.value as? LibraryDialog.Reset ?: return
    dialog.value = null
    viewModelScope.launch {
      if (repository.resetProgress(active.book.id)) {
        sendNotice(LibraryNoticeKind.PROGRESS_RESET)
      } else {
        sendNotice(LibraryNoticeKind.BOOK_OPEN_FAILED)
      }
    }
  }

  private fun updateCovers(latest: List<LibraryBook>) {
    val validIds = latest.mapTo(mutableSetOf()) { it.id }
    covers.value = covers.value.filterKeys(validIds::contains)
    for (book in latest) {
      if (book.coverFile == null || covers.value.containsKey(book.id) || coverJobs[book.id]?.isActive == true) {
        continue
      }
      coverJobs[book.id] =
        viewModelScope.launch {
          val bitmap = repository.loadCover(book.id) ?: return@launch
          covers.value =
            (covers.value + (book.id to bitmap))
              .entries
              .toList()
              .takeLast(MAX_COVER_CACHE)
              .associate { it.key to it.value }
          publishState()
        }
    }
    for ((bookId, job) in coverJobs.toList()) {
      if (bookId !in validIds) {
        job.cancel()
        coverJobs.remove(bookId)
      }
    }
  }

  private fun publishState() {
    val latestBooks = books.value ?: return
    _state.value =
      LibraryScreenState(
        isLoading = books.value == null,
        books = latestBooks,
        continueReading =
          latestBooks.firstOrNull { it.readingState == ReadingState.READING },
        covers = covers.value,
        importState = importState.value,
        dialog = dialog.value,
      )
  }

  private fun sendNotice(kind: LibraryNoticeKind) {
    viewModelScope.launch { eventChannel.send(LibraryEvent.Notice(kind)) }
  }

  private fun noticeFor(code: ImportErrorCode): LibraryNoticeKind =
    when (code) {
      ImportErrorCode.SOURCE_UNAVAILABLE -> LibraryNoticeKind.IMPORT_SOURCE_UNAVAILABLE
      ImportErrorCode.SOURCE_TOO_LARGE,
      ImportErrorCode.ZIP_LIMIT_EXCEEDED,
      ImportErrorCode.XML_VALUE_TOO_LARGE,
      ImportErrorCode.TEXT_LIMIT_EXCEEDED,
      ImportErrorCode.WORD_LIMIT_EXCEEDED -> LibraryNoticeKind.IMPORT_FILE_TOO_LARGE
      ImportErrorCode.UNSUPPORTED_ENCODING,
      ImportErrorCode.BAD_ENCODING -> LibraryNoticeKind.IMPORT_ENCODING
      ImportErrorCode.BAD_ZIP,
      ImportErrorCode.UNSAFE_ZIP_PATH -> LibraryNoticeKind.IMPORT_BAD_ZIP
      ImportErrorCode.ZIP_ENCRYPTED -> LibraryNoticeKind.IMPORT_ZIP_ENCRYPTED
      ImportErrorCode.ZIP_HAS_NO_FB2 -> LibraryNoticeKind.IMPORT_ZIP_WITHOUT_BOOK
      ImportErrorCode.DOCTYPE_FORBIDDEN,
      ImportErrorCode.BAD_XML,
      ImportErrorCode.XML_DEPTH_EXCEEDED -> LibraryNoticeKind.IMPORT_BAD_XML
      ImportErrorCode.NOT_FB2 -> LibraryNoticeKind.IMPORT_NOT_FB2
      ImportErrorCode.NO_TEXT -> LibraryNoticeKind.IMPORT_NO_TEXT
      ImportErrorCode.CANCELLED -> LibraryNoticeKind.IMPORT_FAILED
      ImportErrorCode.STORAGE_WRITE_FAILED -> LibraryNoticeKind.IMPORT_FAILED
    }

  private fun noticeFor(code: StorageErrorCode): LibraryNoticeKind =
    when (code) {
      StorageErrorCode.NOT_ENOUGH_SPACE -> LibraryNoticeKind.IMPORT_NOT_ENOUGH_SPACE
      StorageErrorCode.SOURCE_UNAVAILABLE -> LibraryNoticeKind.IMPORT_SOURCE_UNAVAILABLE
      StorageErrorCode.SOURCE_TOO_LARGE -> LibraryNoticeKind.IMPORT_FILE_TOO_LARGE
      StorageErrorCode.IMPORT_CANCELLED -> LibraryNoticeKind.IMPORT_FAILED
      else -> LibraryNoticeKind.IMPORT_FAILED
    }

  private inner class Interaction(
    private val interactionGeneration: Long,
  ) : com.upr1se.kadr.data.storage.ImportInteraction {
    override suspend fun chooseZipBook(
      candidates: List<ZipBookCandidate>,
    ): ZipBookCandidate? {
      if (generation.get() != interactionGeneration) return null
      val deferred = CompletableDeferred<ZipBookCandidate?>()
      zipChoice = deferred
      importState.value = ImportUiState.ZipSelection(candidates)
      return deferred.await()
    }

    override suspend fun resolveDuplicate(
      existing: com.upr1se.kadr.data.db.BookEntity,
      exactSourceMatch: Boolean,
    ): DuplicateDecision {
      if (generation.get() != interactionGeneration) return DuplicateDecision.CANCEL
      val deferred = CompletableDeferred<DuplicateDecision>()
      duplicateChoice = deferred
      importState.value = ImportUiState.Duplicate(existing.title, exactSourceMatch)
      return deferred.await()
    }
  }

  private companion object {
    const val MAX_COVER_CACHE = 30
  }
}
