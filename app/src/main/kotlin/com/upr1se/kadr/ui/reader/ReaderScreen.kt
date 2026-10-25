package com.upr1se.kadr.ui.reader

import android.app.Activity
import android.text.format.Formatter
import android.view.ViewTreeObserver
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.upr1se.kadr.R
import com.upr1se.kadr.data.settings.SettingsRepository
import com.upr1se.kadr.data.storage.BookInfo
import com.upr1se.kadr.data.storage.IndexItemKind
import com.upr1se.kadr.domain.reader.FocusCalculator
import com.upr1se.kadr.domain.reader.PlaybackStatus
import com.upr1se.kadr.domain.reader.ReaderDurations
import com.upr1se.kadr.domain.reader.ReaderItem
import com.upr1se.kadr.domain.reader.ReaderPlaybackState
import com.upr1se.kadr.fb2.ParsedSection
import com.upr1se.kadr.ui.library.noticeText
import com.upr1se.kadr.ui.library.warningTextResource
import com.upr1se.kadr.ui.components.bookAuthorText
import com.upr1se.kadr.ui.components.bookFormatText
import com.upr1se.kadr.ui.components.bookTitleText
import com.upr1se.kadr.ui.components.sectionTitleText
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect

private enum class ReaderSheet {
  CONTENTS,
  SPEED,
  NOTES,
  ACTIONS,
}

