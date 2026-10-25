package com.upr1se.kadr.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.upr1se.kadr.R
import com.upr1se.kadr.core.UserTextFallbacks

@Composable
fun bookTitleText(raw: String): String =
  when (raw) {
    UserTextFallbacks.UNTITLED -> stringResource(R.string.book_untitled)
    UserTextFallbacks.BOOK -> stringResource(R.string.book_synthetic_title)
    else -> raw
  }

@Composable
fun bookAuthorText(raw: String): String =
  if (raw == UserTextFallbacks.UNKNOWN_AUTHOR) {
    stringResource(R.string.library_unknown_author)
  } else {
    raw
  }

@Composable
fun sectionTitleText(raw: String): String =
  when (raw) {
    UserTextFallbacks.BOOK -> stringResource(R.string.book_synthetic_title)
    else -> {
      val number = UserTextFallbacks.sectionNumber(raw)
      if (number != null) stringResource(R.string.book_section_fallback, number) else raw
    }
  }

@Composable
fun bookFormatText(raw: String): String =
  when (raw) {
    "FB2" -> stringResource(R.string.book_format_fb2)
    "FB2_ZIP" -> stringResource(R.string.book_format_fb2_zip)
    else -> raw
  }
