package com.upr1se.kadr.data.storage

import com.upr1se.kadr.fb2.BoundaryKind
import com.upr1se.kadr.fb2.Fb2Versions
import com.upr1se.kadr.fb2.ParsedFb2
import com.upr1se.kadr.fb2.ParsedSection
import com.upr1se.kadr.fb2.ParsedVisual
import com.upr1se.kadr.fb2.VisualKind
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

data class IndexSummary(
  val wordCount: Int,
  val itemCount: Int,
  val sentenceCount: Int,
  val chapterCount: Int,
)

data class ReadingIndexHeader(
  val formatVersion: Int,
  val parserVersion: Int,
  val layoutVersion: Int,
  val textSize: Long,
  val textHash: String,
  val wordCount: Int,
  val itemCount: Int,
  val sentenceCount: Int,
  val chapterCount: Int,
  val sectionCount: Int,
  val visualCount: Int,
  val noteLinkCount: Int,
  val sectionsOffset: Long,
  val visualsOffset: Long,
  val sentencesOffset: Long,
  val wordsOffset: Long,
  val noteDataOffset: Long,
  val noteOffsetsOffset: Long,
  val itemsOffset: Long,
  val payloadEnd: Long,
)

enum class IndexItemKind {
  WORD,
  HEADING,
  IMAGE,
}

data class IndexItemRecord(
  val itemOrdinal: Int,
  val kind: IndexItemKind,
  val referenceIndex: Int,
)

data class IndexWordRecord(
  val wordIndex: Int,
  val startChar: Int,
  val endChar: Int,
  val sentenceId: Int,
  val sectionIndex: Int,
  val itemOrdinal: Int,
  val boundary: BoundaryKind,
  val endsSentence: Boolean,
  val noteLinkStart: Int,
  val noteCount: Int,
)

data class IndexSentenceRange(
  val sentenceId: Int,
  val firstWordIndex: Int,
  val lastWordIndex: Int,
)

data class IndexVisualRecord(
  val visualIndex: Int,
  val kind: VisualKind,
  val text: String,
  val chapterKey: String,
  val anchorCharOffset: Int,
  val anchorWordIndex: Int,
)

class ReadingIndexWriter {
  fun write(book: ParsedFb2, target: File) {
    if (book.words.size > MAX_RECORD_COUNT || book.sections.size > MAX_RECORD_COUNT) {
      throw StorageException(StorageErrorCode.INDEX_INVALID)
    }
    target.parentFile?.mkdirs()
    val textBytes = book.readingText.toByteArray(Charsets.UTF_8)
    val textHash = Hashing.sha256(textBytes)
    val sectionsBytes = encodeSections(book.sections)
    val visuals = filterVisuals(book)
    val visualsBytes = encodeVisuals(visuals)
    val sentenceCount = book.sentenceCount
    val sentenceBytes = sentenceCount.toLong() * SENTENCE_RECORD_SIZE
    val wordBytes = book.wordCount.toLong() * WORD_RECORD_SIZE
    val noteLinks = collectNoteLinks(book)
    val noteData = noteLinks.data
    val noteOffsets = noteLinks.offsets
    val noteOffsetBytes = noteOffsets.size.toLong() * NOTE_OFFSET_SIZE
    val itemCount = book.wordCount + visuals.size
    val itemBytes = itemCount.toLong() * ITEM_RECORD_SIZE

    val sectionsOffset = HEADER_SIZE.toLong()
    val visualsOffset = sectionsOffset + sectionsBytes.size
    val sentencesOffset = visualsOffset + visualsBytes.size
    val wordsOffset = sentencesOffset + sentenceBytes
    val noteDataOffset = wordsOffset + wordBytes
    val noteOffsetsOffset = noteDataOffset + noteData.size
    val itemsOffset = noteOffsetsOffset + noteOffsetBytes
    val payloadEnd = itemsOffset + itemBytes
    if (payloadEnd > MAX_SUPPORTED_INDEX_BYTES) {
      throw StorageException(StorageErrorCode.INDEX_INVALID)
    }

    val header =
      ReadingIndexHeader(
        formatVersion = Fb2Versions.INDEX_VERSION,
        parserVersion = Fb2Versions.PARSER_VERSION,
        layoutVersion = Fb2Versions.LAYOUT_VERSION,
        textSize = textBytes.size.toLong(),
        textHash = textHash,
        wordCount = book.wordCount,
        itemCount = itemCount,
        sentenceCount = sentenceCount,
        chapterCount = book.chapterCount,
        sectionCount = book.sections.size,
        visualCount = visuals.size,
        noteLinkCount = noteOffsets.size,
        sectionsOffset = sectionsOffset,
        visualsOffset = visualsOffset,
        sentencesOffset = sentencesOffset,
        wordsOffset = wordsOffset,
        noteDataOffset = noteDataOffset,
        noteOffsetsOffset = noteOffsetsOffset,
        itemsOffset = itemsOffset,
        payloadEnd = payloadEnd,
      )

    FileOutputStream(target).use { fileOutput ->
      DataOutputStream(BufferedOutputStream(fileOutput, 64 * 1024)).use { output ->
        writeHeader(output, header)
        output.write(sectionsBytes)
        output.write(visualsBytes)
        writeSentences(output, book, sentenceCount)
        writeWords(
          output = output,
          book = book,
          visuals = visuals,
          noteOffsets = noteOffsets,
        )
        output.write(noteData)
        for (offset in noteOffsets) output.writeInt(offset)
        writeItems(output, book.wordCount, visuals)
      }
    }
    val computedHash = sha256Prefix(target, payloadEnd)
    FileOutputStream(target, true).use { output ->
      output.write(FOOTER_MAGIC)
      output.write(hexToBytes(computedHash))
      output.fd.sync()
    }
  }

