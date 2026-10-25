package com.upr1se.kadr.data.storage

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.FileInputStream
import java.io.InputStream

interface ImportSource {
  val displayName: String
  val sizeHint: Long?
  fun openStream(): InputStream
}

class ContentUriImportSource(
  private val context: Context,
  private val uri: Uri,
) : ImportSource {
  override val displayName: String by lazy {
    queryMetadata()?.first.orEmpty().takeIf { it.isNotBlank() } ?: "book.fb2"
  }
  override val sizeHint: Long? by lazy { queryMetadata()?.second }

  override fun openStream(): InputStream =
    context.contentResolver.openInputStream(uri)
      ?: throw StorageException(StorageErrorCode.SOURCE_UNAVAILABLE)

  private fun queryMetadata(): Pair<String?, Long?>? {
    val cursor =
      runCatching {
        context.contentResolver.query(
          uri,
          arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
          null,
          null,
          null,
        )
      }.getOrNull() ?: return null
    cursor.use {
      if (!it.moveToFirst()) return null
      val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
      val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
      val name = if (nameIndex >= 0 && !it.isNull(nameIndex)) it.getString(nameIndex) else null
      val size = if (sizeIndex >= 0 && !it.isNull(sizeIndex)) it.getLong(sizeIndex) else null
      return name to size
    }
  }
}

class FileImportSource(
  private val file: java.io.File,
  override val displayName: String = file.name,
  override val sizeHint: Long? = file.length(),
) : ImportSource {
  override fun openStream(): InputStream = FileInputStream(file)
}

class ByteArrayImportSource(
  override val displayName: String,
  private val bytes: ByteArray,
) : ImportSource {
  override val sizeHint: Long = bytes.size.toLong()

  override fun openStream(): InputStream = bytes.inputStream()
}
