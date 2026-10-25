package com.upr1se.kadr.ui.library

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.material3.SnackbarHostState
import com.upr1se.kadr.data.storage.DuplicateDecision
import androidx.test.platform.app.InstrumentationRegistry
import com.upr1se.kadr.R
import com.upr1se.kadr.ui.theme.KadrTheme
import org.junit.Rule
import org.junit.Test

class LibraryScreenTest {
  @get:Rule val composeRule = createComposeRule()

  @Test
  fun emptyLibraryShowsTitleAndMessage() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext

    composeRule.setContent {
      KadrTheme {
        LibraryScreen(
          state = LibraryScreenState(isLoading = false),
          snackbarHostState = SnackbarHostState(),
          onOpenSettings = {},
          onAddBook = {},
          onOpenBook = {},
          onDeleteRequested = {},
          onResetRequested = {},
          onInfoRequested = {},
          onDismissDialog = {},
          onConfirmDelete = {},
          onConfirmReset = {},
          onZipSelected = {},
          onDuplicateDecision = {},
          onCancelImport = {},
        )
      }
    }

    composeRule.onNodeWithText(context.getString(R.string.library_title)).assertIsDisplayed()
    composeRule
      .onNodeWithText(context.getString(R.string.library_empty_title))
      .assertIsDisplayed()
  }
}