  private fun encodeSections(sections: List<ParsedSection>): ByteArray {
    val bytes = ByteArrayOutputStream()
    val output = DataOutputStream(bytes)
    for (section in sections) {
      writeString(output, section.chapterKey)
      writeNullableString(output, section.parentKey)
      output.writeInt(section.level)
      writeString(output, section.title)
      output.writeInt(section.firstWordIndex ?: -1)
      output.writeInt(section.wordCount)
      output.writeInt(section.topLevelChapterIndex ?: -1)
      output.writeByte(if (section.isSynthetic) 1 else 0)
    }
    output.flush()
    return bytes.toByteArray()
  }

  private fun filterVisuals(book: ParsedFb2): List<ParsedVisual> {
    val sectionsByKey = book.sections.associateBy { it.chapterKey }
    return book.visualItems
      .filter { visual ->
        if (visual.kind == VisualKind.IMAGE) {
          true
        } else {
          sectionsByKey[visual.chapterKey]?.isEmpty == false
        }
      }
      .sortedBy { it.anchorWordIndex }
  }

  private fun encodeVisuals(visuals: List<ParsedVisual>): ByteArray {
    val bytes = ByteArrayOutputStream()
    val output = DataOutputStream(bytes)
    for (visual in visuals) {
      output.writeByte(visual.kind.ordinal)
      output.writeInt(visual.anchorWordIndex)
      output.writeInt(visual.anchorCharOffset)
      writeString(output, visual.chapterKey)
      writeString(output, visual.text)
    }
    output.flush()
    return bytes.toByteArray()
  }

  private fun writeSentences(
    output: DataOutputStream,
    book: ParsedFb2,
    sentenceCount: Int,
  ) {
    if (sentenceCount == 0) return
    val first = IntArray(sentenceCount) { -1 }
    val last = IntArray(sentenceCount) { -1 }
    for (word in book.words) {
      if (word.sentenceId !in 0 until sentenceCount) {
        throw StorageException(StorageErrorCode.INDEX_INVALID)
      }
      if (first[word.sentenceId] < 0) first[word.sentenceId] = word.wordIndex
      last[word.sentenceId] = word.wordIndex
    }
    for (sentenceId in 0 until sentenceCount) {
      if (first[sentenceId] < 0) throw StorageException(StorageErrorCode.INDEX_INVALID)
      output.writeInt(first[sentenceId])
      output.writeInt(last[sentenceId])
    }
  }

