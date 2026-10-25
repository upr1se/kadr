package com.upr1se.kadr.fb2

import com.upr1se.kadr.core.UserTextFallbacks
import java.util.Locale

internal sealed interface InlinePart {
  data class Text(val value: String) : InlinePart

  data class Note(
    val noteId: String,
    val preferPrevious: Boolean,
  ) : InlinePart
}

internal data class InlineDraft(
  val parts: List<InlinePart>,
) {
  val isBlank: Boolean
    get() = parts.none { it is InlinePart.Text && it.value.isNotBlank() }
}

internal class InlineDraftBuilder {
  private val parts = mutableListOf<InlinePart>()

  fun appendText(value: String) {
    if (value.isEmpty()) return
    val last = parts.lastOrNull()
    if (last is InlinePart.Text) {
      parts[parts.lastIndex] = InlinePart.Text(last.value + value)
    } else {
      parts += InlinePart.Text(value)
    }
  }

  fun appendNote(noteId: String, preferPrevious: Boolean) {
    parts += InlinePart.Note(noteId, preferPrevious)
  }

  fun appendDraft(draft: InlineDraft) {
    for (part in draft.parts) {
      when (part) {
        is InlinePart.Text -> appendText(part.value)
        is InlinePart.Note -> appendNote(part.noteId, part.preferPrevious)
      }
    }
  }

  fun hasNonWhitespace(): Boolean =
    parts.any { part ->
      part is InlinePart.Text && part.value.any { !it.isWhitespace() }
    }

  fun endsWithNonWhitespace(): Boolean =
    parts.asReversed().firstNotNullOfOrNull { part ->
      if (part is InlinePart.Text) part.value.lastOrNull()?.isWhitespace()?.not() else null
    } ?: false

  fun build(): InlineDraft = InlineDraft(parts.toList())
}

internal fun flattenInlineDraft(draft: InlineDraft): String {
  val result = StringBuilder()
  for (part in draft.parts) {
    if (part is InlinePart.Text) {
      val normalized = UnicodeText.normalizeInlineText(part.value)
      if (normalized.isEmpty()) continue
      if (result.lastOrNull() == ' ' && normalized.firstOrNull() == ' ') {
        result.append(normalized.drop(1))
      } else {
        result.append(normalized)
      }
    }
  }
  return result.toString().trim()
}

internal data class ContentBlockDraft(
  val kind: BlockKind,
  val chapterKey: String,
  val inline: InlineDraft,
  val endsStanza: Boolean = false,
)

internal sealed interface FlowDraft {
  data class Block(val value: ContentBlockDraft) : FlowDraft

  data class Visual(
    val kind: VisualKind,
    val text: String,
    val chapterKey: String,
  ) : FlowDraft
}

internal data class SectionDraft(
  val chapterKey: String,
  val parentKey: String?,
  val level: Int,
  val siblingNumber: Int,
  val isSynthetic: Boolean,
) {
  var title: String = ""
  var titleWasExplicit: Boolean = false
}

