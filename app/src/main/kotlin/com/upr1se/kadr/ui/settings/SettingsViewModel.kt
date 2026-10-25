package com.upr1se.kadr.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.upr1se.kadr.data.settings.AppSettings
import com.upr1se.kadr.data.settings.SettingsRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
  private val repository: SettingsRepository,
  val versionName: String,
) : ViewModel() {
  val settings: StateFlow<AppSettings> =
    repository.settings.stateIn(
      scope = viewModelScope,
      started = kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000),
      initialValue = AppSettings(),
    )

  private val eventChannel = Channel<Unit>(Channel.CONFLATED)
  val saveFailures = eventChannel.receiveAsFlow()

  fun setTheme(value: String) = update { repository.setTheme(value) }

  fun setWordSize(value: Int) = update { repository.setWordSize(value) }

  fun setDefaultSpeed(value: Int) = update { repository.setDefaultSpeed(value) }

  fun setShowSentence(value: Boolean) = update { repository.setShowSentenceOnPause(value) }

  fun setChapterAutoPause(value: Boolean) = update { repository.setChapterAutoPause(value) }

  private fun update(block: suspend () -> Unit) {
    viewModelScope.launch {
      runCatching { block() }.onFailure { eventChannel.trySend(Unit) }
    }
  }
}
