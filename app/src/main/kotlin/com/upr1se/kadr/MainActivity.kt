package com.upr1se.kadr

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.upr1se.kadr.app.AppContainer
import com.upr1se.kadr.app.KadrApplication
import com.upr1se.kadr.data.settings.AppSettings
import com.upr1se.kadr.data.settings.SettingsRepository
import com.upr1se.kadr.ui.navigation.KadrNavHost
import com.upr1se.kadr.ui.theme.KadrTheme
import com.upr1se.kadr.ui.theme.KadrThemeMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
  private lateinit var container: AppContainer

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    container = (application as KadrApplication).container
    if (savedInstanceState == null) {
      container.intentImportQueue.submit(intent)
    }
    enableEdgeToEdge()
    setContent {
      val settings by
        container.settingsRepository.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
      KadrTheme(themeMode = settings.theme.toThemeMode()) {
        AppStartupGate(container)
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    container.intentImportQueue.submit(intent)
  }
}

@androidx.compose.runtime.Composable
private fun AppStartupGate(container: AppContainer) {
  var recovered by remember { mutableStateOf(container.startupRecovery.isCompleted) }
  var showIndicator by remember { mutableStateOf(false) }
  LaunchedEffect(container) {
    if (!recovered) {
      val indicatorJob =
        launch {
          delay(300)
          showIndicator = true
        }
      runCatching { container.startupRecovery.await() }
      indicatorJob.cancel()
      recovered = true
    }
  }
  if (recovered) {
    KadrNavHost(container)
  } else {
    Column(
      modifier = Modifier.fillMaxSize().padding(32.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
    ) {
      if (showIndicator) {
        CircularProgressIndicator()
        Text(
          text = stringResource(R.string.startup_loading),
          modifier = Modifier.padding(top = 16.dp),
          style = MaterialTheme.typography.bodyLarge,
        )
      }
    }
  }
}

private fun String.toThemeMode(): KadrThemeMode =
  when (this) {
    SettingsRepository.THEME_LIGHT -> KadrThemeMode.LIGHT
    SettingsRepository.THEME_DARK -> KadrThemeMode.DARK
    SettingsRepository.THEME_SEPIA -> KadrThemeMode.SEPIA
    else -> KadrThemeMode.SYSTEM
  }
