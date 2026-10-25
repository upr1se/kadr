package com.upr1se.kadr.data.storage

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

object Hashing {
  fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

  fun sha256(text: String): String = sha256(text.toByteArray(Charsets.UTF_8))

  fun sha256(file: File): String =
    file.inputStream().use { input ->
      val digest = MessageDigest.getInstance("SHA-256")
      val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
      while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        if (read > 0) digest.update(buffer, 0, read)
      }
      digest.digest().toHex()
    }

  fun fingerprint(text: String): String {
    val normalized = text.toByteArray(Charsets.UTF_8)
    return MessageDigest.getInstance("SHA-256").digest(normalized).copyOf(16).toHex()
  }
}

fun ByteArray.toHex(): String =
  joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xFF) }

class HashingInputStream(
  private val delegate: InputStream,
) : InputStream() {
  private val digest = MessageDigest.getInstance("SHA-256")
  private var finished = false
  var hash: String? = null
    private set

  override fun read(): Int {
    val value = delegate.read()
    if (value < 0) {
      finish()
    } else {
      digest.update(value.toByte())
    }
    return value
  }

  override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
    val read = delegate.read(buffer, offset, length)
    if (read < 0) {
      finish()
    } else if (read > 0) {
      digest.update(buffer, offset, read)
    }
    return read
  }

  override fun close() {
    delegate.close()
  }

  private fun finish() {
    if (!finished) {
      finished = true
      hash = digest.digest().toHex()
    }
  }
}
