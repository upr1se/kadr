package com.upr1se.kadr.data.storage

import com.upr1se.kadr.fb2.BookFormat
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class StorageManifest(
  val schemaVersion: Int,
  val state: String,
  val bookId: String,
  val parserVersion: Int,
  val indexVersion: Int,
  val layoutVersion: Int,
  val format: String,
  val archiveEntryPath: String?,
  val archiveEntryIndex: Int?,
  val sourceHash: String,
  val contentHash: String,
  val files: List<ManifestFile>,
  val summary: ManifestSummary,
)

@Serializable
data class ManifestFile(
  val name: String,
  val role: String,
  val size: Long,
  val sha256: String,
)

@Serializable
data class ManifestSummary(
  val wordCount: Int,
  val itemCount: Int,
  val sentenceCount: Int,
  val chapterCount: Int,
)

object ManifestCodec {
  const val SCHEMA_VERSION = 1
  const val COMMITTED = "COMMITTED"
  const val MAX_MANIFEST_BYTES = 1024 * 1024

  private val json =
    Json {
      ignoreUnknownKeys = true
      encodeDefaults = true
      explicitNulls = true
    }

  fun encode(manifest: StorageManifest): ByteArray {
    val bytes = json.encodeToString(StorageManifest.serializer(), manifest).toByteArray(Charsets.UTF_8)
    if (bytes.size > MAX_MANIFEST_BYTES) {
      throw StorageException(StorageErrorCode.INVALID_MANIFEST)
    }
    return bytes
  }

  fun decode(bytes: ByteArray): StorageManifest {
    if (bytes.size > MAX_MANIFEST_BYTES) {
      throw StorageException(StorageErrorCode.INVALID_MANIFEST)
    }
    return try {
      json.decodeFromString(StorageManifest.serializer(), bytes.toString(Charsets.UTF_8))
    } catch (exception: Exception) {
      throw StorageException(StorageErrorCode.INVALID_MANIFEST, exception)
    }
  }

  fun read(folder: File): StorageManifest =
    decode(File(folder, MANIFEST_FILE).readBytes())

  fun validate(manifest: StorageManifest, folder: File) {
    if (
      manifest.schemaVersion != SCHEMA_VERSION ||
        manifest.state != COMMITTED ||
        manifest.parserVersion <= 0 ||
        manifest.indexVersion <= 0 ||
        manifest.layoutVersion <= 0 ||
        runCatching { BookFormat.valueOf(manifest.format) }.isFailure ||
        manifest.sourceHash.length != 64 ||
        manifest.contentHash.length != 64 ||
        manifest.files.isEmpty() ||
        manifest.summary.wordCount <= 0 ||
        manifest.summary.itemCount < manifest.summary.wordCount
    ) {
      throw StorageException(StorageErrorCode.INVALID_MANIFEST)
    }
    if (manifest.archiveEntryPath != null && !isSafeArchivePath(manifest.archiveEntryPath)) {
      throw StorageException(StorageErrorCode.INVALID_MANIFEST)
    }
    val roles = mutableSetOf<String>()
    for (file in manifest.files) {
      if (
        !isSafeFlatFileName(file.name) ||
          file.role !in ALLOWED_ROLES ||
          !roles.add(file.role) ||
          file.size < 0 ||
          file.sha256.length != 64
      ) {
        throw StorageException(StorageErrorCode.INVALID_MANIFEST)
      }
      val actual = File(folder, file.name)
      if (!actual.isFile || actual.length() != file.size) {
        if (file.role != "cover") {
          throw StorageException(StorageErrorCode.MISSING_FILE)
        }
      }
    }
    if (
      "source" !in roles ||
        "reading_text" !in roles ||
        "reading_index" !in roles ||
        "book_json" !in roles
    ) {
      throw StorageException(StorageErrorCode.INVALID_MANIFEST)
    }
  }

  fun sourceFileName(manifest: StorageManifest): String =
    manifest.files.first { it.role == "source" }.name

  fun coverFileName(manifest: StorageManifest): String? =
    manifest.files.firstOrNull { it.role == "cover" }?.name

  private fun isSafeFlatFileName(name: String): Boolean =
    name.isNotBlank() &&
      name != "." &&
      name != ".." &&
      !name.contains('/') &&
      !name.contains('\\') &&
      !name.contains(':') &&
      name.none { it.isISOControl() }

  private fun isSafeArchivePath(path: String): Boolean {
    val normalized = path.replace('\\', '/')
    return normalized.isNotBlank() &&
      !normalized.startsWith("/") &&
      !Regex("""^[A-Za-z]:/""").matches(normalized) &&
      normalized.split('/').none { it == ".." } &&
      normalized.none { it.isISOControl() }
  }

  const val MANIFEST_FILE = "manifest.json"
  val ALLOWED_ROLES =
    setOf(
      "source",
      "reading_text",
      "reading_index",
      "book_json",
      "cover",
    )
}
