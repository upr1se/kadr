package com.upr1se.kadr.fb2

import java.text.BreakIterator
import java.text.Normalizer
import java.util.Locale

internal object UnicodeText {
  fun normalizeInlineText(value: String): String {
    val normalized = Normalizer.normalize(value, Normalizer.Form.NFC)
    val output = StringBuilder(normalized.length)
    normalized.forEach { character ->
      when {
        character == '\u00AD' -> Unit
        character == '\u200B' -> Unit
        character == '\uFEFF' -> Unit
        character == '\r' || character == '\n' || character == '\t' ->
          if (output.lastOrNull() != ' ') output.append(' ')
        character.isWhitespace() ->
          if (output.lastOrNull() != ' ') output.append(' ')
        Character.getType(character) == Character.CONTROL.toInt() -> Unit
        Character.getType(character) == Character.FORMAT.toInt() &&
          character !in PRESERVED_FORMAT_CHARACTERS -> Unit
        else -> output.append(character)
      }
    }
    return output.toString()
  }

  fun graphemes(value: String): List<String> {
    if (value.isEmpty()) return emptyList()
    val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
    iterator.setText(value)
    val result = ArrayList<String>()
    var start = iterator.first()
    var end = iterator.next()
    while (end != BreakIterator.DONE) {
      result += value.substring(start, end)
      start = end
      end = iterator.next()
    }
    return result
  }

  fun truncateGraphemes(value: String, maxGraphemes: Int): String {
    val graphemes = graphemes(value)
    return if (graphemes.size <= maxGraphemes) value else graphemes.take(maxGraphemes).joinToString("")
  }

  fun truncateCodeUnitsAtGraphemeBoundary(value: String, maxCodeUnits: Int): String {
    if (value.length <= maxCodeUnits) return value
    var result = ""
    for (grapheme in graphemes(value)) {
      if (result.length + grapheme.length > maxCodeUnits) break
      result += grapheme
    }
    return result
  }

  fun sanitizeDisplayFileName(value: String, maxGraphemes: Int): String {
    val baseName = value.substringAfterLast('/').substringAfterLast('\\')
    val withoutControls =
      buildString(baseName.length) {
        baseName.forEach { character ->
          if (!character.isISOControl() && character != '\uFEFF') append(character)
        }
      }
    return truncateGraphemes(withoutControls.trim(), maxGraphemes)
  }

  fun stripBookExtensions(value: String): String {
    val lower = value.lowercase(Locale.ROOT)
    val suffixes = listOf(".fb2.zip", ".fbz", ".fb2")
    val suffix = suffixes.firstOrNull { lower.endsWith(it) }
    return if (suffix == null) value else value.dropLast(suffix.length)
  }

  private val PRESERVED_FORMAT_CHARACTERS =
    setOf('\u200C', '\u200D', '\uFE0E', '\uFE0F')
}
