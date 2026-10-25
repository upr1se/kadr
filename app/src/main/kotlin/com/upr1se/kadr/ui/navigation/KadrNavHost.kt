package com.upr1se.kadr.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.upr1se.kadr.app.AppContainer
import com.upr1se.kadr.app.KadrApplication
import com.upr1se.kadr.ui.library.LibraryNoticeKind
import com.upr1se.kadr.ui.library.LibraryRoute
import com.upr1se.kadr.ui.library.LibraryViewModel
import com.upr1se.kadr.ui.reader.ReaderRoute
import com.upr1se.kadr.ui.reader.ReaderViewModel
import com.upr1se.kadr.ui.settings.SettingsRoute
import com.upr1se.kadr.ui.settings.SettingsViewModel

private object Routes {
  const val LIBRARY = "library"
  const val READER = "reader/{bookId}"
  const val SETTINGS = "settings?origin={origin}"

  fun reader(bookId: String): String = "reader/${Uri.encode(bookId)}"

  fun settings(origin: String): String = "settings?origin=$origin"
}

@Composable
fun KadrNavHost(container: AppContainer) {
  val navController = rememberNavController()
  val context = LocalContext.current
  var libraryNoticeName by rememberSaveable { mutableStateOf<String?>(null) }
  val libraryNotice = libraryNoticeName?.let(LibraryNoticeKind::valueOf)

  NavHost(
    navController = navController,
    startDestination = Routes.LIBRARY,
  ) {
    composable(Routes.LIBRARY) {
      val libraryViewModel: LibraryViewModel =
        viewModel(
          factory =
            viewModelFactory {
              initializer {
                LibraryViewModel(
                  context = context.applicationContext,
                  repository = container.bookRepository,
                  importCoordinator = container.importCoordinator,
                  intentImportQueue = container.intentImportQueue,
                )
              }
            },
        )
      LibraryRoute(
        viewModel = libraryViewModel,
        onOpenBook = { navController.navigate(Routes.reader(it)) },
        onOpenSettings = { navController.navigate(Routes.settings("library")) },
        externalNotice = libraryNotice,
        onExternalNoticeShown = { libraryNoticeName = null },
      )
    }
    composable(Routes.READER) { backStackEntry ->
      val readerViewModel: ReaderViewModel =
        viewModel(
          viewModelStoreOwner = backStackEntry,
          factory =
            viewModelFactory {
              initializer {
                ReaderViewModel(
                  savedStateHandle = createSavedStateHandle(),
                  repository = container.bookRepository,
                  settingsRepository = container.settingsRepository,
                )
              }
            },
        )
      ReaderRoute(
        viewModel = readerViewModel,
        onBackToLibrary = {
          navController.popBackStack(Routes.LIBRARY, inclusive = false)
        },
        onOpenSettings = {
          navController.navigate(Routes.settings("reader"))
        },
        onMissingBook = {
          libraryNoticeName = LibraryNoticeKind.BOOK_OPEN_FAILED.name
          navController.popBackStack(Routes.LIBRARY, inclusive = false)
        },
      )
    }
    composable(Routes.SETTINGS) {
      val settingsViewModel: SettingsViewModel =
        viewModel(
          factory =
            viewModelFactory {
              initializer {
                SettingsViewModel(
                  repository = container.settingsRepository,
                  versionName = container.versionName,
                )
              }
            },
        )
      SettingsRoute(
        viewModel = settingsViewModel,
        onBack = { navController.popBackStack() },
      )
    }
  }
}
