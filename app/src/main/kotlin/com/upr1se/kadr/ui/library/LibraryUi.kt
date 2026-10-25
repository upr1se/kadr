package com.upr1se.kadr.ui.library

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.upr1se.kadr.R
import com.upr1se.kadr.data.db.ContentState
import com.upr1se.kadr.data.db.ReadingState
import com.upr1se.kadr.data.storage.BookInfo
import com.upr1se.kadr.data.storage.DuplicateDecision
import com.upr1se.kadr.data.storage.ImportStage
import com.upr1se.kadr.data.storage.LibraryBook
import com.upr1se.kadr.fb2.Fb2WarningCode
import com.upr1se.kadr.fb2.ZipBookCandidate
import com.upr1se.kadr.ui.components.bookAuthorText
import com.upr1se.kadr.ui.components.bookFormatText
import com.upr1se.kadr.ui.components.bookTitleText
import com.upr1se.kadr.ui.components.sectionTitleText
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import kotlin.math.absoluteValue

@Composable
fun LibraryRoute(
  viewModel: LibraryViewModel,
  onOpenBook: (String) -> Unit,
  onOpenSettings: () -> Unit,
  externalNotice: LibraryNoticeKind?,
  onExternalNoticeShown: () -> Unit,
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val snackbarHostState = remember { SnackbarHostState() }
  val context = LocalContext.current
  val noticeMessages = LibraryNoticeKind.entries.associateWith { stringResource(noticeStringResource(it)) }
  val openActionLabel = stringResource(R.string.notice_open)
  val launcher =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      uri?.let(viewModel::startImport)
    }

  LaunchedEffect(viewModel) {
    viewModel.events.collect { event ->
      when (event) {
        is LibraryEvent.OpenReader -> onOpenBook(event.bookId)
        is LibraryEvent.Notice -> {
          val result =
            snackbarHostState.showSnackbar(
              message = noticeMessages.getValue(event.kind),
              actionLabel = event.openBookId?.let { openActionLabel },
              withDismissAction = true,
            )
          if (result == SnackbarResult.ActionPerformed) {
            event.openBookId?.let(onOpenBook)
          }
        }
      }
    }
  }
  LaunchedEffect(externalNotice) {
    if (externalNotice != null) {
      snackbarHostState.showSnackbar(noticeMessages.getValue(externalNotice), withDismissAction = true)
      onExternalNoticeShown()
    }
  }

  LibraryScreen(
    state = state,
    snackbarHostState = snackbarHostState,
    onOpenSettings = onOpenSettings,
    onAddBook = { launcher.launch(arrayOf("*/*")) },
    onOpenBook = viewModel::openBook,
    onDeleteRequested = viewModel::requestDelete,
    onResetRequested = viewModel::requestReset,
    onInfoRequested = viewModel::requestInfo,
    onDismissDialog = viewModel::dismissDialog,
    onConfirmDelete = viewModel::confirmDelete,
    onConfirmReset = viewModel::confirmReset,
    onZipSelected = viewModel::selectZipBook,
    onDuplicateDecision = viewModel::chooseDuplicate,
    onCancelImport = viewModel::cancelImport,
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
  state: LibraryScreenState,
  snackbarHostState: SnackbarHostState,
  onOpenSettings: () -> Unit,
  onAddBook: () -> Unit,
  onOpenBook: (String) -> Unit,
  onDeleteRequested: (LibraryBook) -> Unit,
  onResetRequested: (LibraryBook) -> Unit,
  onInfoRequested: (String) -> Unit,
  onDismissDialog: () -> Unit,
  onConfirmDelete: () -> Unit,
  onConfirmReset: () -> Unit,
  onZipSelected: (ZipBookCandidate?) -> Unit,
  onDuplicateDecision: (DuplicateDecision) -> Unit,
  onCancelImport: () -> Unit,
) {
  val listState = rememberLazyListState()
  val settingsDescription = stringResource(R.string.library_open_settings)
  val addDescription = stringResource(R.string.library_add_content_description)
  val import = state.importState
  val canCancel =
    when (import) {
      ImportUiState.Idle -> false
      is ImportUiState.Working -> import.canCancel
      is ImportUiState.ZipSelection,
      is ImportUiState.Duplicate -> true
    }
  BackHandler(enabled = import !is ImportUiState.Idle && canCancel) {
    onCancelImport()
  }

  Scaffold(
    modifier = Modifier.fillMaxSize(),
    snackbarHost = { SnackbarHost(snackbarHostState) },
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.library_title)) },
        actions = {
          TextButton(
            onClick = onOpenSettings,
            modifier =
              Modifier.size(48.dp)
                .semantics {
                  contentDescription = settingsDescription
                },
          ) {
            Text(
              text = stringResource(R.string.symbol_settings),
              style = MaterialTheme.typography.titleLarge,
            )
          }
        },
      )
    },
    floatingActionButton = {
      if (state.books.isNotEmpty()) {
        FloatingActionButton(onClick = onAddBook) {
          Text(
            text = stringResource(R.string.symbol_add),
            modifier =
              Modifier.semantics {
                contentDescription = addDescription
              },
            style = MaterialTheme.typography.headlineSmall,
          )
        }
      }
    },
  ) { padding ->
    when {
      state.isLoading ->
        Box(
          modifier = Modifier.fillMaxSize().padding(padding),
          contentAlignment = Alignment.Center,
        ) {
          CircularProgressIndicator()
        }
      state.books.isEmpty() ->
        EmptyLibrary(
          modifier = Modifier.fillMaxSize().padding(padding),
          onAddBook = onAddBook,
        )
      else ->
        LazyColumn(
          modifier = Modifier.fillMaxSize().padding(padding),
          state = listState,
          contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        ) {
          state.continueReading?.let { book ->
            item {
              Text(
                text = stringResource(R.string.library_continue),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
              )
            }
            item {
              BookRow(
                book = book,
                cover = state.covers[book.id],
                continueStyle = true,
                onClick = { onOpenBook(book.id) },
                onDelete = { onDeleteRequested(book) },
                onReset = { onResetRequested(book) },
                onInfo = { onInfoRequested(book.id) },
              )
            }
          }
          item {
            Text(
              text = stringResource(R.string.library_all_books),
              style = MaterialTheme.typography.titleMedium,
              modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
            )
          }
          items(state.books, key = { it.id }) { book ->
            BookRow(
              book = book,
              cover = state.covers[book.id],
              continueStyle = false,
              onClick = { onOpenBook(book.id) },
              onDelete = { onDeleteRequested(book) },
              onReset = { onResetRequested(book) },
              onInfo = { onInfoRequested(book.id) },
            )
          }
        }
    }
  }

  when (val activeDialog = state.dialog) {
    is LibraryDialog.Delete ->
      AlertDialog(
        onDismissRequest = onDismissDialog,
        title = { Text(stringResource(R.string.delete_title)) },
        text = { Text(stringResource(R.string.delete_body, bookTitleText(activeDialog.book.title))) },
        confirmButton = {
          TextButton(
            onClick = onConfirmDelete,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
          ) {
            Text(stringResource(R.string.delete_confirm))
          }
        },
        dismissButton = {
          TextButton(onClick = onDismissDialog) { Text(stringResource(R.string.cancel)) }
        },
      )
    is LibraryDialog.Reset ->
      AlertDialog(
        onDismissRequest = onDismissDialog,
        title = { Text(stringResource(R.string.reset_title)) },
        text = { Text(stringResource(R.string.reset_body, bookTitleText(activeDialog.book.title))) },
        confirmButton = {
          TextButton(onClick = onConfirmReset) { Text(stringResource(R.string.reset_confirm)) }
        },
        dismissButton = {
          TextButton(onClick = onDismissDialog) { Text(stringResource(R.string.cancel)) }
        },
      )
    is LibraryDialog.Info -> BookInfoDialog(activeDialog.info, onDismissDialog)
    null -> Unit
  }

  when (import) {
    ImportUiState.Idle -> Unit
    is ImportUiState.Working -> {
      if (import.indicatorVisible) {
        AlertDialog(
          onDismissRequest = { if (import.canCancel) onCancelImport() },
          title = { Text(importStageText(import.progress.stage)) },
          text = {
            Column {
              LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
              import.progress.totalBytes?.let { total ->
                if (total > 0) {
                  Spacer(Modifier.height(12.dp))
                  Text(
                    stringResource(
                      R.string.library_progress,
                      ((100L * import.progress.bytesCopied) / total).toInt(),
                    ),
                  )
                }
              }
            }
          },
          confirmButton = {
            if (import.canCancel) {
              TextButton(onClick = onCancelImport) { Text(stringResource(R.string.cancel)) }
            }
          },
        )
      }
    }
    is ImportUiState.ZipSelection ->
      ModalBottomSheet(onDismissRequest = { onZipSelected(null) }) {
        Text(
          text = stringResource(R.string.import_zip_title),
          style = MaterialTheme.typography.titleLarge,
          modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
        )
        LazyColumn(
          modifier = Modifier.heightIn(max = 360.dp),
          contentPadding = PaddingValues(bottom = 8.dp),
        ) {
          items(import.candidates, key = { it.entryIndex }) { candidate ->
            val context = LocalContext.current
            Row(
              modifier =
                Modifier.fillMaxWidth()
                  .clickable { onZipSelected(candidate) }
                  .padding(horizontal = 24.dp, vertical = 12.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                text =
                  stringResource(
                    R.string.import_zip_size,
                    candidate.displayPath,
                    Formatter.formatShortFileSize(context, candidate.sizeBytes),
                  ),
                modifier = Modifier.weight(1f),
                fontWeight = FontWeight.Medium,
              )
              TextButton(onClick = { onZipSelected(candidate) }) {
                Text(stringResource(R.string.import_zip_choose))
              }
            }
          }
        }
        TextButton(
          onClick = { onZipSelected(null) },
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
          Text(stringResource(R.string.cancel))
        }
      }
    is ImportUiState.Duplicate ->
      AlertDialog(
        onDismissRequest = { onDuplicateDecision(DuplicateDecision.CANCEL) },
        title = { Text(stringResource(R.string.import_duplicate_title)) },
        text = {
          Column {
            Text(stringResource(R.string.import_duplicate_body, bookTitleText(import.existingTitle)))
            Text(
              text =
                stringResource(
                  if (import.exactSourceMatch) {
                    R.string.import_duplicate_same_file
                  } else {
                    R.string.import_duplicate_same_text
                  },
                ),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(top = 8.dp),
            )
          }
        },
        confirmButton = {
          Column(horizontalAlignment = Alignment.End) {
            TextButton(onClick = { onDuplicateDecision(DuplicateDecision.OPEN_EXISTING) }) {
              Text(stringResource(R.string.import_open_existing))
            }
            TextButton(onClick = { onDuplicateDecision(DuplicateDecision.IMPORT_COPY) }) {
              Text(stringResource(R.string.import_copy))
            }
          }
        },
        dismissButton = {
          TextButton(onClick = { onDuplicateDecision(DuplicateDecision.CANCEL) }) {
            Text(stringResource(R.string.cancel))
          }
        },
      )
  }
}

