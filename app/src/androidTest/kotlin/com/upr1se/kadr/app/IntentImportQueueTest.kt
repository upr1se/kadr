package com.upr1se.kadr.app

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IntentImportQueueTest {
  private val first = Uri.parse("content://provider/first.fb2")
  private val second = Uri.parse("content://provider/second.fb2")

  @Test
  fun actionViewUsesItsSingleDataUri() {
    val queue = IntentImportQueue()
    queue.submit(Intent(Intent.ACTION_VIEW, first))

    assertEquals(first, queue.requests.value?.uri)
    assertNull(queue.requests.value?.error)
  }

  @Test
  fun actionSendAcceptsOneStreamAndRejectsSeveralStreams() {
    val queue = IntentImportQueue()
    queue.submit(
      Intent(Intent.ACTION_SEND).apply {
        type = "application/octet-stream"
        putExtra(Intent.EXTRA_STREAM, first)
        clipData = ClipData.newRawUri("book", first)
      },
    )
    assertEquals(first, queue.requests.value?.uri)

    queue.submit(
      Intent(Intent.ACTION_SEND).apply {
        type = "application/octet-stream"
        clipData =
          ClipData(
            "books",
            arrayOf("text/uri-list"),
            ClipData.Item(first),
          ).apply {
            addItem(ClipData.Item(second))
          }
      },
    )
    assertEquals(IntentImportError.MULTIPLE_STREAMS, queue.requests.value?.error)
  }

  @Test
  fun missingOrUnrelatedIntentsDoNotCreateImports() {
    val queue = IntentImportQueue()
    queue.submit(Intent(Intent.ACTION_VIEW))
    assertEquals(IntentImportError.NO_STREAM, queue.requests.value?.error)

    queue.consume(checkNotNull(queue.requests.value).id)
    queue.submit(Intent(Intent.ACTION_MAIN))
    assertNull(queue.requests.value)
  }
}