  private fun writeWords(
    output: DataOutputStream,
    book: ParsedFb2,
    visuals: List<ParsedVisual>,
    noteOffsets: IntArray,
  ) {
    val sectionIndexes = book.sections.mapIndexed { index, section -> section.chapterKey to index }.toMap()
    var visualPointer = 0
    var notePointer = 0
    for (word in book.words) {
      while (visualPointer < visuals.size &&
        visuals[visualPointer].anchorWordIndex <= word.wordIndex
      ) {
        visualPointer++
      }
      val itemOrdinal = word.wordIndex + visualPointer
      val sectionIndex =
        sectionIndexes[word.chapterKey] ?: throw StorageException(StorageErrorCode.INDEX_INVALID)
      output.writeInt(word.startChar)
      output.writeInt(word.endChar)
      output.writeInt(word.sentenceId)
      output.writeInt(sectionIndex)
      output.writeInt(itemOrdinal)
      output.writeByte(word.boundary.ordinal)
      output.writeByte(if (word.endsSentence) 1 else 0)
      output.writeShort(0)
      output.writeInt(notePointer)
      output.writeInt(word.noteIds.size)
      notePointer += word.noteIds.size
    }
    if (notePointer != noteOffsets.size) {
      throw StorageException(StorageErrorCode.INDEX_INVALID)
    }
  }

  private fun writeItems(
    output: DataOutputStream,
    wordCount: Int,
    visuals: List<ParsedVisual>,
  ) {
    var visualPointer = 0
    for (wordIndex in 0 until wordCount) {
      while (visualPointer < visuals.size &&
        visuals[visualPointer].anchorWordIndex <= wordIndex
      ) {
        val visualIndex = visualPointer
        output.writeByte(indexKind(visuals[visualPointer].kind).ordinal)
        output.writeByte(0)
        output.writeShort(0)
        output.writeInt(visualIndex)
        visualPointer++
      }
      output.writeByte(IndexItemKind.WORD.ordinal)
      output.writeByte(0)
      output.writeShort(0)
      output.writeInt(wordIndex)
    }
    while (visualPointer < visuals.size) {
      output.writeByte(indexKind(visuals[visualPointer].kind).ordinal)
      output.writeByte(0)
      output.writeShort(0)
      output.writeInt(visualPointer)
      visualPointer++
    }
  }

  private fun indexKind(kind: VisualKind): IndexItemKind =
    when (kind) {
      VisualKind.HEADING -> IndexItemKind.HEADING
      VisualKind.IMAGE -> IndexItemKind.IMAGE
    }

  private fun collectNoteLinks(book: ParsedFb2): NoteLinks {
    val data = ByteArrayOutputStream()
    val output = DataOutputStream(data)
    val offsets = mutableListOf<Int>()
    for (word in book.words) {
      for (noteId in word.noteIds) {
        val bytes = noteId.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_INDEX_STRING_BYTES) {
          throw StorageException(StorageErrorCode.INDEX_INVALID)
        }
        offsets += data.size()
        output.writeInt(bytes.size)
        output.write(bytes)
      }
    }
    output.flush()
    if (offsets.size > MAX_NOTE_LINKS) {
      throw StorageException(StorageErrorCode.INDEX_INVALID)
    }
    return NoteLinks(data.toByteArray(), offsets.toIntArray())
  }

  private fun writeHeader(
    output: DataOutputStream,
    header: ReadingIndexHeader,
  ) {
    output.write(HEADER_MAGIC)
    output.writeInt(header.formatVersion)
    output.writeInt(header.parserVersion)
    output.writeInt(header.layoutVersion)
    output.writeInt(0)
    output.writeLong(header.textSize)
    output.write(hexToBytes(header.textHash))
    output.writeInt(header.wordCount)
    output.writeInt(header.itemCount)
    output.writeInt(header.sentenceCount)
    output.writeInt(header.chapterCount)
    output.writeInt(header.sectionCount)
    output.writeInt(header.visualCount)
    output.writeInt(header.noteLinkCount)
    output.writeLong(header.sectionsOffset)
    output.writeLong(header.visualsOffset)
    output.writeLong(header.sentencesOffset)
    output.writeLong(header.wordsOffset)
    output.writeLong(header.noteDataOffset)
    output.writeLong(header.noteOffsetsOffset)
    output.writeLong(header.itemsOffset)
    output.writeLong(header.payloadEnd)
    output.writeLong(0)
    output.writeLong(0)
    check(HEADER_SIZE == 172)
  }

  private fun writeString(output: DataOutputStream, value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    if (bytes.size > MAX_INDEX_STRING_BYTES) {
      throw StorageException(StorageErrorCode.INDEX_INVALID)
    }
    output.writeInt(bytes.size)
    output.write(bytes)
  }

  private fun writeNullableString(output: DataOutputStream, value: String?) {
    if (value == null) {
      output.writeInt(-1)
    } else {
      writeString(output, value)
    }
  }

  private data class NoteLinks(
    val data: ByteArray,
    val offsets: IntArray,
  )

  companion object {
    const val HEADER_SIZE = 172
    const val FOOTER_SIZE = 40
    const val WORD_RECORD_SIZE = 32
    const val ITEM_RECORD_SIZE = 8
    const val SENTENCE_RECORD_SIZE = 8
    const val NOTE_OFFSET_SIZE = 4
    const val MAX_RECORD_COUNT = 5_000_000
    const val MAX_NOTE_LINKS = 1_000_000
    const val MAX_INDEX_STRING_BYTES = 1024 * 1024
    const val MAX_SUPPORTED_INDEX_BYTES = 4L * 1024 * 1024 * 1024
    val HEADER_MAGIC = "SLVINDEX".toByteArray(Charsets.US_ASCII)
    val FOOTER_MAGIC = "SLVNDX01".toByteArray(Charsets.US_ASCII)
  }
}