@Composable
private fun EmptyLibrary(
  modifier: Modifier,
  onAddBook: () -> Unit,
) {
  Column(
    modifier =
      modifier
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 32.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Text(
      text = stringResource(R.string.symbol_book),
      style = MaterialTheme.typography.displayLarge,
      color = MaterialTheme.colorScheme.primary,
    )
    Text(
      text = stringResource(R.string.library_empty_title),
      modifier = Modifier.padding(top = 20.dp),
      style = MaterialTheme.typography.headlineSmall,
      textAlign = TextAlign.Center,
    )
    Text(
      text = stringResource(R.string.library_empty_body),
      modifier = Modifier.padding(top = 8.dp),
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
    Button(
      onClick = onAddBook,
      modifier = Modifier.padding(top = 24.dp).height(52.dp),
    ) {
      Text(stringResource(R.string.library_add_book))
    }
    Text(
      text = stringResource(R.string.library_empty_local),
      modifier = Modifier.padding(top = 16.dp),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun BookRow(
  book: LibraryBook,
  cover: android.graphics.Bitmap?,
  continueStyle: Boolean,
  onClick: () -> Unit,
  onDelete: () -> Unit,
  onReset: () -> Unit,
  onInfo: () -> Unit,
) {
  var menuExpanded by remember { mutableStateOf(false) }
  val displayTitle = bookTitleText(book.title)
  val displayAuthor = bookAuthorText(book.authorDisplay)
  val displayChapter = book.currentChapterTitle?.let { sectionTitleText(it) }
  val cardDescription = stringResource(R.string.library_open_cover, displayTitle)
  val menuDescription = stringResource(R.string.library_book_menu, displayTitle)
  Card(
    modifier =
      Modifier.fillMaxWidth()
        .padding(vertical = 5.dp)
        .clickable(onClick = onClick)
        .semantics { contentDescription = cardDescription },
    colors =
      if (continueStyle) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
      } else {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
      },
    elevation = CardDefaults.cardElevation(defaultElevation = if (continueStyle) 2.dp else 0.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      BookCover(title = displayTitle, cover = cover)
      Column(modifier = Modifier.weight(1f).padding(horizontal = 14.dp)) {
        Text(
          text = displayTitle,
          style = MaterialTheme.typography.titleMedium,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = displayAuthor,
          modifier = Modifier.padding(top = 2.dp),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = bookStatusText(book, displayChapter),
          modifier = Modifier.padding(top = 8.dp),
          style = MaterialTheme.typography.bodySmall,
          color =
            if (book.contentState == ContentState.BROKEN) {
              MaterialTheme.colorScheme.error
            } else {
              MaterialTheme.colorScheme.primary
            },
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      Box {
        TextButton(
          onClick = { menuExpanded = true },
          modifier =
            Modifier.size(48.dp)
              .semantics {
                contentDescription = menuDescription
              },
        ) {
          Text(stringResource(R.string.symbol_actions), style = MaterialTheme.typography.titleLarge)
        }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
          if (book.contentState == ContentState.BROKEN) {
            DropdownMenuItem(
              text = { Text(stringResource(R.string.book_menu_details)) },
              onClick = {
                menuExpanded = false
                onInfo()
              },
            )
          } else {
            DropdownMenuItem(
              text = { Text(stringResource(R.string.book_menu_open)) },
              onClick = {
                menuExpanded = false
                onClick()
              },
            )
            DropdownMenuItem(
              text = { Text(stringResource(R.string.book_menu_restart)) },
              onClick = {
                menuExpanded = false
                onReset()
              },
            )
            DropdownMenuItem(
              text = { Text(stringResource(R.string.book_menu_info)) },
              onClick = {
                menuExpanded = false
                onInfo()
              },
            )
          }
          DropdownMenuItem(
            text = {
              Text(
                stringResource(R.string.book_menu_delete),
                color = MaterialTheme.colorScheme.error,
              )
            },
            onClick = {
              menuExpanded = false
              onDelete()
            },
          )
        }
      }
    }
  }
}

@Composable
private fun BookCover(
  title: String,
  cover: android.graphics.Bitmap?,
) {
  val shape = RoundedCornerShape(6.dp)
  if (cover != null) {
    Image(
      bitmap = cover.asImageBitmap(),
      contentDescription = stringResource(R.string.library_generated_cover, title),
      modifier = Modifier.width(64.dp).height(92.dp).clip(shape),
      contentScale = ContentScale.Crop,
    )
  } else {
    val palette =
      listOf(
        Color(0xFF8F4A3E),
        Color(0xFF4C6654),
        Color(0xFF5A587A),
        Color(0xFF846B3C),
        Color(0xFF46697B),
      )
    val color = palette[(title.hashCode().toLong().absoluteValue % palette.size).toInt()]
    Column(
      modifier = Modifier.width(64.dp).height(92.dp).clip(shape).background(color).padding(6.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
    ) {
      Text(
        text = title.firstOrNull()?.uppercase() ?: "",
        style = MaterialTheme.typography.headlineMedium,
        color = Color.White,
      )
      Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        color = Color.White.copy(alpha = 0.88f),
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
      )
    }
  }
}

@Composable
private fun BookInfoDialog(
  info: BookInfo,
  onDismiss: () -> Unit,
) {
  val context = LocalContext.current
  val locale = LocalConfiguration.current.locales[0]
  val date =
    DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(Date(info.importedAt))
  val size = Formatter.formatShortFileSize(context, info.sizeBytes)
  val words = NumberFormat.getIntegerInstance(locale).format(info.wordCount)
  val title = bookTitleText(info.title)
  val author = bookAuthorText(info.authorDisplay)
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.book_info_title)) },
    text = {
      Column(
        modifier =
          Modifier.fillMaxWidth()
            .heightIn(max = 520.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.book_info_author, author))
        info.language?.let { Text(stringResource(R.string.book_info_language, it)) }
        Text(stringResource(R.string.book_info_words, words))
        Text(stringResource(R.string.book_info_chapters, info.chapterCount))
        Text(stringResource(R.string.book_info_format, bookFormatText(info.format)))
        Text(stringResource(R.string.book_info_added, date))
        Text(stringResource(R.string.book_info_size, size))
        if (info.errorCode != null) {
          Text(
            stringResource(R.string.book_info_broken),
            color = MaterialTheme.colorScheme.error,
          )
        }
        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
        Text(
          text = stringResource(R.string.book_info_warnings),
          style = MaterialTheme.typography.titleSmall,
        )
        val warningCodes = info.warnings.map { it.code }.distinct()
        if (warningCodes.isEmpty()) {
          Text(stringResource(R.string.book_info_no_warnings))
        } else {
          warningCodes.forEach { code ->
            Text(
              text = stringResource(warningTextResource(code)),
              style = MaterialTheme.typography.bodySmall,
            )
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
    },
  )
}

@Composable
private fun bookStatusText(
  book: LibraryBook,
  displayChapter: String?,
): String =
  when {
    book.contentState == ContentState.BROKEN -> stringResource(R.string.library_broken)
    book.readingState == ReadingState.COMPLETED ->
      stringResource(R.string.library_progress_completed)
    book.readingState == ReadingState.READING &&
      !displayChapter.isNullOrBlank() ->
      stringResource(
        R.string.library_progress_chapter,
        displayChapter,
        book.progressPercent,
      )
    book.readingState == ReadingState.READING ->
      stringResource(R.string.library_progress_reading, book.progressPercent)
    else -> stringResource(R.string.library_progress_unread)
  }

@Composable
private fun importStageText(stage: ImportStage): String =
  when (stage) {
    ImportStage.COPYING -> stringResource(R.string.import_stage_copying)
    ImportStage.INSPECTING -> stringResource(R.string.import_stage_inspecting)
    ImportStage.PARSING -> stringResource(R.string.import_stage_parsing)
    ImportStage.PREPARING_TEXT -> stringResource(R.string.import_stage_preparing_text)
    ImportStage.PREPARING_COVER -> stringResource(R.string.import_stage_preparing_cover)
    ImportStage.SAVING -> stringResource(R.string.import_stage_saving)
    ImportStage.COMPLETE -> stringResource(R.string.import_stage_complete)
  }

@Composable
fun noticeText(kind: LibraryNoticeKind): String =
  stringResource(noticeStringResource(kind))

fun noticeStringResource(kind: LibraryNoticeKind): Int =
  when (kind) {
    LibraryNoticeKind.BOOK_ADDED -> R.string.notice_book_added
    LibraryNoticeKind.BOOK_DELETED -> R.string.notice_book_deleted
    LibraryNoticeKind.PROGRESS_RESET -> R.string.notice_progress_reset
    LibraryNoticeKind.ONE_AT_A_TIME -> R.string.notice_one_at_a_time
    LibraryNoticeKind.CURRENT_IMPORT_RUNNING -> R.string.notice_import_running
    LibraryNoticeKind.IMPORT_SOURCE_UNAVAILABLE -> R.string.notice_source_unavailable
    LibraryNoticeKind.IMPORT_FILE_TOO_LARGE -> R.string.notice_file_too_large
    LibraryNoticeKind.IMPORT_NOT_FB2 -> R.string.notice_not_fb2
    LibraryNoticeKind.IMPORT_BAD_XML -> R.string.notice_bad_xml
    LibraryNoticeKind.IMPORT_BAD_ZIP -> R.string.notice_bad_zip
    LibraryNoticeKind.IMPORT_ZIP_ENCRYPTED -> R.string.notice_zip_encrypted
    LibraryNoticeKind.IMPORT_ZIP_WITHOUT_BOOK -> R.string.notice_zip_without_book
    LibraryNoticeKind.IMPORT_ENCODING -> R.string.notice_bad_encoding
    LibraryNoticeKind.IMPORT_NO_TEXT -> R.string.notice_no_text
    LibraryNoticeKind.IMPORT_NOT_ENOUGH_SPACE -> R.string.notice_not_enough_space
    LibraryNoticeKind.IMPORT_FAILED -> R.string.notice_import_failed
    LibraryNoticeKind.BOOK_OPEN_FAILED -> R.string.notice_book_open_failed
  }

fun warningTextResource(code: Fb2WarningCode): Int =
  when (code) {
    Fb2WarningCode.ENCODING_DECLARATION_CONFLICT -> R.string.warning_encoding_conflict
    Fb2WarningCode.METADATA_TRUNCATED -> R.string.warning_metadata_truncated
    Fb2WarningCode.NOTE_TRUNCATED -> R.string.warning_note_truncated
    Fb2WarningCode.MISSING_NOTE -> R.string.warning_missing_note
    Fb2WarningCode.LONG_TOKEN_SPLIT -> R.string.warning_long_token
    Fb2WarningCode.COVER_REJECTED -> R.string.warning_cover_rejected
    Fb2WarningCode.UNSUPPORTED_ELEMENT -> R.string.warning_unsupported_element
    Fb2WarningCode.DUPLICATE_NOTE_ID -> R.string.warning_duplicate_note
  }
