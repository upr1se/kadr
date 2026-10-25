package com.upr1se.kadr.fb2

fun ParsedFb2.wordTexts(): List<String> =
  words.map { readingText.substring(it.startChar, it.endChar) }

fun ParsedFb2.textOf(word: ParsedWord): String =
  readingText.substring(word.startChar, word.endChar)
