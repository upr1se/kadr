package com.upr1se.kadr.core

object UserTextFallbacks {
  const val UNKNOWN_AUTHOR = "\uE000kadr:unknown-author"
  const val UNTITLED = "\uE000kadr:untitled"
  const val BOOK = "\uE000kadr:book"
  const val IMAGE = "\uE000kadr:image"
  private const val SECTION_PREFIX = "\uE000kadr:section:"

  fun section(number: Int): String = "$SECTION_PREFIX$number"

  fun sectionNumber(value: String): Int? =
    value.removePrefix(SECTION_PREFIX).takeIf { it != value }?.toIntOrNull()
}
