package com.upr1se.kadr.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

data class AppSettings(
  val theme: String = SettingsRepository.THEME_SYSTEM,
  val wordSizeSp: Int = SettingsRepository.DEFAULT_WORD_SIZE_SP,
  val defaultSpeedWpm: Int = SettingsRepository.DEFAULT_SPEED_WPM,
  val showSentenceOnPause: Boolean = true,
  val chapterAutoPause: Boolean = true,
  val firstReaderHintShown: Boolean = false,
)

class SettingsRepository(
  private val dataStore: DataStore<Preferences>,
) {
  val settings: Flow<AppSettings> =
    dataStore.data.map { preferences ->
      AppSettings(
        theme = preferences[THEME] ?: THEME_SYSTEM,
        wordSizeSp = (preferences[WORD_SIZE] ?: DEFAULT_WORD_SIZE_SP).coerceIn(36, 72),
        defaultSpeedWpm = normalizeSpeed(preferences[DEFAULT_SPEED] ?: DEFAULT_SPEED_WPM),
        showSentenceOnPause = preferences[SHOW_SENTENCE] ?: true,
        chapterAutoPause = preferences[CHAPTER_AUTO_PAUSE] ?: true,
        firstReaderHintShown = preferences[FIRST_HINT_SHOWN] ?: false,
      )
    }

  suspend fun setTheme(theme: String) {
    require(theme in THEMES)
    dataStore.edit { it[THEME] = theme }
  }

  suspend fun setWordSize(sizeSp: Int) {
    dataStore.edit { it[WORD_SIZE] = sizeSp.coerceIn(36, 72) }
  }

  suspend fun setDefaultSpeed(wpm: Int) {
    dataStore.edit { it[DEFAULT_SPEED] = normalizeSpeed(wpm) }
  }

  suspend fun setShowSentenceOnPause(enabled: Boolean) {
    dataStore.edit { it[SHOW_SENTENCE] = enabled }
  }

  suspend fun setChapterAutoPause(enabled: Boolean) {
    dataStore.edit { it[CHAPTER_AUTO_PAUSE] = enabled }
  }

  suspend fun markFirstReaderHintShown() {
    dataStore.edit { it[FIRST_HINT_SHOWN] = true }
  }

  companion object {
    const val THEME_SYSTEM = "SYSTEM"
    const val THEME_LIGHT = "LIGHT"
    const val THEME_DARK = "DARK"
    const val THEME_SEPIA = "SEPIA"
    val THEMES = setOf(THEME_SYSTEM, THEME_LIGHT, THEME_DARK, THEME_SEPIA)
    const val DEFAULT_WORD_SIZE_SP = 52
    const val DEFAULT_SPEED_WPM = 300
    const val MIN_SPEED_WPM = 100
    const val MAX_SPEED_WPM = 800
    const val SPEED_STEP_WPM = 25

    fun normalizeSpeed(value: Int): Int {
      val clamped = value.coerceIn(MIN_SPEED_WPM, MAX_SPEED_WPM)
      return ((clamped.toDouble() / SPEED_STEP_WPM).roundToInt() * SPEED_STEP_WPM)
        .coerceIn(MIN_SPEED_WPM, MAX_SPEED_WPM)
    }

    private val THEME = stringPreferencesKey("theme")
    private val WORD_SIZE = intPreferencesKey("word_size_sp")
    private val DEFAULT_SPEED = intPreferencesKey("default_speed_wpm")
    private val SHOW_SENTENCE = booleanPreferencesKey("show_sentence_on_pause")
    private val CHAPTER_AUTO_PAUSE = booleanPreferencesKey("chapter_auto_pause")
    private val FIRST_HINT_SHOWN = booleanPreferencesKey("first_reader_hint_shown")
  }
}
