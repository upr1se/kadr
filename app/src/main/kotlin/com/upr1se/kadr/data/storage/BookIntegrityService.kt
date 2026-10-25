package com.upr1se.kadr.data.storage

import com.upr1se.kadr.data.db.BookCatalog
import com.upr1se.kadr.data.db.BookEntity
import com.upr1se.kadr.data.db.ContentState
import com.upr1se.kadr.fb2.Fb2Inspection
import com.upr1se.kadr.fb2.Fb2Parser
import java.io.File
import java.util.UUID

sealed interface RepairResult {
  data class Repaired(val book: BookEntity, val committed: CommittedBook) : RepairResult

  data class Failed(val code: String) : RepairResult
}

class BookIntegrityService(
  private val files: BookFileStore,
  private val parser: Fb2Parser,
  private val catalog: BookCatalog,
) {
  suspend fun repair(bookId: String): RepairResult {
    val row = catalog.findById(bookId) ?: return RepairResult.Failed("BOOK_INTEGRITY")
    return try {
      val folder = files.bookFolder(bookId)
      if (!folder.isDirectory) return failed(bookId, "BOOK_INTEGRITY")
      val source = findSource(folder) ?: return failed(bookId, "BOOK_INTEGRITY")
      val sourceHash = Hashing.sha256(source)
      if (row.sourceHash.isNotBlank() && !row.sourceHash.equals(sourceHash, ignoreCase = true)) {
        return failed(bookId, "BOOK_INTEGRITY")
      }
      val inspection = parser.inspect(source, row.sourceFileName)
      val selectedEntry =
        when (inspection) {
          is Fb2Inspection.SingleBook -> inspection.selectedCandidate?.entryIndex
          is Fb2Inspection.NeedsSelection -> {
            val index = row.archiveEntryIndex ?: return failed(bookId, "BOOK_REBUILD_FAILED")
            val candidate =
              inspection.candidates.firstOrNull { it.entryIndex == index }
                ?: return failed(bookId, "BOOK_REBUILD_FAILED")
            if (row.archiveEntryPath != null && row.archiveEntryPath != candidate.path) {
              return failed(bookId, "BOOK_INTEGRITY")
            }
            index
          }
        }
      val parsed =
        parser.parse(
          file = source,
          sourceFileName = row.sourceFileName,
          inspection = inspection,
          selectedEntryIndex = selectedEntry,
        )
      val token = UUID.randomUUID().toString().replace("-", "").take(16)
      val committed =
        files.writeCommittedArtifacts(
          stagingFolder = folder,
          bookId = bookId,
          book = parsed,
          sourceFile = source,
          sourceHash = sourceHash,
          artifactToken = token,
        )
      val verified = files.loadCommitted(folder, bookId, verifyHashes = false)
      val metadata = verified.document.metadata
      val updated =
        row.copy(
          title = metadata.title,
          authorDisplay = metadata.authorDisplay,
          language = metadata.language,
          format = verified.manifest.format,
          archiveEntryPath = verified.manifest.archiveEntryPath,
          archiveEntryIndex = verified.manifest.archiveEntryIndex,
          sourceHash = verified.manifest.sourceHash,
          contentHash = verified.manifest.contentHash,
          wordCount = verified.manifest.summary.wordCount,
          chapterCount = verified.manifest.summary.chapterCount,
          coverRelativePath = ManifestCodec.coverFileName(verified.manifest),
          contentState = ContentState.READY,
          parserVersion = verified.manifest.parserVersion,
          indexVersion = verified.manifest.indexVersion,
          layoutVersion = verified.manifest.layoutVersion,
          lastErrorCode = null,
        )
      catalog.update(updated)
      files.deleteUnreferencedArtifacts(folder, verified.manifest)
      RepairResult.Repaired(updated, committed)
    } catch (_: Exception) {
      failed(bookId, "BOOK_REBUILD_FAILED")
    }
  }

  private suspend fun failed(bookId: String, code: String): RepairResult {
    runCatching { catalog.markBroken(bookId, code) }
    return RepairResult.Failed(code)
  }

  private fun findSource(folder: File): File? =
    listOf("source.fb2", "source.fb2.zip", "source.fbz")
      .map { File(folder, it) }
      .firstOrNull { it.isFile }
}