class ReadingIndexReader private constructor(
  private val sourceFile: File,
  private val file: RandomAccessFile,
  val header: ReadingIndexHeader,
  private val sections: List<ParsedSection>,
  private val visuals: List<IndexVisualRecord>,
) : AutoCloseable {
  private val sectionIndexes = sections.mapIndexed { index, section -> section.chapterKey to index }.toMap()

  @Synchronized
  fun readWord(wordIndex: Int): IndexWordRecord {
    requireRange(wordIndex, header.wordCount, "word")
    file.seek(header.wordsOffset + wordIndex.toLong() * ReadingIndexWriter.WORD_RECORD_SIZE)
    val startChar = file.readInt()
    val endChar = file.readInt()
    val sentenceId = file.readInt()
    val sectionIndex = file.readInt()
    val itemOrdinal = file.readInt()
    val boundary = boundaryFromOrdinal(file.readUnsignedByte())
    val endsSentence = file.readUnsignedByte() != 0
    file.readShort()
    val noteLinkStart = file.readInt()
    val noteCount = file.readInt()
    if (
      startChar !in 0..endChar ||
        endChar.toLong() > header.textSize ||
        sectionIndex !in sections.indices ||
        sentenceId !in 0 until header.sentenceCount ||
        itemOrdinal !in 0 until header.itemCount ||
        noteLinkStart < 0 ||
        noteCount < 0 ||
        noteLinkStart.toLong() + noteCount > header.noteLinkCount
    ) {
      throw StorageException(StorageErrorCode.INDEX_INVALID)
    }
    return IndexWordRecord(
      wordIndex = wordIndex,
      startChar = startChar,
      endChar = endChar,
      sentenceId = sentenceId,
      sectionIndex = sectionIndex,
      itemOrdinal = itemOrdinal,
      boundary = boundary,
      endsSentence = endsSentence,
      noteLinkStart = noteLinkStart,
      noteCount = noteCount,
    )
  }

  @Synchronized
  fun readItem(itemOrdinal: Int): IndexItemRecord {
    requireRange(itemOrdinal, header.itemCount, "item")
    file.seek(header.itemsOffset + itemOrdinal.toLong() * ReadingIndexWriter.ITEM_RECORD_SIZE)
    val kind = itemKindFromOrdinal(file.readUnsignedByte())
    file.readUnsignedByte()
    file.readShort()
    val reference = file.readInt()
    val expectedCount =
      if (kind == IndexItemKind.WORD) header.wordCount else header.visualCount
    if (reference !in 0 until expectedCount) {
      throw StorageException(StorageErrorCode.INDEX_INVALID)
    }
    return IndexItemRecord(itemOrdinal, kind, reference)
  }

  @Synchronized
  fun readSentence(sentenceId: Int): IndexSentenceRange {
    requireRange(sentenceId, header.sentenceCount, "sentence")
    file.seek(
      header.sentencesOffset + sentenceId.toLong() * ReadingIndexWriter.SENTENCE_RECORD_SIZE,
    )
    val first = file.readInt()
    val last = file.readInt()
    if (first !in 0 until header.wordCount || last !in first until header.wordCount) {
      throw StorageException(StorageErrorCode.INDEX_INVALID)
    }
    return IndexSentenceRange(sentenceId, first, last)
  }

  fun sections(): List<ParsedSection> = sections

  fun visuals(): List<IndexVisualRecord> = visuals

  fun sectionForWord(wordIndex: Int): ParsedSection {
    val word = readWord(wordIndex)
    return sections[word.sectionIndex]
  }

  fun sectionByKey(chapterKey: String): ParsedSection? =
    sectionIndexes[chapterKey]?.let(sections::get)

  @Synchronized
  fun noteIdsForWord(wordIndex: Int): List<String> {
    val word = readWord(wordIndex)
    if (word.noteCount == 0) return emptyList()
    val ids = ArrayList<String>(word.noteCount)
    for (linkIndex in word.noteLinkStart until word.noteLinkStart + word.noteCount) {
      file.seek(header.noteOffsetsOffset + linkIndex.toLong() * ReadingIndexWriter.NOTE_OFFSET_SIZE)
      val relative = file.readInt()
      if (relative < 0 || header.noteDataOffset + relative >= header.noteOffsetsOffset) {
        throw StorageException(StorageErrorCode.INDEX_INVALID)
      }
      file.seek(header.noteDataOffset + relative)
      ids += readStringFromFile(file)
    }
    return ids
  }

  fun verifyChecksum(): Boolean {
    val expected = readFooterHash()
    val actual = sha256Prefix(sourceFile, header.payloadEnd)
    return expected.equals(actual, ignoreCase = true)
  }

  private fun readFooterHash(): String {
    file.seek(header.payloadEnd)
    val magic = ByteArray(ReadingIndexWriter.FOOTER_MAGIC.size)
    file.readFully(magic)
    if (!magic.contentEquals(ReadingIndexWriter.FOOTER_MAGIC)) {
      throw StorageException(StorageErrorCode.INDEX_INVALID)
    }
    val hash = ByteArray(32)
    file.readFully(hash)
    return hash.toHex()
  }

  override fun close() {
    file.close()
  }

  private fun requireRange(value: Int, count: Int, label: String) {
    if (value !in 0 until count) {
      throw StorageException(StorageErrorCode.INDEX_INVALID, IllegalArgumentException("$label $value/$count"))
    }
  }

  companion object {
    fun open(file: File): ReadingIndexReader {
      if (!file.isFile) throw StorageException(StorageErrorCode.MISSING_FILE)
      val randomAccess =
        try {
          RandomAccessFile(file, "r")
        } catch (exception: Exception) {
          throw StorageException(StorageErrorCode.INDEX_INVALID, exception)
        }
      try {
        val header = readHeader(randomAccess, file.length())
        val sections = readSections(randomAccess, header)
        val visuals = readVisuals(randomAccess, header)
        return ReadingIndexReader(file, randomAccess, header, sections, visuals)
      } catch (exception: Exception) {
        runCatching { randomAccess.close() }
        if (exception is StorageException) throw exception
        throw StorageException(StorageErrorCode.INDEX_INVALID, exception)
      }
    }

    private fun readHeader(file: RandomAccessFile, fileLength: Long): ReadingIndexHeader {
      if (fileLength < ReadingIndexWriter.HEADER_SIZE + ReadingIndexWriter.FOOTER_SIZE) {
        throw StorageException(StorageErrorCode.INDEX_INVALID)
      }
      file.seek(0)
      val magic = ByteArray(ReadingIndexWriter.HEADER_MAGIC.size)
      file.readFully(magic)
      if (!magic.contentEquals(ReadingIndexWriter.HEADER_MAGIC)) {
        throw StorageException(StorageErrorCode.INDEX_INVALID)
      }
      val formatVersion = file.readInt()
      val parserVersion = file.readInt()
      val layoutVersion = file.readInt()
      file.readInt()
      val textSize = file.readLong()
      val textHash = ByteArray(32).also(file::readFully).toHex()
      val wordCount = file.readInt()
      val itemCount = file.readInt()
      val sentenceCount = file.readInt()
      val chapterCount = file.readInt()
      val sectionCount = file.readInt()
      val visualCount = file.readInt()
      val noteLinkCount = file.readInt()
      val sectionsOffset = file.readLong()
      val visualsOffset = file.readLong()
      val sentencesOffset = file.readLong()
      val wordsOffset = file.readLong()
      val noteDataOffset = file.readLong()
      val noteOffsetsOffset = file.readLong()
      val itemsOffset = file.readLong()
      val payloadEnd = file.readLong()
      file.readLong()
      file.readLong()
      val header =
        ReadingIndexHeader(
          formatVersion = formatVersion,
          parserVersion = parserVersion,
          layoutVersion = layoutVersion,
          textSize = textSize,
          textHash = textHash,
          wordCount = wordCount,
          itemCount = itemCount,
          sentenceCount = sentenceCount,
          chapterCount = chapterCount,
          sectionCount = sectionCount,
          visualCount = visualCount,
          noteLinkCount = noteLinkCount,
          sectionsOffset = sectionsOffset,
          visualsOffset = visualsOffset,
          sentencesOffset = sentencesOffset,
          wordsOffset = wordsOffset,
          noteDataOffset = noteDataOffset,
          noteOffsetsOffset = noteOffsetsOffset,
          itemsOffset = itemsOffset,
          payloadEnd = payloadEnd,
        )
      validateHeader(header, fileLength)
      return header
    }

    private fun validateHeader(header: ReadingIndexHeader, fileLength: Long) {
      if (
        header.formatVersion != Fb2Versions.INDEX_VERSION ||
        header.parserVersion <= 0 ||
        header.layoutVersion <= 0 ||
        header.textSize < 0 ||
        header.wordCount !in 0..ReadingIndexWriter.MAX_RECORD_COUNT ||
        header.itemCount != header.wordCount + header.visualCount ||
        header.sentenceCount !in 0..header.wordCount ||
        header.chapterCount < 0 ||
        header.sectionCount < 0 ||
        header.visualCount < 0 ||
        header.noteLinkCount !in 0..ReadingIndexWriter.MAX_NOTE_LINKS ||
        header.sectionsOffset != ReadingIndexWriter.HEADER_SIZE.toLong() ||
        header.visualsOffset < header.sectionsOffset ||
        header.sentencesOffset < header.visualsOffset ||
        header.wordsOffset < header.sentencesOffset ||
        header.noteDataOffset < header.wordsOffset ||
        header.noteOffsetsOffset < header.noteDataOffset ||
        header.itemsOffset < header.noteOffsetsOffset ||
        header.payloadEnd < header.itemsOffset ||
        header.payloadEnd != fileLength - ReadingIndexWriter.FOOTER_SIZE ||
        header.payloadEnd > fileLength ||
        header.sentencesOffset + header.sentenceCount.toLong() * ReadingIndexWriter.SENTENCE_RECORD_SIZE !=
          header.wordsOffset ||
        header.wordsOffset + header.wordCount.toLong() * ReadingIndexWriter.WORD_RECORD_SIZE !=
          header.noteDataOffset ||
        header.noteOffsetsOffset + header.noteLinkCount.toLong() * ReadingIndexWriter.NOTE_OFFSET_SIZE !=
          header.itemsOffset ||
        header.itemsOffset + header.itemCount.toLong() * ReadingIndexWriter.ITEM_RECORD_SIZE !=
          header.payloadEnd
      ) {
        throw StorageException(StorageErrorCode.INDEX_INVALID)
      }
    }

    private fun readSections(
      file: RandomAccessFile,
      header: ReadingIndexHeader,
    ): List<ParsedSection> {
      file.seek(header.sectionsOffset)
      val sections = ArrayList<ParsedSection>(header.sectionCount.coerceAtMost(100_000))
      var consumed = 0L
      repeat(header.sectionCount) {
        val key = readStringFromFile(file)
        val parent = readNullableStringFromFile(file)
        val level = file.readInt()
        val title = readStringFromFile(file)
        val firstWord = file.readInt()
        val wordCount = file.readInt()
        val topLevel = file.readInt()
        val synthetic = file.readUnsignedByte() != 0
        if (
          level < 0 ||
            wordCount < 0 ||
            firstWord < -1 ||
            topLevel < -1 ||
            (firstWord >= 0 && firstWord >= header.wordCount)
        ) {
          throw StorageException(StorageErrorCode.INDEX_INVALID)
        }
        sections +=
          ParsedSection(
            chapterKey = key,
            parentKey = parent,
            level = level,
            title = title,
            firstWordIndex = firstWord.takeIf { it >= 0 },
            wordCount = wordCount,
            topLevelChapterIndex = topLevel.takeIf { it >= 0 },
            isSynthetic = synthetic,
          )
        consumed++
      }
      val current = file.filePointer
      if (current > header.visualsOffset || sections.size != header.sectionCount) {
        throw StorageException(StorageErrorCode.INDEX_INVALID)
      }
      return sections
    }

    private fun readVisuals(
      file: RandomAccessFile,
      header: ReadingIndexHeader,
    ): List<IndexVisualRecord> {
      file.seek(header.visualsOffset)
      val visuals = ArrayList<IndexVisualRecord>(header.visualCount.coerceAtMost(100_000))
      repeat(header.visualCount) { visualIndex ->
        val kind =
          when (file.readUnsignedByte()) {
            VisualKind.HEADING.ordinal -> VisualKind.HEADING
            VisualKind.IMAGE.ordinal -> VisualKind.IMAGE
            else -> throw StorageException(StorageErrorCode.INDEX_INVALID)
          }
        val anchorWord = file.readInt()
        val anchorChar = file.readInt()
        val chapterKey = readStringFromFile(file)
        val text = readStringFromFile(file)
        if (
          anchorWord !in 0..header.wordCount ||
            anchorChar !in 0..header.textSize.toInt()
        ) {
          throw StorageException(StorageErrorCode.INDEX_INVALID)
        }
        visuals +=
          IndexVisualRecord(
            visualIndex = visualIndex,
            kind = kind,
            text = text,
            chapterKey = chapterKey,
            anchorCharOffset = anchorChar,
            anchorWordIndex = anchorWord,
          )
      }
      if (file.filePointer > header.sentencesOffset) {
        throw StorageException(StorageErrorCode.INDEX_INVALID)
      }
      return visuals
    }
  }
}