internal class BookDraftBuilder(
  private val limits: Fb2Limits,
) {
  private val sections = mutableListOf<SectionDraft>()
  private val flow = mutableListOf<FlowDraft>()
  private val warnings = mutableListOf<Fb2Warning>()
  private val warningKeys = mutableSetOf<Pair<Fb2WarningCode, String?>>()
  private val notes = linkedMapOf<String, ParsedNote>()
  private var notesTextCodeUnits = 0
  private var coverBytes: ByteArray? = null

  fun addSection(
    chapterKey: String,
    parentKey: String?,
    level: Int,
    siblingNumber: Int,
    isSynthetic: Boolean,
  ): SectionDraft {
    val section =
      SectionDraft(
        chapterKey = chapterKey,
        parentKey = parentKey,
        level = level,
        siblingNumber = siblingNumber,
        isSynthetic = isSynthetic,
      )
    sections += section
    return section
  }

  fun addBlock(
    kind: BlockKind,
    chapterKey: String,
    inline: InlineDraft,
  ) {
    flow += FlowDraft.Block(ContentBlockDraft(kind, chapterKey, inline))
  }

  fun markLastVerseStanza() {
    val lastIndex = flow.indexOfLast { it is FlowDraft.Block }
    val last = flow.getOrNull(lastIndex) as? FlowDraft.Block ?: return
    if (last.value.kind == BlockKind.VERSE_LINE) {
      flow[lastIndex] = FlowDraft.Block(last.value.copy(endsStanza = true))
    }
  }

  fun addVisual(
    kind: VisualKind,
    text: String,
    chapterKey: String,
  ) {
    flow += FlowDraft.Visual(kind, text, chapterKey)
  }

  fun addNote(note: ParsedNote) {
    if (notes.containsKey(note.noteId)) {
      addWarning(Fb2Warning(Fb2WarningCode.DUPLICATE_NOTE_ID))
      return
    }
    if (notes.size >= limits.maxNotes) {
      addWarning(Fb2Warning(Fb2WarningCode.UNSUPPORTED_ELEMENT, "notes-limit"))
      return
    }
    val truncated = note.blocks.sumOf { it.length } > limits.maxNoteCodeUnits
    val boundedBlocks =
      if (!truncated) {
        note.blocks
      } else {
        val output = mutableListOf<String>()
        var remaining = limits.maxNoteCodeUnits
        for (block in note.blocks) {
          if (remaining <= 0) break
          val piece = UnicodeText.truncateCodeUnitsAtGraphemeBoundary(block, remaining)
          if (piece.isNotEmpty()) output += piece
          remaining -= piece.length + 2
        }
        output
      }
    val textLength = boundedBlocks.sumOf { it.length }
    if (notesTextCodeUnits + textLength > limits.maxNotesTextCodeUnits) {
      addWarning(Fb2Warning(Fb2WarningCode.NOTE_TRUNCATED))
      return
    }
    notesTextCodeUnits += textLength
    val noteWarnings =
      if (truncated) listOf(Fb2Warning(Fb2WarningCode.NOTE_TRUNCATED)) else emptyList()
    notes[note.noteId] = note.copy(blocks = boundedBlocks, warnings = noteWarnings)
    if (truncated) addWarning(Fb2Warning(Fb2WarningCode.NOTE_TRUNCATED))
  }

  fun setCover(bytes: ByteArray?) {
    coverBytes = bytes
  }

  fun addWarning(warning: Fb2Warning) {
    val key = warning.code to warning.detail
    if (warningKeys.add(key) && warnings.size < limits.maxWarnings) {
      warnings += warning
    }
  }

  fun build(
    sourceFileName: String,
    format: BookFormat,
    archiveEntryPath: String?,
    archiveEntryIndex: Int?,
    metadata: BookMetadata,
  ): ParsedFb2 {
    val canonical = Canonicalizer(limits, notes.keys).canonicalize(flow, sections)
    if (canonical.words.isEmpty()) {
      throw Fb2ImportException(ImportErrorCode.NO_TEXT)
    }
    return ParsedFb2(
      sourceFileName = sourceFileName,
      format = format,
      archiveEntryPath = archiveEntryPath,
      archiveEntryIndex = archiveEntryIndex,
      metadata = metadata,
      readingText = canonical.readingText,
      words = canonical.words,
      sections = canonical.sections,
      notes = notes.values.toList(),
      visualItems = canonical.visualItems,
      warnings =
        (warnings + canonical.warnings).distinctBy { it.code to it.detail }.take(limits.maxWarnings),
      coverBytes = coverBytes,
    )
  }
}

private data class CanonicalResult(
  val readingText: String,
  val words: List<ParsedWord>,
  val sections: List<ParsedSection>,
  val visualItems: List<ParsedVisual>,
  val warnings: List<Fb2Warning>,
)

private data class LogicalToken(
  val text: String,
  val noteIds: MutableList<String>,
)

private data class NormalizedInline(
  val text: String,
  val markers: List<NormalizedMarker>,
)

private data class NormalizedMarker(
  val position: Int,
  val noteId: String,
  val preferPrevious: Boolean,
)

private data class RawPiece(
  val text: String,
  val start: Int,
  val end: Int,
)

