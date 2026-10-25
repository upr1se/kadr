package com.upr1se.kadr.data.storage

import com.upr1se.kadr.testing.SyntheticFb2
import com.upr1se.kadr.domain.reader.ReadingDocument
import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReadingIndexTest {
  @get:Rule val temporaryFolder = TemporaryFolder()

  @Test
  fun indexKeepsItemOrderBoundariesNotesAndSentenceRanges() {
    val xml =
      SyntheticFb2.xml(
        body =
          """
          <section>
            <title>Первая</title>
            <p>Первый<a type="note" l:href="#n1">1</a>, второй.</p>
            <image l:href="#image"/>
            <section><title>Вложенная</title><p>Третий?</p></section>
          </section>
          <section><title>Вторая</title><p>Четвёртый.</p></section>
          """,
        notes =
          """
          <body name="notes"><section id="n1"><p>Сноска.</p></section></body>
          """,
      )
    val book = SyntheticFb2.parser().parse(write("source.fb2", xml), "source.fb2")
    val indexFile = temporaryFolder.newFile("reading_index.bin")

    ReadingIndexWriter().write(book, indexFile)

    ReadingIndexReader.open(indexFile).use { index ->
      assertTrue(index.verifyChecksum())
      assertEquals(book.wordCount, index.header.wordCount)
      assertEquals(book.sentenceCount, index.header.sentenceCount)
      assertEquals(book.visualItems.size, index.header.visualCount)
      assertEquals(book.wordCount + book.visualItems.size, index.header.itemCount)
      assertEquals(book.chapterCount, index.header.chapterCount)

      val kinds = (0 until index.header.itemCount).map { index.readItem(it).kind }
      assertEquals(IndexItemKind.HEADING, kinds[0])
      assertEquals(IndexItemKind.WORD, kinds[1])
      assertEquals(IndexItemKind.WORD, kinds[2])
      assertEquals(IndexItemKind.IMAGE, kinds[3])
      assertEquals(IndexItemKind.HEADING, kinds[4])
      assertEquals(IndexItemKind.WORD, kinds[5])

      val firstWord = index.readWord(0)
      assertEquals(1, firstWord.itemOrdinal)
      assertEquals(listOf("n1"), index.noteIdsForWord(0))
      val sentence = index.readSentence(firstWord.sentenceId)
      assertEquals(0, sentence.firstWordIndex)
      assertTrue(sentence.lastWordIndex >= sentence.firstWordIndex)
      assertEquals("Первая", index.sectionForWord(0).title)
    }
  }

  @Test
  fun truncatedOrInflatedIndexIsRejectedWithoutLargeAllocation() {
    val xml =
      SyntheticFb2.xml(
        body = "<section><title>Глава</title><p>Один два три.</p></section>",
      )
    val book = SyntheticFb2.parser().parse(write("source.fb2", xml), "source.fb2")
    val indexFile = temporaryFolder.newFile("valid.bin")
    ReadingIndexWriter().write(book, indexFile)

    val inflated = temporaryFolder.newFile("inflated.bin")
    inflated.writeBytes(indexFile.readBytes())
    RandomAccessFile(inflated, "rw").use {
      it.seek(64)
      it.writeInt(Int.MAX_VALUE)
    }
    assertInvalid(inflated)

    val truncated = temporaryFolder.newFile("truncated.bin")
    truncated.writeBytes(indexFile.readBytes().copyOf(100))
    assertInvalid(truncated)
  }

  @Test
  fun indexFlagsAuthoritativeChecksumCorruption() {
    val xml = SyntheticFb2.xml(body = "<p>Один два.</p>")
    val book = SyntheticFb2.parser().parse(write("source.fb2", xml), "source.fb2")
    val indexFile = temporaryFolder.newFile("index.bin")
    ReadingIndexWriter().write(book, indexFile)
    RandomAccessFile(indexFile, "rw").use {
      it.seek(ReadingIndexWriter.HEADER_SIZE.toLong() + 4)
      val original = it.readByte()
      it.seek(ReadingIndexWriter.HEADER_SIZE.toLong() + 4)
      it.writeByte(original.toInt() xor 1)
    }

    ReadingIndexReader.open(indexFile).use { index ->
      assertTrue(!index.verifyChecksum())
    }
  }

  @Test
  fun longSentencePreviewUsesCorrectEllipsesAroundCurrentWord() {
    val sentence = List(1_200) { "word$it" }.joinToString(" ") + "."
    val book =
      SyntheticFb2.parser().parse(
        write("source.fb2", SyntheticFb2.xml(body = "<p>$sentence</p>")),
        "source.fb2",
      )
    val indexFile = temporaryFolder.newFile("sentence.bin")
    ReadingIndexWriter().write(book, indexFile)

    ReadingIndexReader.open(indexFile).use { index ->
      val document =
        ReadingDocument(
          text = book.readingText,
          index = index,
          sections = book.sections,
          notes = book.notes.associateBy { it.noteId },
        )
      val beginning = document.sentencePreview(0, maxGraphemes = 1_000)
      val middle = document.sentencePreview(600, maxGraphemes = 1_000)

      assertTrue(!beginning.startsWith("…"))
      assertTrue(beginning.endsWith("…"))
      assertTrue(middle.startsWith("…"))
      assertTrue(middle.endsWith("…"))
    }
  }

  private fun assertInvalid(file: File) {
    val error = runCatching { ReadingIndexReader.open(file) }.exceptionOrNull()
    assertTrue(error is StorageException)
    assertEquals(StorageErrorCode.INDEX_INVALID, (error as StorageException).code)
  }

  private fun write(name: String, contents: String): File =
    temporaryFolder.newFile(name).also { it.writeText(contents, Charsets.UTF_8) }
}
