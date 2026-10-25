package com.upr1se.kadr.app

import android.content.Intent
import android.net.Uri
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class IntentImportRequest(
  val id: String,
  val uri: Uri?,
  val error: IntentImportError?,
)

enum class IntentImportError {
  NO_STREAM,
  MULTIPLE_STREAMS,
}

class IntentImportQueue {
  private val _requests = MutableStateFlow<IntentImportRequest?>(null)
  val requests: StateFlow<IntentImportRequest?> = _requests.asStateFlow()

  fun submit(intent: Intent?) {
    if (intent == null) return
    val request =
      when (intent.action) {
        Intent.ACTION_VIEW -> {
          val uri = intent.data
          if (uri == null) {
            IntentImportRequest(UUID.randomUUID().toString(), null, IntentImportError.NO_STREAM)
          } else {
            IntentImportRequest(UUID.randomUUID().toString(), uri, null)
          }
        }
        Intent.ACTION_SEND -> {
          val uris = linkedSetOf<Uri>()
          intent.data?.let(uris::add)
          @Suppress("DEPRECATION")
          (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let(uris::add)
          val clip = intent.clipData
          if (clip != null) {
            for (index in 0 until clip.itemCount) {
              clip.getItemAt(index).uri?.let(uris::add)
            }
          }
          when (uris.size) {
            0 -> IntentImportRequest(UUID.randomUUID().toString(), null, IntentImportError.NO_STREAM)
            1 -> IntentImportRequest(UUID.randomUUID().toString(), uris.first(), null)
            else ->
              IntentImportRequest(
                UUID.randomUUID().toString(),
                null,
                IntentImportError.MULTIPLE_STREAMS,
              )
          }
        }
        else -> return
      }
    _requests.value = request
  }

  fun consume(id: String) {
    if (_requests.value?.id == id) _requests.value = null
  }
}
