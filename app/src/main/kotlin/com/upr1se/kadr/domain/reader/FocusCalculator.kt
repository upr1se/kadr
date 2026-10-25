package com.upr1se.kadr.domain.reader

import com.upr1se.kadr.fb2.UnicodeText

data class FocusResult(
  val graphemes: List<String>,
  val focusIndex: Int,
) {
  val focusGrapheme: String
    get() = graphemes[focusIndex]
}

object FocusCalculator {
  fun calculate(word: String): FocusResult {
    val graphemes = UnicodeText.graphemes(word)
    if (graphemes.isEmpty()) return FocusResult(listOf(""), 0)
    val semantic = graphemes.indices.filter { index -> isWordCharacter(graphemes[index]) }
    if (semantic.isEmpty()) return FocusResult(graphemes, 0)
    val first = semantic.first()
    val last = semantic.last()
    val length = last - first + 1
    val offset =
      when {
        length <= 1 -> 0
        length <= 5 -> 1
        length <= 9 -> 2
        length <= 13 -> 3
        else -> 4
      }.coerceAtMost(length - 1)
    return FocusResult(graphemes, (first + offset).coerceIn(first, last))
  }

  private fun isWordCharacter(grapheme: String): Boolean =
    grapheme.codePoints().anyMatch { codePoint ->
      Character.isLetterOrDigit(codePoint)
    }
}
