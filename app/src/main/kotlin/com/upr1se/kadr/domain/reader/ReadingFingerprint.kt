package com.upr1se.kadr.domain.reader

import com.upr1se.kadr.data.storage.Hashing

object ReadingFingerprint {
  fun calculate(
    readingText: String,
    startChar: Int,
    endChar: Int,
  ): String {
    if (startChar !in 0..endChar || endChar > readingText.length) return ""
    var contextStart = (startChar - CONTEXT_CODE_UNITS).coerceAtLeast(0)
    if (contextStart < startChar &&
      contextStart > 0 &&
      Character.isLowSurrogate(readingText[contextStart])
    ) {
      contextStart--
    }
    var contextEnd = (endChar + CONTEXT_CODE_UNITS).coerceAtMost(readingText.length)
    if (contextEnd > endChar &&
      contextEnd < readingText.length &&
      Character.isHighSurrogate(readingText[contextEnd - 1])
    ) {
      contextEnd++
    }
    val before = normalizeContext(readingText.substring(contextStart, startChar))
    val word = normalizeContext(readingText.substring(startChar, endChar))
    val after = normalizeContext(readingText.substring(endChar, contextEnd))
    return Hashing.fingerprint("$before\u0000$word\u0000$after")
  }

  private fun normalizeContext(value: String): String =
    value
      .replace(Regex("""\s+"""), " ")
      .filterNot { it.isISOControl() }

  private const val CONTEXT_CODE_UNITS = 64
}
