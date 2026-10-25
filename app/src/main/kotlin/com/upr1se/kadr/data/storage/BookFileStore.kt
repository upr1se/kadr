package com.upr1se.kadr.data.storage

import com.upr1se.kadr.fb2.BookFormat
import com.upr1se.kadr.fb2.Fb2Warning
import com.upr1se.kadr.fb2.Fb2WarningCode
import com.upr1se.kadr.fb2.Fb2Versions
import com.upr1se.kadr.fb2.ParsedFb2
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

data class CopiedSource(
  val file: File,
  val sourceHash: String,
  val sizeBytes: Long,
)

data class CommittedBook(
  val folder: File,
  val manifest: StorageManifest,
  val document: BookDocument,
)

class BookFileStore(
  private val filesDir: File,
  private val availableSpace: AvailableSpace = AndroidAvailableSpace(),
  private val coverProcessor: CoverBytesProcessor = AndroidCoverProcessor(),
) {
  private val booksDir = File(filesDir, "books")
  private val importsDir = File(filesDir, "imports")
  private val trashDir = File(filesDir, "trash")

  init {
    listOf(filesDir, booksDir, importsDir, trashDir).forEach {
      if (!it.exists() && !it.mkdirs() && !it.isDirectory) {
        throw StorageException(StorageErrorCode.IO_FAILURE)
      }
    }
  }

  fun createImportFolder(importId: String): File {
    val folder = safeChild(importsDir, importId)
    if (folder.exists() && !folder.deleteRecursively()) {
      throw StorageException(StorageErrorCode.IO_FAILURE)
    }
    if (!folder.mkdirs()) throw StorageException(StorageErrorCode.IO_FAILURE)
    return folder
  }

  fun copySource(
    input: InputStream,
    destinationFolder: File,
    sizeHint: Long?,
    maxBytes: Long,
    onProgress: (bytesCopied: Long) -> Unit = {},
  ): CopiedSource {
    requireAvailableSpace(minimumCopyReserve(sizeHint))
    val target = File(destinationFolder, "upload.tmp")
    val digest = MessageDigest.getInstance("SHA-256")
    var copied = 0L
    var sinceSpaceCheck = 0L
    val buffer = ByteArray(64 * 1024)
    try {
      FileOutputStream(target).use { output ->
        while (true) {
          val read = input.read(buffer)
          if (read < 0) break
          if (read == 0) continue
          copied += read
          if (copied > maxBytes) {
            throw StorageException(StorageErrorCode.SOURCE_TOO_LARGE)
          }
          digest.update(buffer, 0, read)
          output.write(buffer, 0, read)
          sinceSpaceCheck += read
          if (sinceSpaceCheck >= SPACE_CHECK_STEP) {
            requireAvailableSpace(MINIMUM_STORAGE_RESERVE)
            sinceSpaceCheck = 0
          }
          onProgress(copied)
        }
        output.fd.sync()
      }
      if (copied == 0L) throw StorageException(StorageErrorCode.SOURCE_UNAVAILABLE)
      return CopiedSource(target, digest.digest().toHex(), copied)
    } catch (exception: Exception) {
      runCatching { target.delete() }
      if (exception is StorageException) throw exception
      throw StorageException(StorageErrorCode.IO_FAILURE, exception)
    }
  }

  fun finalizeSource(
    stagingFolder: File,
    copied: CopiedSource,
    format: BookFormat,
    displayName: String,
  ): File {
    val sourceName =
      when {
        format == BookFormat.FB2 -> "source.fb2"
        displayName.endsWith(".fbz", ignoreCase = true) -> "source.fbz"
        else -> "source.fb2.zip"
      }
    val target = File(stagingFolder, sourceName)
    moveAtomic(copied.file, target, replace = true)
    if (Hashing.sha256(target) != copied.sourceHash) {
      throw StorageException(StorageErrorCode.HASH_MISMATCH)
    }
    return target
  }

  fun writeCommittedArtifacts(
    stagingFolder: File,
    bookId: String,
    book: ParsedFb2,
    sourceFile: File,
    sourceHash: String,
    artifactToken: String? = null,
  ): CommittedBook {
    val readingName = artifactName("reading", "txt", artifactToken)
    val indexName = artifactName("reading_index", "bin", artifactToken)
    val bookJsonName = artifactName("book", "json", artifactToken)
    val coverName = artifactName("cover", "webp", artifactToken)
    val textBytes = book.readingText.toByteArray(Charsets.UTF_8)
    val estimatedIndexBytes =
      book.wordCount.toLong() * ReadingIndexWriter.WORD_RECORD_SIZE +
        book.sections.size.toLong() * 1024 +
        book.visualItems.size.toLong() * 256 +
        64 * 1024
    requireAvailableSpace(
      textBytes.size +
        estimatedIndexBytes +
        BookJsonCodec.MAX_BOOK_JSON_BYTES / 4 +
        MINIMUM_STORAGE_RESERVE,
    )
    writeAtomic(stagingFolder, readingName, textBytes)
    val contentHash = Hashing.sha256(textBytes)

    var preparedBook = book
    var coverFile: File? = null
    if (book.coverBytes != null) {
      val webp = coverProcessor.toWebp(book.coverBytes)
      if (webp == null || webp.isEmpty()) {
        preparedBook =
          book.copy(
            warnings =
              (book.warnings + Fb2Warning(Fb2WarningCode.COVER_REJECTED))
                .distinctBy { it.code to it.detail },
          )
      } else {
        coverFile = File(stagingFolder, coverName)
        writeAtomic(stagingFolder, coverName, webp)
      }
    }

    val bookJson = BookJsonCodec.encode(preparedBook)
    writeAtomic(stagingFolder, bookJsonName, bookJson)
    val indexPath = File(stagingFolder, indexName)
    val indexTemp = File(stagingFolder, "$indexName.tmp")
    ReadingIndexWriter().write(preparedBook, indexTemp)
    moveAtomic(indexTemp, indexPath, replace = true)
    val indexHeader =
      ReadingIndexReader.open(indexPath).use { reader ->
      if (!reader.verifyChecksum() ||
        reader.header.textSize != textBytes.size.toLong() ||
        !reader.header.textHash.equals(contentHash, ignoreCase = true)
      ) {
        throw StorageException(StorageErrorCode.INDEX_INVALID)
      }
        reader.header
    }

    val files =
      mutableListOf(
        manifestFile(sourceFile, "source"),
        manifestFile(File(stagingFolder, readingName), "reading_text"),
        manifestFile(indexPath, "reading_index"),
        manifestFile(File(stagingFolder, bookJsonName), "book_json"),
      )
    if (coverFile != null) files += manifestFile(coverFile, "cover")
    val summary =
      IndexSummary(
        wordCount = indexHeader.wordCount,
        itemCount = indexHeader.itemCount,
        sentenceCount = indexHeader.sentenceCount,
        chapterCount = indexHeader.chapterCount,
      )
    val manifest =
      StorageManifest(
        schemaVersion = ManifestCodec.SCHEMA_VERSION,
        state = ManifestCodec.COMMITTED,
        bookId = bookId,
        parserVersion = Fb2Versions.PARSER_VERSION,
        indexVersion = Fb2Versions.INDEX_VERSION,
        layoutVersion = Fb2Versions.LAYOUT_VERSION,
        format = preparedBook.format.name,
        archiveEntryPath = preparedBook.archiveEntryPath,
        archiveEntryIndex = preparedBook.archiveEntryIndex,
        sourceHash = sourceHash,
        contentHash = contentHash,
        files = files,
        summary =
          ManifestSummary(
            wordCount = summary.wordCount,
            itemCount = summary.itemCount,
            sentenceCount = summary.sentenceCount,
            chapterCount = summary.chapterCount,
          ),
      )
    val manifestBytes = ManifestCodec.encode(manifest)
    writeAtomic(stagingFolder, "manifest.json", manifestBytes)
    val decoded = ManifestCodec.decode(File(stagingFolder, "manifest.json").readBytes())
    ManifestCodec.validate(decoded, stagingFolder)
    val document = BookJsonCodec.decode(File(stagingFolder, bookJsonName).readBytes())
    return CommittedBook(stagingFolder, decoded, document)
  }

  fun moveCommittedToBooks(committed: CommittedBook): File {
    val target = safeChild(booksDir, committed.manifest.bookId)
    if (target.exists()) throw StorageException(StorageErrorCode.DUPLICATE_IMPORT)
    Files.createDirectories(booksDir.toPath())
    moveAtomic(committed.folder, target, replace = false)
    loadCommitted(target, committed.manifest.bookId, verifyHashes = false)
    return target
  }

  fun loadCommitted(
    folder: File,
    expectedBookId: String,
    verifyHashes: Boolean,
  ): CommittedBook {
    ensureWithin(filesDir, folder)
    val manifest =
      try {
        ManifestCodec.read(folder)
      } catch (exception: Exception) {
        throw StorageException(StorageErrorCode.INVALID_MANIFEST, exception)
      }
    if (manifest.bookId != expectedBookId) {
      throw StorageException(StorageErrorCode.INVALID_MANIFEST)
    }
    ManifestCodec.validate(manifest, folder)
    if (verifyHashes) {
      for (entry in manifest.files) {
        val file = File(folder, entry.name)
        if (
          entry.role != "cover" &&
            (!file.isFile || !Hashing.sha256(file).equals(entry.sha256, ignoreCase = true))
        ) {
          throw StorageException(StorageErrorCode.HASH_MISMATCH)
        }
      }
    }
    val bookJsonEntry = manifest.files.first { it.role == "book_json" }
    val document = BookJsonCodec.decode(File(folder, bookJsonEntry.name).readBytes())
    return CommittedBook(folder, manifest, document)
  }

  fun deleteUnreferencedArtifacts(folder: File, keep: StorageManifest) {
    val keepNames = keep.files.mapTo(mutableSetOf()) { it.name } + ManifestCodec.MANIFEST_FILE
    folder.listFiles()
      ?.filter {
        it.isFile &&
          it.name !in keepNames &&
          GENERATED_ARTIFACT_PREFIXES.any { prefix -> it.name.startsWith(prefix) }
      }
      ?.forEach { it.delete() }
  }

  fun findCommitted(folder: File, bookId: String): CommittedBook? =
    runCatching { loadCommitted(folder, bookId, verifyHashes = false) }.getOrNull()

  fun bookFolder(bookId: String): File = safeChild(booksDir, bookId)

  fun importFolder(importId: String): File = safeChild(importsDir, importId)

  fun listImports(): List<File> =
    importsDir.listFiles()?.filter { it.isDirectory }.orEmpty()

  fun deleteImport(importId: String) {
    val folder = importFolder(importId)
    if (folder.exists() && !folder.deleteRecursively()) {
      throw StorageException(StorageErrorCode.IO_FAILURE)
    }
  }

  fun moveBookToTrash(bookId: String): File? {
    val folder = bookFolder(bookId)
    if (!folder.exists()) return null
    val marker = File(folder, TRASH_MARKER)
    writeAtomic(folder, TRASH_MARKER, bookId.toByteArray(Charsets.UTF_8))
    val target = safeChild(trashDir, "$bookId-${UUID.randomUUID()}")
    moveAtomic(folder, target, replace = false)
    return target
  }

  fun deleteTrash(folder: File) {
    ensureWithin(trashDir, folder)
    if (folder.exists() && !folder.deleteRecursively()) {
      throw StorageException(StorageErrorCode.IO_FAILURE)
    }
  }

  fun listTrash(): List<File> =
    trashDir.listFiles()?.filter { it.isDirectory }.orEmpty()

  fun trashBelongsTo(folder: File, bookId: String): Boolean {
    val marker = File(folder, TRASH_MARKER)
    return marker.isFile && marker.readText().trim() == bookId
  }

  fun hasValidTrashMarker(folder: File): Boolean {
    val marker = File(folder, TRASH_MARKER)
    if (!marker.isFile || marker.length() > 128) return false
    val value = runCatching { marker.readText().trim() }.getOrNull().orEmpty()
    return value.matches(Regex("""[0-9a-fA-F-]{36}"""))
  }

  fun root(): File = filesDir

  private fun writeAtomic(folder: File, name: String, bytes: ByteArray) {
    if (!isSafeFileName(name)) throw StorageException(StorageErrorCode.INVALID_PATH)
    requireAvailableSpace(bytes.size.toLong() + MINIMUM_STORAGE_RESERVE)
    val target = safeChild(folder, name)
    val temp = safeChild(folder, "$name.tmp")
    try {
      FileOutputStream(temp).use { output ->
        output.write(bytes)
        output.fd.sync()
      }
      moveAtomic(temp, target, replace = true)
    } catch (exception: Exception) {
      runCatching { temp.delete() }
      if (exception is StorageException) throw exception
      throw StorageException(StorageErrorCode.IO_FAILURE, exception)
    }
  }

  private fun manifestFile(file: File, role: String) =
    ManifestFile(
      name = file.name,
      role = role,
      size = file.length(),
      sha256 = Hashing.sha256(file),
    )

  private fun requireAvailableSpace(requiredBytes: Long) {
    val available = availableSpace.availableBytes(filesDir)
    if (available < requiredBytes) {
      throw StorageException(StorageErrorCode.NOT_ENOUGH_SPACE)
    }
  }

  private fun minimumCopyReserve(sizeHint: Long?): Long =
    (sizeHint ?: 0L).coerceAtLeast(0L) + MINIMUM_STORAGE_RESERVE

  private fun moveAtomic(source: File, target: File, replace: Boolean) {
    target.parentFile?.mkdirs()
    val options =
      if (replace) {
        arrayOf(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
      } else {
        arrayOf(StandardCopyOption.ATOMIC_MOVE)
      }
    try {
      Files.move(source.toPath(), target.toPath(), *options)
    } catch (_: AtomicMoveNotSupportedException) {
      val fallback =
        if (replace) {
          arrayOf(StandardCopyOption.REPLACE_EXISTING)
        } else {
          emptyArray()
        }
      Files.move(source.toPath(), target.toPath(), *fallback)
    }
  }

  private fun safeChild(parent: File, childName: String): File {
    if (childName.isBlank() || childName.contains('/') || childName.contains('\\') || childName == "..") {
      throw StorageException(StorageErrorCode.INVALID_PATH)
    }
    val child = File(parent, childName).canonicalFile
    ensureWithin(parent, child)
    return child
  }

  private fun ensureWithin(parent: File, candidate: File) {
    val parentPath = parent.canonicalFile.toPath()
    val candidatePath = candidate.canonicalFile.toPath()
    if (!candidatePath.startsWith(parentPath)) {
      throw StorageException(StorageErrorCode.INVALID_PATH)
    }
  }

  private fun isSafeFileName(name: String): Boolean =
    name.isNotBlank() &&
      !name.contains('/') &&
      !name.contains('\\') &&
      name != "." &&
      name != ".." &&
      name.none { it.isISOControl() }

  private fun artifactName(base: String, extension: String, token: String?): String =
    if (token == null) "$base.$extension" else "$base.$token.$extension"

  companion object {
    const val MINIMUM_STORAGE_RESERVE = 8L * 1024 * 1024
    const val SPACE_CHECK_STEP = 1024L * 1024
    const val TRASH_MARKER = ".kadr-trash"
    val GENERATED_ARTIFACT_PREFIXES =
      listOf("reading.", "reading_index.", "book.", "cover.")
  }
}
