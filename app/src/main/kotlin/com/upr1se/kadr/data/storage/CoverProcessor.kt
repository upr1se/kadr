package com.upr1se.kadr.data.storage

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.graphics.scale
import java.io.ByteArrayOutputStream
import kotlin.math.max

fun interface CoverBytesProcessor {
  fun toWebp(source: ByteArray): ByteArray?
}

class AndroidCoverProcessor(
  private val maxLongSide: Int = 1_200,
  private val maxBytes: Int = 2 * 1024 * 1024,
  private val maxDimension: Int = 4_096,
  private val maxPixels: Long = 16_000_000,
) : CoverBytesProcessor {
  override fun toWebp(source: ByteArray): ByteArray? {
    val bounds =
      BitmapFactory.Options().apply {
        inJustDecodeBounds = true
      }
    BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
    if (
      bounds.outWidth <= 0 ||
        bounds.outHeight <= 0 ||
        bounds.outWidth > maxDimension ||
        bounds.outHeight > maxDimension ||
        bounds.outWidth.toLong() * bounds.outHeight > maxPixels
    ) {
      return null
    }
    val decodeOptions =
      BitmapFactory.Options().apply {
        inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxLongSide)
        inPreferredConfig = Bitmap.Config.ARGB_8888
      }
    val decoded =
      try {
        BitmapFactory.decodeByteArray(source, 0, source.size, decodeOptions)
      } catch (_: OutOfMemoryError) {
        null
      } ?: return null
    val scaled = scaleToLimit(decoded)
    try {
      val qualities = intArrayOf(82, 72, 60)
      for (quality in qualities) {
        val bytes =
          ByteArrayOutputStream().use { output ->
            scaled.compress(webpFormat(), quality, output)
            output.toByteArray()
          }
        if (bytes.size <= maxBytes) return bytes
      }
      return null
    } finally {
      if (scaled !== decoded) scaled.recycle()
      decoded.recycle()
    }
  }

  private fun scaleToLimit(bitmap: Bitmap): Bitmap {
    val longSide = max(bitmap.width, bitmap.height)
    if (longSide <= maxLongSide) return bitmap
    val scale = maxLongSide.toFloat() / longSide
    val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
    val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
    return bitmap.scale(width, height)
  }

  private fun sampleSize(width: Int, height: Int, target: Int): Int {
    var sample = 1
    while (max(width / sample, height / sample) > target * 2) {
      sample *= 2
    }
    return sample
  }

  @Suppress("DEPRECATION")
  private fun webpFormat(): Bitmap.CompressFormat =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      Bitmap.CompressFormat.WEBP_LOSSY
    } else {
      Bitmap.CompressFormat.WEBP
    }
}
