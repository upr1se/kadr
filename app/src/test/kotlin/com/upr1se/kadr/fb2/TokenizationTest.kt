package com.upr1se.kadr.fb2

import com.upr1se.kadr.core.UserTextFallbacks
import com.upr1se.kadr.testing.SyntheticFb2
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TokenizationTest {
  @get:Rule val temporaryFolder = TemporaryFolder()

  @Test
  fun punctuationQuotesHyphensNumbersInitialsAndAbbreviationsStayInExpectedTokens() {
    val book =
      parse(
        "<p>— Привет, «кто-то»! В 3.14 раза; А. П. Иванов, г. Москва, и т. д.</p>",
      )

    assertEquals(
      listOf("—Привет,", "«кто-то»!", "В", "3.14", "раза;", "А.", "П.", "Иванов,", "г.", "Москва,", "и", "т.", "д."),
      book.wordTexts(),
    )
    assertEquals("А.", book.textOf(book.words.first { book.textOf(it) == "А." }))
    assertEquals(BoundaryKind.CLAUSE, book.words.first { book.textOf(it) == "—Привет," }.boundary)
    assertEquals(BoundaryKind.SENTENCE, book.words.first { book.textOf(it) == "«кто-то»!" }.boundary)
  }

  @Test
  fun inlineTagsNeverCreateSpacesInsideWordsButPreserveAuthoredSpaces() {
    val book =
      parse(
        "<p>раз<emphasis>два</emphasis>три <strong>четыре</strong> пять<emphasis>.</emphasis></p>",
      )

    assertEquals(listOf("раздватри", "четыре", "пять."), book.wordTexts())
  }

  @Test
  fun nonBreakingWhitespaceAndPunctuationOnlyTokensAreNormalized() {
    val book =
      parse(
        "<p>Первый&#160;второй , третий</p><p>— четвёртый</p><empty-line/><p>   </p>",
      )

    assertEquals(
      listOf("Первый", "второй,", "третий", "—четвёртый"),
      book.wordTexts(),
    )
    assertTrue(book.readingText.isNotBlank())
    assertTrue(!book.readingText.contains('\u00A0'))
  }

  @Test
  fun headingsAndImagesDoNotIncrementWordCount() {
    val book =
      parse(
        "<section><title>Заголовок</title><p>Одно.</p><image l:href=\"#x\"/></section>",
      )

    assertEquals(1, book.wordCount)
    assertEquals(2, book.visualItems.size)
    assertTrue(book.visualItems.any { it.kind == VisualKind.HEADING })
    assertTrue(
      book.visualItems.any {
        it.kind == VisualKind.IMAGE && it.text == UserTextFallbacks.IMAGE
      },
    )
  }

  @Test
  fun utf16OffsetsSurviveEmojiSurrogatePairsAndCombiningMarks() {
    val book = parse("<p>😀 Сло́во 😀</p>")

    assertEquals(listOf("😀", "Сло́во", "😀"), book.wordTexts())
    for (word in book.words) {
      assertEquals(book.textOf(word), book.readingText.substring(word.startChar, word.endChar))
    }
    assertEquals(2, book.words[0].endChar)
    assertEquals(10, book.words[2].startChar)
  }

  @Test
  fun cjkParagraphRemainsOneBoundedTokenAndLongTokensSplitByGraphemes() {
    val cjk = parse("<p>漢字かな交じり文</p>")
    assertEquals(listOf("漢字かな交じり文"), cjk.wordTexts())

    val long = "e\u0301".repeat(101)
    val split = parse("<p>$long</p>")
    assertEquals(2, split.words.size)
    assertEquals(
      java.text.Normalizer.normalize(long, java.text.Normalizer.Form.NFC),
      split.wordTexts().joinToString(""),
    )
    assertTrue(split.wordTexts().all { UnicodeText.graphemes(it).size <= 96 })
  }

  @Test
  fun noteOnSplitTokenAttachesToLastSegmentAndDoesNotAddWord() {
    val long = "б".repeat(100)
    val book =
      parseWithNotes(
        body = "<p>$long<a type=\"note\" l:href=\"#n\">1</a></p>",
        notes = "<body name=\"notes\"><section id=\"n\"><p>Сноска.</p></section></body>",
      )

    assertEquals(2, book.words.size)
    assertTrue(book.words.first().noteIds.isEmpty())
    assertEquals(listOf("n"), book.words.last().noteIds)
  }

  @Test
  fun verseAndTableBoundariesAreRetained() {
    val book =
      parse(
        """
        <poem><stanza><v>Строка первая</v><v>Строка вторая</v></stanza></poem>
        <table><tr><td>Ячейка</td><td>два</td></tr></table>
        """,
      )

    assertEquals(BoundaryKind.VERSE_STANZA, book.words[3].boundary)
    assertEquals(BoundaryKind.TABLE_ROW, book.words.last().boundary)
    assertEquals("Ячейка два", book.wordTexts().takeLast(2).joinToString(" "))
  }

  @Test
  fun sentenceSegmentationAvoidsDecimalsInitialsAbbreviationsAndUrls() {
    val book =
      parse("<p>Точка 3.14, А. П. Иванов, г. Москва и https://example.com/a.b без разрыва.</p>")

    assertEquals(1, book.words.map { it.sentenceId }.distinct().size)
    assertTrue(book.words.dropLast(1).all { !it.endsSentence })
    assertTrue(book.words.last().endsSentence)
  }

  @Test
  fun paragraphBoundaryEndsSentenceEvenWithoutPunctuation() {
    val book = parse("<p>Первый абзац без точки</p><p>Второй.</p>")

    assertEquals(0, book.words[3].sentenceId)
    assertEquals(1, book.words[4].sentenceId)
    assertNotEquals(book.words[3].sentenceId, book.words[4].sentenceId)
  }

  private fun parse(body: String): ParsedFb2 {
    val xml = SyntheticFb2.xml(body = body)
    val file = temporaryFolder.newFile()
    file.writeText(xml, Charsets.UTF_8)
    return SyntheticFb2.parser().parse(file, "tokenization.fb2")
  }

  private fun parseWithNotes(body: String, notes: String): ParsedFb2 {
    val xml = SyntheticFb2.xml(body = body, notes = notes)
    val file = temporaryFolder.newFile()
    file.writeText(xml, Charsets.UTF_8)
    return SyntheticFb2.parser().parse(file, "tokenization.fb2")
  }
}