private class Canonicalizer(
  private val limits: Fb2Limits,
  private val availableNoteIds: Set<String>,
) {
  private val output = StringBuilder()
  private val words = mutableListOf<ParsedWord>()
  private val visuals = mutableListOf<VisualAnchor>()
  private val warnings = mutableListOf<Fb2Warning>()
  private val warningKeys = mutableSetOf<Pair<Fb2WarningCode, String?>>()
  private val wordCountsBySection = mutableMapOf<String, Int>()
  private val firstWordBySection = mutableMapOf<String, Int>()
  private var previousBlockKind: BlockKind? = null
  private var previousVerseEndedStanza = false
  private var previousEndsStanza = false

  fun canonicalize(
    flow: List<FlowDraft>,
    sections: List<SectionDraft>,
  ): CanonicalResult {
    for (entry in flow) {
      when (entry) {
        is FlowDraft.Visual ->
          visuals +=
            VisualAnchor(
              kind = entry.kind,
              text = entry.text,
              chapterKey = entry.chapterKey,
              wordIndex = words.size,
            )
        is FlowDraft.Block -> appendBlock(entry.value)
      }
    }

    assignSentenceIds()
    val nonEmptyTopLevel = topLevelChapterNumbering(sections)
    val topLevelBySection = buildTopLevelMap(sections, nonEmptyTopLevel)
    for (word in words) {
      word.topLevelChapterIndex = topLevelBySection[word.chapterKey] ?: -1
    }
    val finalSections = buildSections(sections, words, nonEmptyTopLevel)
    val finalVisuals = resolveVisuals(words)
    return CanonicalResult(
      readingText = output.toString(),
      words = words,
      sections = finalSections,
      visualItems = finalVisuals,
      warnings = warnings,
    )
  }

  private fun appendBlock(block: ContentBlockDraft) {
    val normalized = normalize(block.inline)
    val rawPieces = splitPieces(normalized.text)
    val markersByPiece = attachMarkers(rawPieces, normalized.markers)
    val logicalTokens = buildLogicalTokens(rawPieces, markersByPiece)
    if (logicalTokens.isEmpty()) return

    if (output.isNotEmpty()) {
      val lineBreaks =
        if (
          block.kind == BlockKind.VERSE_LINE &&
            previousBlockKind == BlockKind.VERSE_LINE &&
            !previousVerseEndedStanza &&
            !previousEndsStanza
        ) {
          "\n"
        } else {
          "\n\n"
        }
      output.append(lineBreaks)
    }

    val firstWordInBlock = words.size
    for ((logicalIndex, logicalToken) in logicalTokens.withIndex()) {
      val chunks = splitToken(logicalToken.text)
      for ((chunkIndex, chunk) in chunks.withIndex()) {
        if (words.size >= limits.maxWords) {
          throw Fb2ImportException(ImportErrorCode.WORD_LIMIT_EXCEEDED)
        }
        if (chunkIndex > 0) output.append('\u0020')
        val start = output.length
        output.append(chunk)
        val end = output.length
        if (output.length > limits.maxNormalizedTextCodeUnits) {
          throw Fb2ImportException(ImportErrorCode.TEXT_LIMIT_EXCEEDED)
        }
        val isLastChunk = chunkIndex == chunks.lastIndex
        val noteIds =
          if (isLastChunk) {
            logicalToken.noteIds.filter { it in availableNoteIds }
          } else {
            emptyList()
          }
        if (isLastChunk) {
          logicalToken.noteIds.forEach { noteId ->
            if (noteId !in availableNoteIds) reportWarning(Fb2WarningCode.MISSING_NOTE)
          }
        }
        val boundary =
          if (logicalIndex == logicalTokens.lastIndex) {
            boundaryForBlock(block, logicalToken.text)
          } else {
            clauseBoundary(logicalToken.text)
          }
        words +=
          ParsedWord(
            wordIndex = words.size,
            startChar = start,
            endChar = end,
            sentenceId = -1,
            chapterKey = block.chapterKey,
            topLevelChapterIndex = -1,
            boundary = boundary,
            endsSentence = isLastChunk && endsWithSentencePunctuation(logicalToken.text),
            noteIds = noteIds,
          )
      }
      if (logicalIndex != logicalTokens.lastIndex) {
        output.append('\u0020')
      }
    }

    val sectionCount = words.size - firstWordInBlock
    if (sectionCount > 0) {
      wordCountsBySection[block.chapterKey] =
        (wordCountsBySection[block.chapterKey] ?: 0) + sectionCount
      firstWordBySection.putIfAbsent(block.chapterKey, firstWordInBlock)
    }
    previousBlockKind = block.kind
    previousVerseEndedStanza = false
    previousEndsStanza = block.endsStanza
  }

  private fun normalize(inlineDraft: InlineDraft): NormalizedInline {
    val result = StringBuilder()
    val markers = mutableListOf<NormalizedMarker>()
    for (part in inlineDraft.parts) {
      when (part) {
        is InlinePart.Text -> {
          val normalized = UnicodeText.normalizeInlineText(part.value)
          if (normalized.isEmpty()) continue
          if (result.lastOrNull() == ' ' && normalized.firstOrNull() == ' ') {
            result.append(normalized.drop(1))
          } else {
            result.append(normalized)
          }
        }
        is InlinePart.Note -> {
          markers += NormalizedMarker(result.length, part.noteId, part.preferPrevious)
        }
      }
    }
    val all = result.toString()
    val leading = all.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) all.length else it }
    val trailing = all.indexOfLast { !it.isWhitespace() }.let { if (it < 0) -1 else it }
    if (trailing < leading) return NormalizedInline("", emptyList())
    val trimmed = all.substring(leading, trailing + 1)
    val adjusted =
      markers.map { marker ->
        marker.copy(position = (marker.position - leading).coerceIn(0, trimmed.length))
      }
    return NormalizedInline(trimmed, adjusted)
  }

  private fun splitPieces(text: String): List<RawPiece> {
    val pieces = mutableListOf<RawPiece>()
    var index = 0
    while (index < text.length) {
      while (index < text.length && text[index].isWhitespace()) index++
      if (index >= text.length) break
      val start = index
      while (index < text.length && !text[index].isWhitespace()) index++
      pieces += RawPiece(text.substring(start, index), start, index)
    }
    return pieces
  }

  private fun attachMarkers(
    pieces: List<RawPiece>,
    markers: List<NormalizedMarker>,
  ): Map<Int, List<String>> {
    if (pieces.isEmpty()) return emptyMap()
    val result = mutableMapOf<Int, MutableList<String>>()
    for (marker in markers) {
      val targetIndex =
        if (marker.preferPrevious) {
          pieces.indexOfLast { it.start <= marker.position }.takeIf { it >= 0 }
            ?: pieces.indexOfFirst { it.start >= marker.position }
        } else {
          pieces.indexOfFirst { it.start >= marker.position }.takeIf { it >= 0 }
            ?: pieces.indexOfLast { it.start <= marker.position }
        }
      val resolved = targetIndex.coerceAtLeast(0)
      result.getOrPut(resolved) { mutableListOf() } += marker.noteId
    }
    return result
  }

  private fun buildLogicalTokens(
    pieces: List<RawPiece>,
    notesByPiece: Map<Int, List<String>>,
  ): List<LogicalToken> {
    val tokens = mutableListOf<LogicalToken>()
    val pendingPrefix = StringBuilder()
    val pendingNotes = mutableListOf<String>()
    for ((pieceIndex, piece) in pieces.withIndex()) {
      val noteIds = notesByPiece[pieceIndex].orEmpty()
      if (isPunctuationOnly(piece.text)) {
        if (tokens.isNotEmpty()) {
          val previous = tokens.last()
          tokens[tokens.lastIndex] = previous.copy(text = previous.text + piece.text)
          previous.noteIds += noteIds
        } else {
          pendingPrefix.append(piece.text)
          pendingNotes += noteIds
        }
      } else {
        val token =
          LogicalToken(
            text = pendingPrefix.toString() + piece.text,
            noteIds = (pendingNotes + noteIds).toMutableList(),
          )
        tokens += token
        pendingPrefix.clear()
        pendingNotes.clear()
      }
    }
    // A punctuation-only block has no readable word and is intentionally omitted.
    return tokens
  }

  private fun splitToken(token: String): List<String> {
    if (token.length <= Fb2Versions.MAX_TOKEN_GRAPHEMES) return listOf(token)
    val graphemes = UnicodeText.graphemes(token)
    if (graphemes.size <= Fb2Versions.MAX_TOKEN_GRAPHEMES) return listOf(token)
    reportWarning(Fb2WarningCode.LONG_TOKEN_SPLIT)
    return graphemes
      .chunked(Fb2Versions.MAX_TOKEN_GRAPHEMES)
      .map { it.joinToString("") }
  }

  private fun boundaryForBlock(
    block: ContentBlockDraft,
    lastToken: String,
  ): BoundaryKind {
    val semantic = clauseBoundary(lastToken)
    val blockBoundary =
      when {
        block.endsStanza -> BoundaryKind.VERSE_STANZA
        block.kind == BlockKind.VERSE_LINE -> BoundaryKind.VERSE_LINE
        block.kind == BlockKind.TABLE_ROW -> BoundaryKind.TABLE_ROW
        else -> BoundaryKind.PARAGRAPH
      }
    val sentenceBoundary =
      if (endsWithSentencePunctuation(lastToken)) BoundaryKind.SENTENCE else BoundaryKind.NONE
    return semantic.strongest(sentenceBoundary).strongest(blockBoundary)
  }

  private fun clauseBoundary(token: String): BoundaryKind {
    val stripped = token.trimEnd(*CLOSING_QUOTES_AND_BRACKETS)
    return if (stripped.endsWith(',') || stripped.endsWith(';') || stripped.endsWith(':') ||
      stripped.endsWith('—') || stripped.endsWith('–') || stripped.endsWith('-')
    ) {
      BoundaryKind.CLAUSE
    } else {
      BoundaryKind.NONE
    }
  }

  private fun endsWithSentencePunctuation(token: String): Boolean {
    val stripped = token.trimEnd(*CLOSING_QUOTES_AND_BRACKETS).lowercase(Locale.ROOT)
    if (stripped.isEmpty()) return false
    if (!(stripped.endsWith('.') || stripped.endsWith('!') || stripped.endsWith('?') ||
        stripped.endsWith('…'))
    ) {
      return false
    }
    if (stripped in ABBREVIATIONS) return false
    if (INITIALS.matches(stripped)) return false
    if (DECIMAL.matches(stripped)) return false
    if (stripped.contains("://") || stripped.startsWith("www.")) return false
    val lastDot = stripped.lastIndexOf('.')
    if (lastDot > 0 && stripped.count { it == '.' } > 1 &&
      stripped.substring(lastDot + 1).all { it.isDigit() }
    ) {
      return false
    }
    return true
  }

  private fun assignSentenceIds() {
    if (words.isEmpty()) return
    var sentenceId = 0
    for (index in words.indices) {
      val word = words[index]
      val endsSentence =
        word.endsSentence ||
          when (word.boundary) {
            BoundaryKind.PARAGRAPH,
            BoundaryKind.VERSE_STANZA,
            BoundaryKind.SENTENCE -> true
            else -> false
          }
      word.sentenceId = sentenceId
      word.endsSentence = endsSentence
      if (endsSentence) {
        word.boundary = word.boundary.strongest(BoundaryKind.SENTENCE)
      }
      if (endsSentence) sentenceId++
    }
  }

  private fun topLevelChapterNumbering(
    sections: List<SectionDraft>,
  ): Map<String, Int> {
    var counter = 0
    val result = linkedMapOf<String, Int>()
    for (section in sections) {
      if (section.level != 0 || section.isSynthetic) continue
      val hasWords = containsWords(section.chapterKey)
      if (hasWords) result[section.chapterKey] = counter++
    }
    return result
  }

  private fun buildTopLevelMap(
    sections: List<SectionDraft>,
    numbering: Map<String, Int>,
  ): Map<String, Int> {
    val result = mutableMapOf<String, Int>()
    for (section in sections) {
      if (section.isSynthetic) continue
      val top =
        sections
          .asSequence()
          .filter { it.level == 0 && !it.isSynthetic }
          .firstOrNull { section.chapterKey == it.chapterKey || section.chapterKey.startsWith(it.chapterKey + ".s") }
      val number = top?.let { numbering[it.chapterKey] } ?: continue
      result[section.chapterKey] = number
    }
    return result
  }

  private fun buildSections(
    drafts: List<SectionDraft>,
    finalWords: List<ParsedWord>,
    numbering: Map<String, Int>,
  ): List<ParsedSection> =
    drafts.map { draft ->
      val subtreeWordIndexes =
        finalWords.asSequence().filter {
          it.chapterKey == draft.chapterKey ||
            (!draft.isSynthetic && it.chapterKey.startsWith(draft.chapterKey + ".s"))
        }
      val first = subtreeWordIndexes.minOfOrNull { it.wordIndex }
      val count =
        if (first == null) {
          0
        } else {
          finalWords.count {
            it.chapterKey == draft.chapterKey ||
              (!draft.isSynthetic && it.chapterKey.startsWith(draft.chapterKey + ".s"))
          }
        }
      val title =
        UnicodeText.truncateGraphemes(
          draft.title.ifBlank {
            if (draft.isSynthetic) UserTextFallbacks.BOOK
            else UserTextFallbacks.section(draft.siblingNumber)
          },
          limits.maxSectionTitleGraphemes,
        )
      ParsedSection(
        chapterKey = draft.chapterKey,
        parentKey = draft.parentKey,
        level = draft.level,
        title = title,
        firstWordIndex = first,
        wordCount = count,
        topLevelChapterIndex =
          if (draft.isSynthetic) {
            null
          } else {
            numbering[draft.chapterKey]
              ?: drafts.firstOrNull {
                draft.chapterKey.startsWith(it.chapterKey + ".s") && it.level == 0
              }?.let { numbering[it.chapterKey] }
          },
        isSynthetic = draft.isSynthetic,
      )
    }

  private fun resolveVisuals(finalWords: List<ParsedWord>): List<ParsedVisual> =
    visuals.map { visual ->
      val charOffset =
        finalWords.getOrNull(visual.wordIndex)?.startChar ?: output.length
      ParsedVisual(
        kind = visual.kind,
        text = visual.text,
        chapterKey = visual.chapterKey,
        anchorCharOffset = charOffset,
        anchorWordIndex = visual.wordIndex,
      )
    }

  private fun containsWords(sectionKey: String): Boolean =
    firstWordBySection.keys.any { it == sectionKey || it.startsWith(sectionKey + ".s") }

  private fun reportWarning(code: Fb2WarningCode) {
    val key = code to null
    if (warningKeys.add(key) && warnings.size < limits.maxWarnings) {
      warnings += Fb2Warning(code)
    }
  }

  private data class VisualAnchor(
    val kind: VisualKind,
    val text: String,
    val chapterKey: String,
    val wordIndex: Int,
  )

  private companion object {
    val CLOSING_QUOTES_AND_BRACKETS =
      charArrayOf(
        '"',
        '\'',
        '»',
        '”',
        '’',
        ')',
        ']',
        '}',
      )

    val ABBREVIATIONS =
      setOf(
        "г.",
        "т.",
        "д.",
        "др.",
        "стр.",
        "рис.",
        "им.",
        "ул.",
        "т.д.",
        "т.п.",
      )

    val INITIALS = Regex("""^(?:[a-zа-яё]\.){1,3}$""", RegexOption.IGNORE_CASE)
    val DECIMAL = Regex("""^\d+[.,]\d+[.!?…]?$""")
  }
}

private fun isPunctuationOnly(value: String): Boolean {
  var sawPunctuation = false
  for (character in value) {
    if (character.isWhitespace()) continue
    if (Character.isLetterOrDigit(character)) return false
    when (Character.getType(character)) {
      Character.CONNECTOR_PUNCTUATION.toInt(),
      Character.DASH_PUNCTUATION.toInt(),
      Character.START_PUNCTUATION.toInt(),
      Character.END_PUNCTUATION.toInt(),
      Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
      Character.FINAL_QUOTE_PUNCTUATION.toInt(),
      Character.OTHER_PUNCTUATION.toInt() -> sawPunctuation = true
      else -> return false
    }
  }
  return sawPunctuation
}
