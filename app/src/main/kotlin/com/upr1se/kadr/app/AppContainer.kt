package com.upr1se.kadr.app

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import com.upr1se.kadr.data.db.RoomBookCatalog
import com.upr1se.kadr.data.db.KadrDatabase
import com.upr1se.kadr.data.settings.SettingsRepository
import com.upr1se.kadr.data.storage.AndroidAvailableSpace
import com.upr1se.kadr.data.storage.AndroidCoverProcessor
import com.upr1se.kadr.data.storage.BookFileStore
import com.upr1se.kadr.data.storage.BookRepository
import com.upr1se.kadr.data.storage.ImportCoordinator
import com.upr1se.kadr.data.storage.RecoveryReport
import com.upr1se.kadr.fb2.Fb2Parser
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

class AppContainer(context: Context) {
  private val appContext: Context = context.applicationContext
  val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  val intentImportQueue = IntentImportQueue()
  val versionName: String =
    runCatching {
      appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
    }.getOrNull().orEmpty()

  private val database: KadrDatabase =
    Room.databaseBuilder(appContext, KadrDatabase::class.java, "kadr.db").build()
  private val catalog = RoomBookCatalog(database.bookDao())
  private val fb2Parser = Fb2Parser()
  val fileStore =
    BookFileStore(
      filesDir = File(appContext.filesDir.absolutePath),
      availableSpace = AndroidAvailableSpace(),
      coverProcessor = AndroidCoverProcessor(),
    )
  val importCoordinator = ImportCoordinator(fileStore, fb2Parser, catalog)
  val bookRepository = BookRepository(catalog, fileStore, fb2Parser)
  val settingsRepository =
    SettingsRepository(
      PreferenceDataStoreFactory.create(
        scope = applicationScope,
        produceFile = { appContext.preferencesDataStoreFile("settings") },
      ),
    )

  val startupRecovery: Deferred<RecoveryReport> =
    applicationScope.async { importCoordinator.recoverOnStartup() }
}