@Composable
fun ReaderRoute(
  viewModel: ReaderViewModel,
  onBackToLibrary: () -> Unit,
  onOpenSettings: () -> Unit,
  onMissingBook: () -> Unit,
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val snackbarHostState = remember { SnackbarHostState() }
  val lifecycleOwner = LocalLifecycleOwner.current
  val view = LocalView.current
  val saveFailedMessage = stringResource(R.string.reader_save_failed)
  val restoreMessage = stringResource(R.string.reader_restore_inexact)
  val infoFailedMessage = stringResource(R.string.notice_book_open_failed)

  LaunchedEffect(viewModel) {
    viewModel.events.collect { event ->
      when (event) {
        ReaderUiEvent.NavigateBack -> onBackToLibrary()
        ReaderUiEvent.NavigateSettings -> onOpenSettings()
        ReaderUiEvent.NavigateLibraryForMissingBook -> onMissingBook()
        is ReaderUiEvent.Notice -> {
          val message =
            when (event.notice) {
              ReaderNotice.SAVE_FAILED -> saveFailedMessage
              ReaderNotice.RESTORE_INEXACT -> restoreMessage
              ReaderNotice.BOOK_INFO_FAILED -> infoFailedMessage
            }
          snackbarHostState.showSnackbar(message, withDismissAction = true)
        }
      }
    }
  }
  DisposableEffect(lifecycleOwner, viewModel) {
    val observer =
      LifecycleEventObserver { _, event ->
        when (event) {
          Lifecycle.Event.ON_RESUME -> viewModel.setForeground(true)
          Lifecycle.Event.ON_PAUSE,
          Lifecycle.Event.ON_STOP -> viewModel.setForeground(false)
          else -> Unit
        }
      }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }
  DisposableEffect(view, viewModel) {
    val listener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
      viewModel.setForeground(hasFocus)
    }
    view.viewTreeObserver.addOnWindowFocusChangeListener(listener)
    onDispose { view.viewTreeObserver.removeOnWindowFocusChangeListener(listener) }
  }
  ReaderScreen(
    state = state,
    snackbarHostState = snackbarHostState,
    onBack = viewModel::requestLeave,
    onPlay = viewModel::play,
    onPause = viewModel::pause,
    onTogglePlay = viewModel::togglePlay,
    onPreviousSentence = viewModel::previousSentence,
    onNextSentence = viewModel::nextSentence,
    onSpeedDecrease = { viewModel.adjustSpeed(-ReaderDurations.SPEED_STEP_WPM) },
    onSpeedIncrease = { viewModel.adjustSpeed(ReaderDurations.SPEED_STEP_WPM) },
    onSpeedSet = viewModel::setSpeed,
    onSectionSelected = viewModel::openSection,
    onDismissHint = viewModel::dismissHint,
    onSettings = viewModel::requestSettings,
    onRestart = viewModel::restart,
    onRequestInfo = viewModel::requestBookInfo,
    onDismissInfo = viewModel::dismissBookInfo,
    onRetryLeave = viewModel::retryLeave,
    onDismissLeaveFailure = viewModel::dismissLeaveFailure,
    onLeaveWithoutSaving = viewModel::leaveWithoutSaving,
    onLeaveError = viewModel::openLibraryFromError,
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
  state: ReaderScreenState,
  snackbarHostState: SnackbarHostState,
  onBack: () -> Unit,
  onPlay: () -> Unit,
  onPause: () -> Unit,
  onTogglePlay: () -> Unit,
  onPreviousSentence: () -> Unit,
  onNextSentence: () -> Unit,
  onSpeedDecrease: () -> Unit,
  onSpeedIncrease: () -> Unit,
  onSpeedSet: (Int) -> Unit,
  onSectionSelected: (String) -> Unit,
  onDismissHint: () -> Unit,
  onSettings: () -> Unit,
  onRestart: () -> Unit,
  onRequestInfo: () -> Unit,
  onDismissInfo: () -> Unit,
  onRetryLeave: () -> Unit,
  onDismissLeaveFailure: () -> Unit,
  onLeaveWithoutSaving: () -> Unit,
  onLeaveError: () -> Unit,
) {
  var sheet by remember { mutableStateOf<ReaderSheet?>(null) }
  var restartDialog by remember { mutableStateOf(false) }
  val displayTitle = bookTitleText(state.bookTitle)
  val playback = state.playback
  val playing = playback?.status == PlaybackStatus.PLAYING
  val context = LocalContext.current

  BackHandler {
    when {
      restartDialog -> restartDialog = false
      state.leaveSaveFailed -> onDismissLeaveFailure()
      state.bookInfo != null -> onDismissInfo()
      sheet != null -> sheet = null
      state.errorCode != null -> onLeaveError()
      else -> onBack()
    }
  }

  val view = LocalView.current
  val activity = context.findActivity()
  DisposableEffect(playing, view, activity) {
    val window = activity?.window
    val bars = window?.let { WindowCompat.getInsetsController(it, view) }
    if (playing) {
      bars?.systemBarsBehavior =
        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
      bars?.hide(WindowInsetsCompat.Type.systemBars())
    } else {
      bars?.show(WindowInsetsCompat.Type.systemBars())
    }
    onDispose { bars?.show(WindowInsetsCompat.Type.systemBars()) }
  }
  DisposableEffect(playing, view) {
    if (playing) view.keepScreenOn = true
    onDispose { view.keepScreenOn = false }
  }

  Scaffold(
    modifier = Modifier.fillMaxSize(),
    containerColor = MaterialTheme.colorScheme.background,
    contentWindowInsets = WindowInsets(0),
    snackbarHost = { SnackbarHost(snackbarHostState) },
  ) { scaffoldPadding ->
    Box(
      modifier =
        Modifier.fillMaxSize()
          .padding(scaffoldPadding)
          .then(
            if (playback?.status == PlaybackStatus.PAUSED ||
              playback?.status == PlaybackStatus.PLAYING
            ) {
              Modifier
            } else {
              Modifier.windowInsetsPadding(WindowInsets.safeDrawing)
            },
          )
          .background(MaterialTheme.colorScheme.background),
    ) {
      when {
        state.loading -> LoadingReader(state.showLoadingIndicator)
        state.errorCode != null ->
          ErrorReader(
            onBack = onLeaveError,
          )
        playback == null -> LoadingReader(true)
        playback.status == PlaybackStatus.COMPLETED ->
          CompletedReader(
            title = state.bookTitle,
            onRestart = { restartDialog = true },
            onBack = onBack,
          )
        playback.status == PlaybackStatus.CHAPTER_BREAK ->
          ChapterBreakReader(
            number = playback.nextChapterNumber,
            title = playback.nextChapterTitle.orEmpty(),
            onPlay = onPlay,
            onBack = onBack,
          )
        else ->
          PlaybackReader(
            state = state,
            playback = playback,
            onBack = onBack,
            onPlay = onPlay,
            onPause = onPause,
            onTogglePlay = onTogglePlay,
            onPreviousSentence = onPreviousSentence,
            onNextSentence = onNextSentence,
            onSpeedDecrease = onSpeedDecrease,
            onSpeedIncrease = onSpeedIncrease,
            onOpenSpeed = { sheet = ReaderSheet.SPEED },
            onOpenContents = { sheet = ReaderSheet.CONTENTS },
            onOpenActions = { sheet = ReaderSheet.ACTIONS },
            onOpenNotes = { sheet = ReaderSheet.NOTES },
            onDismissHint = onDismissHint,
          )
      }
    }
  }

  playback?.let { current ->
    when (sheet) {
      ReaderSheet.CONTENTS ->
        ContentsSheet(
          sections = state.sections,
          currentChapterKey = current.item.chapterKey,
          onSelect = {
            sheet = null
            onSectionSelected(it)
          },
          onDismiss = { sheet = null },
        )
      ReaderSheet.SPEED ->
        SpeedSheet(
          speed = current.speedWpm,
          onSpeedChanged = onSpeedSet,
          onDismiss = { sheet = null },
        )
      ReaderSheet.NOTES ->
        NotesSheet(
          blocks = state.noteBlocks,
          onDismiss = { sheet = null },
        )
      ReaderSheet.ACTIONS ->
        ActionsSheet(
          onRestart = {
            sheet = null
            restartDialog = true
          },
          onSettings = {
            sheet = null
            onSettings()
          },
          onInfo = {
            sheet = null
            onRequestInfo()
          },
          onDismiss = { sheet = null },
        )
      null -> Unit
    }
  }

  if (restartDialog) {
    AlertDialog(
      onDismissRequest = { restartDialog = false },
      title = { Text(stringResource(R.string.reader_restart)) },
      text = { Text(stringResource(R.string.reader_restart_confirm_body, displayTitle)) },
      confirmButton = {
        TextButton(
          onClick = {
            restartDialog = false
            onRestart()
          },
        ) {
          Text(stringResource(R.string.reader_restart))
        }
      },
      dismissButton = {
        TextButton(onClick = { restartDialog = false }) { Text(stringResource(R.string.cancel)) }
      },
    )
  }
  if (state.leaveSaveFailed) {
    AlertDialog(
      onDismissRequest = onDismissLeaveFailure,
      title = { Text(stringResource(R.string.reader_leave_failed_title)) },
      text = { Text(stringResource(R.string.reader_leave_failed_body)) },
      confirmButton = {
        TextButton(onClick = onRetryLeave) { Text(stringResource(R.string.retry)) }
      },
      dismissButton = {
        TextButton(onClick = onLeaveWithoutSaving) {
          Text(
            stringResource(R.string.reader_exit_without_save),
            color = MaterialTheme.colorScheme.error,
          )
        }
      },
    )
  }
  state.bookInfo?.let { info ->
    ReaderBookInfoDialog(info = info, onDismiss = onDismissInfo)
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlaybackReader(
  state: ReaderScreenState,
  playback: ReaderPlaybackState,
  onBack: () -> Unit,
  onPlay: () -> Unit,
  onPause: () -> Unit,
  onTogglePlay: () -> Unit,
  onPreviousSentence: () -> Unit,
  onNextSentence: () -> Unit,
  onSpeedDecrease: () -> Unit,
  onSpeedIncrease: () -> Unit,
  onOpenSpeed: () -> Unit,
  onOpenContents: () -> Unit,
  onOpenActions: () -> Unit,
  onOpenNotes: () -> Unit,
  onDismissHint: () -> Unit,
) {
  val playing = playback.status == PlaybackStatus.PLAYING
  val stableInsets = WindowInsets.systemBarsIgnoringVisibility
  val noteDescription = stringResource(R.string.reader_note_marker)
  BoxWithConstraints(
    modifier =
      Modifier.fillMaxSize()
        .windowInsetsPadding(stableInsets)
        .then(
          if (playing) {
            Modifier.clickable(
              interactionSource = remember { MutableInteractionSource() },
              indication = null,
              role = Role.Button,
              onClickLabel = stringResource(R.string.reader_pause),
              onClick = onPause,
            )
          } else {
            Modifier
          },
        ),
  ) {
    val compact = maxHeight < 500.dp
    val fontScale = LocalDensity.current.fontScale.coerceIn(1f, 2f)
    val sentenceAreaHeight =
      if (compact) 48.dp else (76.dp * fontScale)
    Column(modifier = Modifier.fillMaxSize()) {
      ReaderTopBar(
        visible = !playing,
        title = state.bookTitle,
        canShowContents = playback.canShowContents,
        saving = state.showSaveIndicator,
        onBack = onBack,
        onContents = onOpenContents,
        onActions = onOpenActions,
      )
      if (!playing && state.firstHintVisible) {
        FirstHint(
          onDismiss = onDismissHint,
          modifier = Modifier.fillMaxWidth().height(sentenceAreaHeight),
          horizontal = compact,
        )
      } else {
        SentencePreview(
          visible = !playing && state.showSentence,
          text = playback.sentencePreview,
          modifier = Modifier.height(sentenceAreaHeight),
        )
      }
      Box(
        modifier = Modifier.weight(1f).fillMaxWidth(),
        contentAlignment = Alignment.Center,
      ) {
        val readerWidth = Modifier.widthIn(max = 640.dp).fillMaxWidth()
        when (playback.item.kind) {
          IndexItemKind.WORD ->
            FocusedWord(
              word = playback.item.text,
              requestedSizeSp = state.wordSizeSp,
              modifier = readerWidth,
            )
          IndexItemKind.HEADING ->
            Text(
              text = playback.item.text,
              modifier = readerWidth.padding(horizontal = 24.dp),
              style =
                MaterialTheme.typography.headlineMedium.copy(
                  fontFamily = FontFamily.Serif,
                  fontWeight = FontWeight.SemiBold,
                ),
              color = MaterialTheme.colorScheme.onBackground,
              textAlign = TextAlign.Center,
              maxLines = 6,
              overflow = TextOverflow.Ellipsis,
            )
          IndexItemKind.IMAGE ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
              Text(
                text = stringResource(R.string.symbol_book),
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.primary,
              )
              Text(
                text = stringResource(R.string.reader_image_placeholder),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
        }
        if (!playing && playback.noteIds.isNotEmpty()) {
          TextButton(
            onClick = onOpenNotes,
            modifier =
              Modifier.align(Alignment.TopEnd)
                .size(48.dp)
                .semantics {
                  contentDescription = noteDescription
                },
          ) {
            Text(
              text = stringResource(R.string.symbol_notes),
              color = MaterialTheme.colorScheme.primary,
              style = MaterialTheme.typography.titleLarge,
            )
          }
        }
        if (!playing) {
          Box(
            modifier =
              Modifier.fillMaxSize()
                .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.018f)),
          )
        }
      }
      Box(
        modifier =
          Modifier.fillMaxWidth()
            .height(if (compact) 116.dp else 196.dp)
            .padding(horizontal = 16.dp),
      ) {
        if (!playing) {
          PauseControls(
            playback = playback,
            compact = compact,
            onPreviousSentence = onPreviousSentence,
            onNextSentence = onNextSentence,
            onTogglePlay = onTogglePlay,
            onSpeedDecrease = onSpeedDecrease,
            onSpeedIncrease = onSpeedIncrease,
            onOpenSpeed = onOpenSpeed,
          )
        }
      }
      ReaderProgressFooter(playback = playback, playing = playing)
    }
  }
}

@Composable
private fun ReaderTopBar(
  visible: Boolean,
  title: String,
  canShowContents: Boolean,
  saving: Boolean,
  onBack: () -> Unit,
  onContents: () -> Unit,
  onActions: () -> Unit,
) {
  val saveHint = stringResource(R.string.reader_save_hint)
  val displayTitle = bookTitleText(title)
  val backDescription = stringResource(R.string.reader_back_content_description)
  val contentsDescription = stringResource(R.string.reader_contents_open)
  val actionsDescription = stringResource(R.string.reader_actions_open)
  Row(
    modifier = Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (visible) {
      TextButton(
        onClick = onBack,
        modifier =
          Modifier.semantics {
            contentDescription = backDescription
          },
      ) {
        Text(
          text = stringResource(R.string.symbol_back),
          style = MaterialTheme.typography.headlineSmall,
        )
        Text(stringResource(R.string.reader_back_library), maxLines = 1)
      }
      Text(
        text = displayTitle,
        modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
        style = MaterialTheme.typography.titleMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (saving) {
        CircularProgressIndicator(
          modifier =
            Modifier.size(20.dp).semantics {
              contentDescription = saveHint
            },
          strokeWidth = 2.dp,
        )
      }
      if (canShowContents) {
        TextButton(
          onClick = onContents,
          modifier =
            Modifier.size(48.dp)
              .semantics {
                contentDescription = contentsDescription
              },
        ) {
          Text(stringResource(R.string.symbol_contents))
        }
      }
      TextButton(
        onClick = onActions,
        modifier =
          Modifier.size(48.dp)
            .semantics {
              contentDescription = actionsDescription
            },
      ) {
        Text(stringResource(R.string.symbol_actions))
      }
    }
  }
}

@Composable
private fun SentencePreview(
  visible: Boolean,
  text: String,
  modifier: Modifier,
) {
  Box(modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
    if (visible) {
      Text(
        text = text,
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun FocusedWord(
  word: String,
  requestedSizeSp: Int,
  modifier: Modifier,
) {
  val focus = remember(word) { FocusCalculator.calculate(word) }
  val baseColor = MaterialTheme.colorScheme.onBackground
  val accent = MaterialTheme.colorScheme.primary
  val annotated = remember(word, focus, baseColor, accent) {
    buildAnnotatedString {
      append(focus.graphemes.take(focus.focusIndex).joinToString(""))
      pushStyle(
        SpanStyle(
          color = accent,
          fontWeight = FontWeight.Bold,
        ),
      )
      append(focus.focusGrapheme)
      pop()
      append(focus.graphemes.drop(focus.focusIndex + 1).joinToString(""))
    }
  }
  val description = stringResource(R.string.reader_word_accessibility, word)
  BoxWithConstraints(
    modifier = modifier.fillMaxHeight().semantics { contentDescription = description },
    contentAlignment = Alignment.Center,
  ) {
    val density = LocalDensity.current
    val maxWidthPx = with(density) { maxWidth.toPx() }
    val maxHeightPx = with(density) { maxHeight.toPx() }
    val measurer = rememberTextMeasurer()
    var fontSize = requestedSizeSp
    var style =
      TextStyle(
        fontFamily = FontFamily.Serif,
        fontSize = fontSize.sp,
        color = baseColor,
      )
    var measured = measurer.measure(annotated, style = style, softWrap = false, maxLines = 1)
    while ((measured.size.width > maxWidthPx || measured.size.height > maxHeightPx) && fontSize > 28) {
      fontSize -= 2
      style = style.copy(fontSize = fontSize.sp)
      measured = measurer.measure(annotated, style = style, softWrap = false, maxLines = 1)
    }
    val fitsOneLine = measured.size.width <= maxWidthPx && measured.size.height <= maxHeightPx
    if (!fitsOneLine) {
      var multilineSize = fontSize
      var multilineStyle =
        style.copy(
          lineHeight = (multilineSize * 1.15f).sp,
          fontWeight = FontWeight.Normal,
        )
      var multilineMeasurement =
        measurer.measure(
          text = annotated,
          style = multilineStyle,
          overflow = TextOverflow.Clip,
          softWrap = true,
          maxLines = 6,
          constraints =
            Constraints(
              maxWidth = maxWidthPx.toInt(),
              maxHeight = maxHeightPx.toInt(),
            ),
        )
      while (multilineMeasurement.didOverflowHeight && multilineSize > 10) {
        multilineSize -= 1
        multilineStyle =
          multilineStyle.copy(
            fontSize = multilineSize.sp,
            lineHeight = (multilineSize * 1.15f).sp,
          )
        multilineMeasurement =
          measurer.measure(
            text = annotated,
            style = multilineStyle,
            overflow = TextOverflow.Clip,
            softWrap = true,
            maxLines = 6,
            constraints =
              Constraints(
                maxWidth = maxWidthPx.toInt(),
                maxHeight = maxHeightPx.toInt(),
              ),
          )
      }
      Text(
        text = annotated,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        style = multilineStyle,
        maxLines = 6,
        overflow = TextOverflow.Visible,
        softWrap = true,
        textAlign = TextAlign.Start,
      )
      return@BoxWithConstraints
    }
    val prefix =
      measurer.measure(
        AnnotatedString(focus.graphemes.take(focus.focusIndex).joinToString("")),
        style = style,
        softWrap = false,
        maxLines = 1,
      )
    val focusText =
      measurer.measure(
        AnnotatedString(focus.focusGrapheme),
        style = style.copy(color = accent, fontWeight = FontWeight.Bold),
        softWrap = false,
        maxLines = 1,
      )
    Canvas(modifier = Modifier.fillMaxSize()) {
      val targetX = size.width * 0.4f
      val x = targetX - prefix.size.width - focusText.size.width / 2f
      val y = size.height * 0.42f - measured.size.height / 2f
      drawText(
        textLayoutResult = measured,
        topLeft = Offset(x = x.coerceIn(0f, (size.width - measured.size.width).coerceAtLeast(0f)), y = y),
      )
    }
  }
}

@Composable
private fun PauseControls(
  playback: ReaderPlaybackState,
  compact: Boolean,
  onPreviousSentence: () -> Unit,
  onNextSentence: () -> Unit,
  onTogglePlay: () -> Unit,
  onSpeedDecrease: () -> Unit,
  onSpeedIncrease: () -> Unit,
  onOpenSpeed: () -> Unit,
) {
  val speedOpenDescription = stringResource(R.string.reader_speed_open)
  val playDescription = stringResource(R.string.reader_play)
  Column(
    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Row(
      horizontalArrangement = Arrangement.spacedBy(20.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      SquareTextButton(
        text = stringResource(R.string.symbol_previous),
        description = stringResource(R.string.reader_previous_sentence),
        enabled = playback.canPreviousSentence,
        onClick = onPreviousSentence,
      )
      FilledTonalButton(
        onClick = onTogglePlay,
        modifier = Modifier.size(if (compact) 52.dp else 64.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
          containerColor = MaterialTheme.colorScheme.primary,
          contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
      ) {
        Text(
          text = stringResource(R.string.symbol_play),
          style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
          modifier =
            Modifier.semantics {
              contentDescription = playDescription
              role = Role.Button
            },
        )
      }
      SquareTextButton(
        text = stringResource(R.string.symbol_next),
        description = stringResource(R.string.reader_next_sentence),
        enabled = playback.canNextSentence,
        onClick = onNextSentence,
      )
    }
    Row(
      modifier = Modifier.padding(top = if (compact) 4.dp else 10.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      SquareTextButton(
        text = stringResource(R.string.symbol_decrease),
        description = stringResource(R.string.reader_speed_decrease),
        enabled = playback.speedWpm > ReaderDurations.MIN_SPEED_WPM,
        onClick = onSpeedDecrease,
      )
      TextButton(
        onClick = onOpenSpeed,
        modifier = Modifier.semantics { contentDescription = speedOpenDescription },
      ) {
        Text(
          text = stringResource(R.string.reader_speed_value, playback.speedWpm),
          style = MaterialTheme.typography.titleMedium,
        )
      }
      SquareTextButton(
        text = stringResource(R.string.symbol_increase),
        description = stringResource(R.string.reader_speed_increase),
        enabled = playback.speedWpm < ReaderDurations.MAX_SPEED_WPM,
        onClick = onSpeedIncrease,
      )
    }
  }
}

@Composable
private fun SquareTextButton(
  text: String,
  description: String,
  enabled: Boolean = true,
  onClick: () -> Unit,
) {
  TextButton(
    onClick = onClick,
    enabled = enabled,
    modifier =
      Modifier.size(48.dp)
        .semantics {
          contentDescription = description
        },
    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
  ) {
    Text(text, style = MaterialTheme.typography.headlineSmall)
  }
}

@Composable
private fun FirstHint(
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
  horizontal: Boolean,
) {
  Surface(
    modifier = modifier.padding(horizontal = 20.dp, vertical = 2.dp),
    tonalElevation = 3.dp,
    shape = RoundedCornerShape(12.dp),
  ) {
    val contentModifier =
      Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)
    if (horizontal) {
      Row(
        modifier = contentModifier,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        HintText(Modifier.weight(1f))
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.reader_hint_dismiss)) }
      }
    } else {
      Column(
        modifier = contentModifier,
        horizontalAlignment = Alignment.End,
      ) {
        HintText(Modifier.fillMaxWidth())
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.reader_hint_dismiss)) }
      }
    }
  }
}

@Composable
private fun HintText(modifier: Modifier = Modifier) {
  Text(
    text = stringResource(R.string.reader_hint),
    modifier = modifier,
    style = MaterialTheme.typography.bodySmall,
    maxLines = 3,
    overflow = TextOverflow.Ellipsis,
  )
}

@Composable
private fun ReaderProgressFooter(
  playback: ReaderPlaybackState,
  playing: Boolean,
) {
  Column(
    modifier = Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 20.dp),
  ) {
    if (playing) {
      ReaderProgressBar(
        progressPercent = playback.progressPercent,
        height = 2.dp,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
      )
    } else {
      ReaderProgressBar(
        progressPercent = playback.progressPercent,
        height = 4.dp,
        modifier = Modifier.fillMaxWidth(),
      )
      Text(
        text =
          if (playback.chapterNumber != null) {
            stringResource(
              R.string.reader_chapter_of,
              playback.chapterNumber,
              playback.chapterCount,
              playback.progressPercent,
            )
          } else {
            stringResource(R.string.reader_book_percent, playback.progressPercent)
          },
        modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun ReaderProgressBar(
  progressPercent: Int,
  height: Dp,
  modifier: Modifier = Modifier,
) {
  val fraction = (progressPercent / 100f).coerceIn(0f, 1f)
  val progressLabel = stringResource(R.string.library_progress, progressPercent)
  Box(
    modifier =
      modifier
        .height(height)
        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(height))
        .semantics {
          progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
          contentDescription = progressLabel
        },
  ) {
    Box(
      modifier =
        Modifier.fillMaxWidth(fraction)
          .fillMaxHeight()
          .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(height)),
    )
  }
}

@Composable
private fun LoadingReader(showIndicator: Boolean) {
  Column(
    modifier = Modifier.fillMaxSize(),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    if (showIndicator) {
      CircularProgressIndicator()
      Text(
        text = stringResource(R.string.reader_loading),
        modifier = Modifier.padding(top = 16.dp),
      )
    }
  }
}

@Composable
private fun ErrorReader(onBack: () -> Unit) {
  Column(
    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Text(
      text = stringResource(R.string.reader_error_title),
      style = MaterialTheme.typography.headlineSmall,
      textAlign = TextAlign.Center,
    )
    Text(
      text = stringResource(R.string.reader_error_body),
      modifier = Modifier.padding(top = 12.dp),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
    Button(
      onClick = onBack,
      modifier = Modifier.padding(top = 24.dp).height(52.dp),
    ) {
      Text(stringResource(R.string.reader_return_library))
    }
  }
}

@Composable
private fun ChapterBreakReader(
  number: Int?,
  title: String,
  onPlay: () -> Unit,
  onBack: () -> Unit,
) {
  val displayTitle = sectionTitleText(title)
  Column(
    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    number?.let {
      Text(
        text = stringResource(R.string.reader_chapter_break, it),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
      )
    }
    Text(
        text = stringResource(R.string.reader_chapter_break_next, displayTitle),
      modifier = Modifier.padding(top = 12.dp),
      style = MaterialTheme.typography.headlineLarge.copy(fontFamily = FontFamily.Serif),
      textAlign = TextAlign.Center,
      maxLines = 6,
      overflow = TextOverflow.Ellipsis,
    )
    Button(
      onClick = onPlay,
      modifier = Modifier.padding(top = 32.dp).height(52.dp),
    ) {
      Text(stringResource(R.string.reader_play))
    }
    TextButton(onClick = onBack, modifier = Modifier.padding(top = 8.dp)) {
      Text(stringResource(R.string.reader_return_library))
    }
  }
}

@Composable
private fun CompletedReader(
  title: String,
  onRestart: () -> Unit,
  onBack: () -> Unit,
) {
  val displayTitle = bookTitleText(title)
  Column(
    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Text(
      text = stringResource(R.string.reader_completed_title),
      style = MaterialTheme.typography.headlineSmall,
      color = MaterialTheme.colorScheme.primary,
    )
    Text(
      text = displayTitle,
      modifier = Modifier.padding(top = 16.dp),
      style = MaterialTheme.typography.headlineLarge.copy(fontFamily = FontFamily.Serif),
      textAlign = TextAlign.Center,
      maxLines = 4,
      overflow = TextOverflow.Ellipsis,
    )
    Button(
      onClick = onRestart,
      modifier = Modifier.padding(top = 28.dp).height(52.dp),
    ) {
      Text(stringResource(R.string.reader_restart))
    }
    TextButton(onClick = onBack, modifier = Modifier.padding(top = 8.dp)) {
      Text(stringResource(R.string.reader_return_library))
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContentsSheet(
  sections: List<ParsedSection>,
  currentChapterKey: String,
  onSelect: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  val listState = rememberLazyListState()
  val currentIndex = sections.indexOfFirst { it.chapterKey == currentChapterKey }
  LaunchedEffect(currentIndex) {
    if (currentIndex >= 0) listState.scrollToItem(currentIndex)
  }
  ModalBottomSheet(onDismissRequest = onDismiss) {
    Text(
      text = stringResource(R.string.contents_title),
      style = MaterialTheme.typography.titleLarge,
      modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
    )
    LazyColumn(
      state = listState,
      modifier = Modifier.heightIn(max = 520.dp),
      contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
    ) {
      items(sections.size, key = { sections[it].chapterKey }) { index ->
        val section = sections[index]
        val isCurrent = section.chapterKey == currentChapterKey
        Row(
          modifier =
            Modifier.fillMaxWidth()
              .background(
                if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                else Color.Transparent,
              )
              .clickable(enabled = !section.isEmpty) { onSelect(section.chapterKey) }
              .padding(start = (16 + section.level * 18).dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          if (isCurrent) {
            Box(
              modifier =
                Modifier.size(width = 3.dp, height = 28.dp)
                  .background(MaterialTheme.colorScheme.primary),
            )
            Spacer(Modifier.size(9.dp))
          }
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = sectionTitleText(section.title),
              style = MaterialTheme.typography.bodyLarge,
              color =
                if (section.isEmpty) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                else MaterialTheme.colorScheme.onSurface,
            )
            if (section.isEmpty) {
              Text(
                text = stringResource(R.string.contents_empty_section),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
          if (isCurrent) {
            Text(
              text = stringResource(R.string.contents_current),
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.primary,
            )
          }
        }
        HorizontalDivider()
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpeedSheet(
  speed: Int,
  onSpeedChanged: (Int) -> Unit,
  onDismiss: () -> Unit,
) {
  var sliderValue by remember(speed) { mutableFloatStateOf(speed.toFloat()) }
  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(
      modifier =
        Modifier.heightIn(max = 620.dp)
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 24.dp, vertical = 8.dp),
    ) {
      Text(stringResource(R.string.speed_title), style = MaterialTheme.typography.titleLarge)
      Text(
        text = stringResource(R.string.reader_speed_value, sliderValue.toInt()),
        modifier = Modifier.padding(top = 12.dp),
        style = MaterialTheme.typography.headlineSmall,
      )
      Slider(
        value = sliderValue,
        onValueChange = { sliderValue = ReaderDurations.normalizeSpeed(it.toInt()).toFloat() },
        onValueChangeFinished = { onSpeedChanged(sliderValue.toInt()) },
        valueRange = ReaderDurations.MIN_SPEED_WPM.toFloat()..ReaderDurations.MAX_SPEED_WPM.toFloat(),
        steps = 27,
      )
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        for (preset in listOf(200, 300, 400, 500, 750)) {
          TextButton(
            onClick = {
              sliderValue = preset.toFloat()
              onSpeedChanged(preset)
            },
          ) {
            Text(stringResource(R.string.speed_preset, preset))
          }
        }
      }
      Text(
        text = stringResource(R.string.speed_above_recommended),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(Modifier.height(24.dp))
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotesSheet(
  blocks: List<String>,
  onDismiss: () -> Unit,
) {
  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(
      modifier =
        Modifier.heightIn(max = 560.dp)
          .padding(horizontal = 24.dp, vertical = 8.dp)
          .verticalScroll(rememberScrollState()),
    ) {
      Text(stringResource(R.string.notes_title), style = MaterialTheme.typography.titleLarge)
      blocks.forEach { block ->
        Text(
          text = block,
          modifier = Modifier.padding(top = 12.dp),
          style = MaterialTheme.typography.bodyLarge,
        )
      }
      Spacer(Modifier.height(24.dp))
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionsSheet(
  onRestart: () -> Unit,
  onSettings: () -> Unit,
  onInfo: () -> Unit,
  onDismiss: () -> Unit,
) {
  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(
      modifier =
        Modifier.fillMaxWidth()
          .heightIn(max = 620.dp)
          .verticalScroll(rememberScrollState())
          .padding(vertical = 8.dp),
    ) {
      Text(
        text = stringResource(R.string.actions_title),
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
      )
      SheetAction(stringResource(R.string.book_menu_restart), onRestart)
      SheetAction(stringResource(R.string.actions_reading_settings), onSettings)
      SheetAction(stringResource(R.string.actions_book_info), onInfo)
      Spacer(Modifier.height(16.dp))
    }
  }
}

@Composable
private fun SheetAction(
  label: String,
  onClick: () -> Unit,
) {
  TextButton(
    onClick = onClick,
    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
  ) {
    Text(label, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
  }
}

@Composable
private fun ReaderBookInfoDialog(
  info: BookInfo,
  onDismiss: () -> Unit,
) {
  val context = LocalContext.current
  val locale = LocalConfiguration.current.locales[0]
  val title = bookTitleText(info.title)
  val author = bookAuthorText(info.authorDisplay)
  val date =
    DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(Date(info.importedAt))
  val size = Formatter.formatShortFileSize(context, info.sizeBytes)
  val words = NumberFormat.getIntegerInstance(locale).format(info.wordCount)
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.book_info_title)) },
    text = {
      Column(
        modifier =
          Modifier.heightIn(max = 520.dp)
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
        val codes = info.warnings.map { it.code }.distinct()
        HorizontalDivider()
        Text(stringResource(R.string.book_info_warnings), style = MaterialTheme.typography.titleSmall)
        if (codes.isEmpty()) {
          Text(stringResource(R.string.book_info_no_warnings))
        } else {
          codes.forEach {
            Text(stringResource(warningTextResource(it)), style = MaterialTheme.typography.bodySmall)
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
    },
  )
}

private fun android.content.Context.findActivity(): Activity? {
  var current = this
  while (current is android.content.ContextWrapper) {
    if (current is Activity) return current
    current = current.baseContext
  }
  return current as? Activity
}