private fun readStringFromFile(file: RandomAccessFile): String {
  val length = file.readInt()
  if (length !in 0..ReadingIndexWriter.MAX_INDEX_STRING_BYTES) {
    throw StorageException(StorageErrorCode.INDEX_INVALID)
  }
  val bytes = ByteArray(length)
  file.readFully(bytes)
  return bytes.toString(Charsets.UTF_8)
}

private fun readNullableStringFromFile(file: RandomAccessFile): String? {
  val length = file.readInt()
  if (length == -1) return null
  if (length !in 0..ReadingIndexWriter.MAX_INDEX_STRING_BYTES) {
    throw StorageException(StorageErrorCode.INDEX_INVALID)
  }
  val bytes = ByteArray(length)
  file.readFully(bytes)
  return bytes.toString(Charsets.UTF_8)
}

private fun boundaryFromOrdinal(ordinal: Int): BoundaryKind =
  BoundaryKind.entries.getOrNull(ordinal)
    ?: throw StorageException(StorageErrorCode.INDEX_INVALID)

private fun itemKindFromOrdinal(ordinal: Int): IndexItemKind =
  IndexItemKind.entries.getOrNull(ordinal)
    ?: throw StorageException(StorageErrorCode.INDEX_INVALID)

private fun hexToBytes(value: String): ByteArray {
  if (value.length != 64) throw StorageException(StorageErrorCode.INDEX_INVALID)
  return ByteArray(32) { index ->
    value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
  }
}

private fun sha256Prefix(file: File, prefixLength: Long): String {
  val digest = MessageDigest.getInstance("SHA-256")
  FileInputStream(file).use { input ->
    val buffer = ByteArray(64 * 1024)
    var remaining = prefixLength
    while (remaining > 0) {
      val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
      if (read < 0) throw StorageException(StorageErrorCode.INDEX_INVALID)
      digest.update(buffer, 0, read)
      remaining -= read
    }
  }
  return digest.digest().toHex()
}
