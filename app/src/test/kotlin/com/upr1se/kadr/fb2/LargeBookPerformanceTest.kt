package com.upr1se.kadr.fb2

import com.upr1se.kadr.data.storage.ReadingIndexReader
import com.upr1se.kadr.data.storage.ReadingIndexWriter
import com.upr1se.kadr.testing.SyntheticFb2
import java.io.File
import kotlin.system.measureTimeMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LargeBookPerformanceTest {
  @get:Rule val temporaryFolder = TemporaryFolder()

  @Test(timeout = 180_000)
  fun oneMillionWordsParseAndIndexWithoutLosingOrder() {
    val words = 1_000_000
    val wordsPerParagraph = 1_000
    val body =
      buildString {
        repeat(words / wordsPerParagraph) { paragraph ->
          append("<p>")
          repeat(wordsPerParagraph) { withinParagraph ->
            if (withinParagraph > 0) append(' ')
            append("слово")
            append(paragraph * wordsPerParagraph + withinParagraph)
          }
          append("</p>")
        }
      }
    val source = temporaryFolder.newFile("million.fb2")
    source.writeText(SyntheticFb2.xml(title = "Миллион", body = body), Charsets.UTF_8)
    val indexFile = temporaryFolder.newFile("million.bin")

    lateinit var parsed: ParsedFb2
    val elapsed =
      measureTimeMillis {
        parsed = SyntheticFb2.parser().parse(source, "million.fb2")
        ReadingIndexWriter().write(parsed, indexFile)
      }

    assertEquals(words, parsed.wordCount)
    assertTrue(parsed.readingText.startsWith("слово0 слово1"))
    assertTrue(parsed.readingText.endsWith("слово999999"))
    ReadingIndexReader.open(indexFile).use { index ->
      assertEquals(words, index.header.wordCount)
      assertEquals("слово0", parsed.readingText.substring(index.readWord(0).startChar, index.readWord(0).endChar))
      val last = index.readWord(words - 1)
      assertEquals("слово999999", parsed.readingText.substring(last.startChar, last.endChar))
      assertTrue(index.verifyChecksum())
    }
    assertTrue("Elapsed time was ${elapsed}ms", elapsed < 180_000)
  }
}
