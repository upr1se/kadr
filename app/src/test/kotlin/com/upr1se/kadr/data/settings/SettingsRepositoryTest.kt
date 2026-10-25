package com.upr1se.kadr.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsRepositoryTest {
  @get:Rule val temporaryFolder = TemporaryFolder()

  @Test
  fun settingsPersistAndValuesAreNormalized() = runTest {
    val file = temporaryFolder.newFile("settings.preferences_pb")
    file.delete()
    val store = PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { file })
    val repository = SettingsRepository(store)

    repository.setTheme(SettingsRepository.THEME_SEPIA)
    repository.setWordSize(81)
    repository.setDefaultSpeed(813)
    repository.setShowSentenceOnPause(false)
    repository.setChapterAutoPause(false)
    repository.markFirstReaderHintShown()

    val settings = repository.settings.first()
    assertEquals(SettingsRepository.THEME_SEPIA, settings.theme)
    assertEquals(72, settings.wordSizeSp)
    assertEquals(800, settings.defaultSpeedWpm)
    assertFalse(settings.showSentenceOnPause)
    assertFalse(settings.chapterAutoPause)
    assertTrue(settings.firstReaderHintShown)
  }

  @Test
  fun speedNormalizationUsesNearestStepAndBounds() {
    assertEquals(100, SettingsRepository.normalizeSpeed(87))
    assertEquals(175, SettingsRepository.normalizeSpeed(183))
    assertEquals(325, SettingsRepository.normalizeSpeed(313))
    assertEquals(800, SettingsRepository.normalizeSpeed(999))
  }
}
