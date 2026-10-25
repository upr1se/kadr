package com.upr1se.kadr.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.upr1se.kadr.R
import com.upr1se.kadr.data.settings.AppSettings
import com.upr1se.kadr.data.settings.SettingsRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
  settings: AppSettings,
  versionName: String,
  onBack: () -> Unit,
  onThemeSelected: (String) -> Unit,
  onWordSizeChanged: (Int) -> Unit,
  onDefaultSpeedChanged: (Int) -> Unit,
  onShowSentenceChanged: (Boolean) -> Unit,
  onChapterAutoPauseChanged: (Boolean) -> Unit,
  saveFailure: Boolean,
  onSaveFailureShown: () -> Unit,
) {
  val snackbarHostState = remember { SnackbarHostState() }
  var wordSize by remember(settings.wordSizeSp) { mutableIntStateOf(settings.wordSizeSp) }
  var defaultSpeed by remember(settings.defaultSpeedWpm) {
    mutableIntStateOf(settings.defaultSpeedWpm)
  }
  val failureMessage = stringResource(R.string.settings_save_failed)
  LaunchedEffect(saveFailure) {
    if (saveFailure) {
      snackbarHostState.showSnackbar(failureMessage)
      onSaveFailureShown()
    }
  }
  Scaffold(
    modifier = Modifier.fillMaxSize(),
    snackbarHost = { SnackbarHost(snackbarHostState) },
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.settings_title)) },
        navigationIcon = {
          TextButton(onClick = onBack) {
            Text(
              text = stringResource(R.string.symbol_back),
              style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.back))
          }
        },
      )
    },
  ) { padding ->
    Column(
      modifier =
        Modifier.fillMaxSize()
          .padding(padding)
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 24.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(
        text = stringResource(R.string.settings_theme),
        style = MaterialTheme.typography.titleMedium,
      )
      ThemeOption(
        label = stringResource(R.string.settings_theme_system),
        selected = settings.theme == SettingsRepository.THEME_SYSTEM,
        onSelect = { onThemeSelected(SettingsRepository.THEME_SYSTEM) },
      )
      ThemeOption(
        label = stringResource(R.string.settings_theme_light),
        selected = settings.theme == SettingsRepository.THEME_LIGHT,
        onSelect = { onThemeSelected(SettingsRepository.THEME_LIGHT) },
      )
      ThemeOption(
        label = stringResource(R.string.settings_theme_dark),
        selected = settings.theme == SettingsRepository.THEME_DARK,
        onSelect = { onThemeSelected(SettingsRepository.THEME_DARK) },
      )
      ThemeOption(
        label = stringResource(R.string.settings_theme_sepia),
        selected = settings.theme == SettingsRepository.THEME_SEPIA,
        onSelect = { onThemeSelected(SettingsRepository.THEME_SEPIA) },
      )
      HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
      Text(
        text = stringResource(R.string.settings_word_size),
        style = MaterialTheme.typography.titleMedium,
      )
      Text(
        text = stringResource(R.string.settings_word_size_value, wordSize),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Slider(
        value = wordSize.toFloat(),
        onValueChange = { wordSize = it.toInt() },
        onValueChangeFinished = { onWordSizeChanged(wordSize) },
        valueRange = 36f..72f,
        steps = 35,
      )
      HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
      Text(
        text = stringResource(R.string.settings_default_speed),
        style = MaterialTheme.typography.titleMedium,
      )
      Text(
        text = stringResource(R.string.reader_speed_value, defaultSpeed),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Slider(
        value = defaultSpeed.toFloat(),
        onValueChange = { defaultSpeed = SettingsRepository.normalizeSpeed(it.toInt()) },
        onValueChangeFinished = { onDefaultSpeedChanged(defaultSpeed) },
        valueRange =
          SettingsRepository.MIN_SPEED_WPM.toFloat()..SettingsRepository.MAX_SPEED_WPM.toFloat(),
        steps = 27,
      )
      HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
      SettingSwitch(
        label = stringResource(R.string.settings_show_sentence),
        checked = settings.showSentenceOnPause,
        onCheckedChange = onShowSentenceChanged,
      )
      SettingSwitch(
        label = stringResource(R.string.settings_chapter_pause),
        checked = settings.chapterAutoPause,
        onCheckedChange = onChapterAutoPauseChanged,
      )
      Text(
        text = stringResource(R.string.settings_version, versionName),
        modifier = Modifier.padding(top = 24.dp, bottom = 24.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun ThemeOption(
  label: String,
  selected: Boolean,
  onSelect: () -> Unit,
) {
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
        .padding(vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    RadioButton(selected = selected, onClick = null)
    Text(label, modifier = Modifier.padding(start = 8.dp))
  }
}

@Composable
private fun SettingSwitch(
  label: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
) {
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .selectable(selected = checked, onClick = { onCheckedChange(!checked) }, role = Role.Switch)
        .padding(vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(label, modifier = Modifier.weight(1f))
    Switch(checked = checked, onCheckedChange = onCheckedChange)
  }
}

@Composable
fun SettingsRoute(
  viewModel: SettingsViewModel,
  onBack: () -> Unit,
) {
  val settings by viewModel.settings.collectAsStateWithLifecycle()
  var saveFailure by remember { mutableStateOf(false) }
  LaunchedEffect(viewModel) {
    viewModel.saveFailures.collect { saveFailure = true }
  }
  SettingsScreen(
    settings = settings,
    versionName = viewModel.versionName,
    onBack = onBack,
    onThemeSelected = viewModel::setTheme,
    onWordSizeChanged = viewModel::setWordSize,
    onDefaultSpeedChanged = viewModel::setDefaultSpeed,
    onShowSentenceChanged = viewModel::setShowSentence,
    onChapterAutoPauseChanged = viewModel::setChapterAutoPause,
    saveFailure = saveFailure,
    onSaveFailureShown = { saveFailure = false },
  )
}
