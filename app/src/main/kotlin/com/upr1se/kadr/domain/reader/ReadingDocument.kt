package com.upr1se.kadr.domain.reader

import com.upr1se.kadr.data.storage.IndexItemKind
import com.upr1se.kadr.data.storage.IndexSentenceRange
import com.upr1se.kadr.data.storage.IndexWordRecord
import com.upr1se.kadr.data.storage.ReadingIndexReader
import com.upr1se.kadr.fb2.ParsedNote
import com.upr1se.kadr.fb2.ParsedSection
import com.upr1se.kadr.fb2.VisualKind
import com.upr1se.kadr.fb2.UnicodeText
import kotlin.math.abs

data class ReaderItem(
  val ordinal: Int,
  val kind: IndexItemKind,
  val text: String,
  val visualKind: VisualKind?,
  val wordIndex: Int?,
  val startChar: Int?,
  val endChar: Int?,
  val sentenceId: Int?,
  val chapterKey: String,
  val chapterTitle: String,
  val topLevelChapterIndex: Int?,
  val noteIds: List<String>,
)

data class RestoredPosition(
  val itemOrdinal: Int,
  val exact: Boolean,
)

class ReadingDocument(
  val text: String,
  val index: ReadingIndexReader,
  val sections: List<ParsedSection>,
  val notes: Map<String, ParsedNote>,
) {
  private val sectionsByKey = sections.associateBy { it.chapterKey }
  private val topLevelSections =
    sections
      .filter { it.level == 0 && !it.isSynthetic && !it.isEmpty }
      .sortedBy { it.topLevelChapterIndex ?: Int.MAX_VALUE }

  val wordCount: Int
    get() = index.header.wordCount

  val itemCount: Int
    get() = index.header.itemCount

  val sentenceCount: Int
    get() = index.header.sentenceCount

  val chapterCount: Int
    get() = index.header.chapterCount

  fun item(itemOrdinal: Int): ReaderItem {
    val record = index.readItem(itemOrdinal)
    return when (record.kind) {
      IndexItemKind.WORD -> {
        val word = index.readWord(record.referenceIndex)
        val section = sections[word.sectionIndex]
        ReaderItem(
          ordinal = itemOrdinal,
          kind = IndexItemKind.WORD,
          text = text.substring(word.startChar, word.endChar),
          visualKind = null,
          wordIndex = record.referenceIndex,
          startChar = word.startChar,
          endChar = word.endChar,
          sentenceId = word.sentenceId,
          chapterKey = section.chapterKey,
          chapterTitle = section.title,
          topLevelChapterIndex = section.topLevelChapterIndex,
          noteIds = index.noteIdsForWord(record.referenceIndex),
        )
      }
      IndexItemKind.HEADING,
      IndexItemKind.IMAGE -> {
        val visual = index.visuals()[record.referenceIndex]
        val section = sectionsByKey[visual.chapterKey]
        ReaderItem(
          ordinal = itemOrdinal,
          kind = record.kind,
          text = visual.text,
          visualKind = visual.kind,
          wordIndex = visual.anchorWordIndex.takeIf { it < wordCount },
          startChar = visual.anchorCharOffset,
          endChar = null,
          sentenceId = visual.anchorWordIndex
            .takeIf { it < wordCount }
            ?.let { index.readWord(it).sentenceId },
          chapterKey = visual.chapterKey,
          chapterTitle = section?.title ?: visual.text,
          topLevelChapterIndex = section?.topLevelChapterIndex,
          noteIds = emptyList(),
        )
      }
    }
  }

  fun word(wordIndex: Int): IndexWordRecord = index.readWord(wordIndex)

  fun itemForWord(wordIndex: Int): Int = index.readWord(wordIndex).itemOrdinal

  fun firstWordItem(): Int = if (wordCount > 0) index.readWord(0).itemOrdinal else throw IllegalStateException()

  fun lastWordItem(): Int = index.readWord(wordCount - 1).itemOrdinal

  fun wordText(wordIndex: Int): String =
    index.readWord(wordIndex).let { text.substring(it.startChar, it.endChar) }

  fun fingerprintForWord(wordIndex: Int): String =
    index.readWord(wordIndex).let { ReadingFingerprint.calculate(text, it.startChar, it.endChar) }

  fun sentenceForWord(wordIndex: Int): IndexSentenceRange =
    index.readSentence(index.readWord(wordIndex).sentenceId)

  fun sentencePreview(
    wordIndex: Int,
    maxGraphemes: Int = 1_000,
  ): String {
    val sentence = sentenceForWord(wordIndex)
    val first = index.readWord(sentence.firstWordIndex)
    val last = index.readWord(sentence.lastWordIndex)
    val current = index.readWord(wordIndex)
    val sentenceStart = first.startChar
    val sentenceEnd = last.endChar
    val fullLength = sentenceEnd - sentenceStart
    val windowStart: Int
    val windowEnd: Int
    val needsPrefix: Boolean
    val needsSuffix: Boolean
    if (fullLength <= MAX_SENTENCE_WINDOW_CODE_UNITS) {
      windowStart = sentenceStart
      windowEnd = sentenceEnd
      needsPrefix = false
      needsSuffix = false
    } else {
      windowStart = (current.startChar - MAX_SENTENCE_WINDOW_CODE_UNITS / 2)
        .coerceIn(sentenceStart, sentenceEnd)
      windowEnd = (windowStart + MAX_SENTENCE_WINDOW_CODE_UNITS).coerceAtMost(sentenceEnd)
      needsPrefix = windowStart > sentenceStart
      needsSuffix = windowEnd < sentenceEnd
    }
    val normalized =
      text.substring(windowStart, windowEnd)
        .replace(Regex("""\s+"""), " ")
        .trim()
    var graphemes = UnicodeText.graphemes(normalized)
    val prefixEllipsis = needsPrefix
    val suffixEllipsis = needsSuffix
    if (graphemes.size > maxGraphemes) {
      val totalGraphemes = graphemes.size
      val currentApprox =
        ((normalized.indexOf(text.substring(current.startChar, current.endChar))
          .coerceAtLeast(0)).toFloat() / normalized.length * graphemes.size).toInt()
      val start = (currentApprox - maxGraphemes / 2).coerceIn(0, graphemes.size - maxGraphemes)
      graphemes = graphemes.subList(start, start + maxGraphemes)
      return buildString {
        if (prefixEllipsis || start > 0) append("…")
        append(graphemes.joinToString(""))
        if (suffixEllipsis || start + maxGraphemes < totalGraphemes) append("…")
      }
    }
    return buildString {
      if (prefixEllipsis) append("…")
      append(graphemes.joinToString(""))
      if (suffixEllipsis) append("…")
    }
  }

  fun restorePosition(
    itemOrdinal: Int,
    currentWordIndex: Int,
    currentCharOffset: Int,
    chapterKey: String?,
    fingerprint: String?,
  ): RestoredPosition {
    if (itemOrdinal in 0 until itemCount) {
      val item = item(itemOrdinal)
      if (item.kind == IndexItemKind.WORD) {
        if (
          item.wordIndex == currentWordIndex &&
            item.startChar == currentCharOffset &&
            (fingerprint == null || fingerprint == fingerprintForWord(currentWordIndex))
        ) {
          return RestoredPosition(itemOrdinal, exact = true)
        }
      } else if (chapterKey == null || chapterKey == item.chapterKey) {
        return RestoredPosition(itemOrdinal, exact = true)
      }
    }

    val section = chapterKey?.let(sectionsByKey::get)
    if (section != null && section.firstWordIndex != null && section.wordCount > 0) {
      val start = section.firstWordIndex
      val endExclusive = (start + section.wordCount).coerceAtMost(wordCount)
      var closest: Int? = null
      var closestDistance = Long.MAX_VALUE
      if (fingerprint != null) {
        for (candidate in start until endExclusive) {
          if (fingerprintForWord(candidate) == fingerprint) {
            val distance = abs(index.readWord(candidate).startChar - currentCharOffset).toLong()
            if (distance < closestDistance) {
              closest = candidate
              closestDistance = distance
            }
          }
        }
      }
      if (closest == null &&
        currentWordIndex in start until endExclusive &&
        (fingerprint == null || fingerprintForWord(currentWordIndex) == fingerprint)
      ) {
        closest = currentWordIndex
      }
      val fallback = closest ?: start
      return RestoredPosition(itemForWord(fallback), exact = false)
    }
    return RestoredPosition(firstWordItem(), exact = false)
  }

  fun currentSentenceNumber(item: ReaderItem): Int? =
    item.wordIndex?.let { index.readWord(it).sentenceId }

  fun canGoToPreviousSentence(item: ReaderItem): Boolean {
    val wordIndex = item.wordIndex ?: return false
    val sentence = sentenceForWord(wordIndex)
    return wordIndex > sentence.firstWordIndex || sentence.sentenceId > 0
  }

  fun canGoToNextSentence(item: ReaderItem): Boolean {
    val wordIndex = item.wordIndex ?: return false
    val sentence = sentenceForWord(wordIndex)
    return sentence.sentenceId + 1 < sentenceCount
  }

  fun previousSentenceItem(item: ReaderItem): Int? {
    val wordIndex = item.wordIndex ?: return null
    val sentence = sentenceForWord(wordIndex)
    val targetSentence =
      if (wordIndex > sentence.firstWordIndex) {
        sentence.sentenceId
      } else {
        sentence.sentenceId - 1
      }
    if (targetSentence < 0) return null
    return itemForWord(index.readSentence(targetSentence).firstWordIndex)
  }

  fun nextSentenceItem(item: ReaderItem): Int? {
    val wordIndex = item.wordIndex ?: return null
    val sentence = sentenceForWord(wordIndex)
    if (sentence.sentenceId + 1 >= sentenceCount) return null
    return itemForWord(index.readSentence(sentence.sentenceId + 1).firstWordIndex)
  }

  fun sectionFirstItem(section: ParsedSection): Int? =
    section.firstWordIndex?.takeIf { !section.isEmpty }?.let(::itemForWord)

  fun contentSections(): List<ParsedSection> =
    sections.filter { !it.isSynthetic }

  fun realTopLevelSections(): List<ParsedSection> = topLevelSections

  fun nextTopLevelChapter(
    currentItem: ReaderItem,
  ): Pair<ParsedSection, Int>? {
    val currentTop = currentItem.topLevelChapterIndex ?: return null
    val next = topLevelSections.firstOrNull { (it.topLevelChapterIndex ?: -1) > currentTop } ?: return null
    val firstWord = next.firstWordIndex ?: return null
    return next to itemForWord(firstWord)
  }

  fun nextWordStartsDifferentTopLevelChapter(item: ReaderItem): Boolean {
    val wordIndex = item.wordIndex ?: return false
    if (item.kind != IndexItemKind.WORD || wordIndex + 1 >= wordCount) return false
    val nextWord = index.readWord(wordIndex + 1)
    val nextSection = sections[nextWord.sectionIndex]
    return nextSection.topLevelChapterIndex != item.topLevelChapterIndex
  }

  fun topLevelSectionFor(item: ReaderItem): ParsedSection? =
    item.topLevelChapterIndex?.let { number ->
      topLevelSections.firstOrNull { it.topLevelChapterIndex == number }
    }

  fun noteBlocks(noteIds: List<String>): List<String> =
    noteIds.flatMap { notes[it]?.blocks.orEmpty() }

  private companion object {
    const val MAX_SENTENCE_WINDOW_CODE_UNITS = 8_000
  }
}
