package com.upr1se.kadr.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class ContentState {
  IMPORTING,
  READY,
  BROKEN,
  DELETING,
}

enum class ReadingState {
  UNREAD,
  READING,
  COMPLETED,
}

@Entity(
  tableName = "books",
  indices = [
    Index(value = ["contentState", "lastOpenedAt"]),
    Index(value = ["readingState", "lastOpenedAt"]),
    Index(value = ["sourceHash"]),
    Index(value = ["contentHash"]),
  ],
)
data class BookEntity(
  @PrimaryKey val id: String,
  val title: String,
  val authorDisplay: String?,
  val language: String?,
  val format: String,
  val sourceFileName: String,
  val folderRelativePath: String,
  val archiveEntryPath: String?,
  val archiveEntryIndex: Int?,
  val sourceHash: String,
  val contentHash: String,
  val sourceSizeBytes: Long,
  val wordCount: Int,
  val chapterCount: Int,
  val coverRelativePath: String?,
  val contentState: ContentState,
  val readingState: ReadingState,
  val currentItemOrdinal: Int,
  val currentCharOffset: Int,
  val currentWordIndex: Int,
  val currentSentenceId: Int?,
  val currentChapterKey: String?,
  val currentChapterTitle: String?,
  val positionFingerprint: String?,
  val positionRevision: Long,
  val progressPercent: Int,
  val lastSpeedWpm: Int?,
  val parserVersion: Int,
  val indexVersion: Int,
  val layoutVersion: Int,
  val importedAt: Long,
  val lastOpenedAt: Long?,
  val lastSavedAt: Long?,
  val lastErrorCode: String?,
)
