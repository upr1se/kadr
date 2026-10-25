package com.upr1se.kadr.data.storage

import android.os.StatFs
import java.io.File

fun interface AvailableSpace {
  fun availableBytes(directory: File): Long
}

class AndroidAvailableSpace : AvailableSpace {
  override fun availableBytes(directory: File): Long {
    val stat = StatFs(directory.absolutePath)
    return stat.availableBytes
  }
}

class FixedAvailableSpace(
  private val bytes: Long,
) : AvailableSpace {
  override fun availableBytes(directory: File): Long = bytes
}
