package com.upr1se.kadr.fb2

import java.io.File
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import com.upr1se.kadr.core.UserTextFallbacks
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.kxml2.io.KXmlParser
import kotlinx.coroutines.CancellationException

class Fb2ParserTest {
  @get:Rule val temporaryFolder = TemporaryFolder()

  private fun newParser(limits: Fb2Limits = Fb2Limits()) =
    Fb2Parser(limits = limits, parserFactory = { KXmlParser() })

  @Test
  fun simpleBookParsesMetadataChaptersAndInlineText() {
    val xml =
      bookXml(
        title = "Синтетическая книга",
        author = "<first-name>Анна</first-name><last-name>Тестова</last-name>",
        body =
          """
          <section>
            <title><p>Первая глава</p></title>
            <p>Начало <emphasis>сло</emphasis>ва и конец.</p>
            <section>
              <title>Вложенная</title>
              <p>Ещё одна строка.</p>
            </section>
          </section>
          <section>
            <title>Вторая</title>
            <p>Финал!</p>
          </section>
          """,
      )

    val book = parseText(xml)

    assertEquals("Синтетическая книга", book.metadata.title)
    assertEquals("Анна Тестова", book.metadata.authorDisplay)
    assertEquals("ru", book.metadata.language)
    assertEquals("Начало слова и конец.\n\nЕщё одна строка.\n\nФинал!", book.readingText)
    assertEquals(2, book.chapterCount)
    assertEquals(3, book.sections.size)
    assertEquals("Первая глава", book.sections.first().title)
    assertEquals("Вложенная", book.sections[1].title)
    assertEquals(listOf("Начало", "слова", "и", "конец.", "Ещё", "одна", "строка.", "Финал!"), book.wordTexts())
    assertEquals(3, book.visualItems.size)
    assertTrue(book.visualItems.first().kind == VisualKind.HEADING)
  }

  @Test
  fun notesStayOutOfMainTextAndAttachToPreviousWord() {
    val xml =
      bookXml(
        body =
          """
          <section>
            <title>Глава</title>
            <p>Слово<a type="note" l:href="#n1">1</a> продолжается.</p>
          </section>
          """,
        notesBody =
          """
          <body name="notes">
            <section id="n1">
              <p>Текст сноски.</p>
            </section>
          </body>
          """,
      )

    val book = parseText(xml)

    assertEquals("Слово продолжается.", book.readingText)
    assertEquals(listOf("Слово", "продолжается."), book.wordTexts())
    assertEquals(listOf("n1"), book.words.first().noteIds)
    assertEquals("Текст сноски.", book.notes.single().blocks.single())
  }

  @Test
  fun missingNoteTargetIsWarningOnly() {
    val xml =
      bookXml(
        body =
          """
          <section><title>Глава</title><p>Слово<a type="note" l:href="#missing">1</a>.</p></section>
          """,
      )

    val book = parseText(xml)

    assertTrue(book.warnings.any { it.code == Fb2WarningCode.MISSING_NOTE })
    assertTrue(book.words.first().noteIds.isEmpty())
  }

  @Test
  fun textBeforeFirstSectionBecomesSyntheticSection() {
    val xml =
      bookXml(
        body =
          """
          <p>До главы.</p>
          <section><title>Глава</title><p>Внутри.</p></section>
          """,
      )

    val book = parseText(xml)

    assertTrue(book.sections.first().isSynthetic)
    assertEquals("До главы.\n\nВнутри.", book.readingText)
    assertEquals(UserTextFallbacks.BOOK, book.sections.first().title)
  }

  @Test
  fun bookWithoutSectionsUsesSyntheticBookChapter() {
    val xml = bookXml(body = "<p>Одна короткая книга.</p>")

    val book = parseText(xml)

    assertEquals(0, book.chapterCount)
    assertEquals(UserTextFallbacks.BOOK, book.sections.single().title)
    assertTrue(book.sections.single().isSynthetic)
  }

  @Test
  fun missingTitleAndAuthorUseStableUiFallbackTokens() {
    val xml =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <FictionBook><body><p>Текст.</p></body></FictionBook>
      """.trimIndent()

    val book =
      newParser().parse(
        writeBytes(xml.toByteArray(Charsets.UTF_8)),
        ".fb2",
      )

    assertEquals(UserTextFallbacks.UNTITLED, book.metadata.title)
    assertEquals(UserTextFallbacks.UNKNOWN_AUTHOR, book.metadata.authorDisplay)
    assertEquals(UserTextFallbacks.BOOK, book.sections.single().title)
  }

  @Test
  fun poemsTablesAndBodyImagesAreLinearized() {
    val xml =
      bookXml(
        body =
          """
          <section>
            <title>Смешанная</title>
            <poem>
              <stanza><v>Первая строка</v><v>Вторая строка</v></stanza>
            </poem>
            <table>
              <tr><td>ячейка 1</td><td></td><td>ячейка 2</td></tr>
            </table>
            <image l:href="#picture"/>
            <unknown><p>Неизвестный тег сохраняется.</p></unknown>
          </section>
          """,
      )

    val book = parseText(xml)

    assertTrue(book.readingText.contains("Первая строка\nВторая строка"))
    assertTrue(book.readingText.contains("ячейка 1 ячейка 2"))
    assertTrue(book.readingText.contains("Неизвестный тег сохраняется."))
    assertTrue(
      book.visualItems.any {
        it.kind == VisualKind.IMAGE && it.text == UserTextFallbacks.IMAGE
      },
    )
    assertEquals(BoundaryKind.VERSE_STANZA, book.words.last { book.textOf(it) == "строка" }.boundary)
  }

  @Test
  fun longTokenIsSplitOnGraphemeBoundariesWithoutLoss() {
    val token = "e\u0301".repeat(101)
    val xml = bookXml(body = "<section><title>Глава</title><p>$token</p></section>")

    val book = parseText(xml)

    assertEquals(2, book.words.size)
    assertEquals(
      java.text.Normalizer.normalize(token, java.text.Normalizer.Form.NFC),
      book.wordTexts().joinToString(""),
    )
    assertTrue(book.wordTexts().all { UnicodeText.graphemes(it).size <= Fb2Versions.MAX_TOKEN_GRAPHEMES })
    assertTrue(book.warnings.any { it.code == Fb2WarningCode.LONG_TOKEN_SPLIT })
  }

  @Test
  fun coverBinaryIsDecodedButBodyImageIsNot() {
    val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    val encoded = java.util.Base64.getEncoder().encodeToString(bytes)
    val xml =
      bookXml(
        cover = true,
        body = "<section><title>Глава</title><p>Текст.</p><image l:href=\"#other\"/></section>",
      ).replace("</FictionBook>", "<binary id=\"cover\" content-type=\"image/png\">$encoded</binary></FictionBook>")

    val book = parseText(xml)

    assertNotNull(book.coverBytes)
    assertArrayEquals(bytes, book.coverBytes)
  }

  @Test
  fun windows1251AndUtf16BomProduceSameCanonicalText() {
    val source = bookXml(body = "<section><title>Глава</title><p>Привет, мир.</p></section>")
    val winXml = source.replace("encoding=\"UTF-8\"", "encoding=\"windows-1251\"")
    val cp1251 = winXml.toByteArray(Charset.forName("windows-1251"))
    val utf16Le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + source.toByteArray(Charsets.UTF_16LE)
    val utf16Be = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + source.toByteArray(Charsets.UTF_16BE)

    val expected = parseText(source).readingText
    assertEquals(expected, parseBytes(cp1251).readingText)
    assertEquals(expected, parseBytes(utf16Le).readingText)
    assertEquals(expected, parseBytes(utf16Be).readingText)
  }

  @Test
  fun encodingDeclarationConflictUsesBomAndAddsWarning() {
    val xml = bookXml(body = "<p>Текст.</p>")
    val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
      xml.replace("encoding=\"UTF-8\"", "encoding=\"windows-1251\"").toByteArray(Charsets.UTF_8)

    val book = parseBytes(bytes)

    assertEquals("Текст.", book.readingText)
    assertTrue(book.warnings.any { it.code == Fb2WarningCode.ENCODING_DECLARATION_CONFLICT })
  }

  @Test
  fun unknownDeclaredEncodingIsRejected() {
    val xml = bookXml(body = "<p>Текст.</p>").replace("UTF-8", "x-user-unknown")

    assertEquals(
      ImportErrorCode.UNSUPPORTED_ENCODING,
      expectImportError(xml.toByteArray()).code,
    )
  }

  @Test
  fun invalidUtf8FailsWithoutReplacementCharacter() {
    val prefix = bookXml(body = "<p>").toByteArray(Charsets.UTF_8)
    val suffix = "</p></body></FictionBook>".toByteArray(Charsets.UTF_8)
    val bytes = prefix + byteArrayOf(0xC3.toByte()) + suffix

    val error = expectImportError(bytes)
    assertTrue(
      error.code == ImportErrorCode.BAD_ENCODING || error.code == ImportErrorCode.BAD_XML,
    )
  }

  @Test
  fun doctypeAndEntitiesAreRejected() {
    val xml =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <!DOCTYPE FictionBook [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
      <FictionBook><description><title-info><book-title>Bad</book-title></title-info></description><body><p>&xxe;</p></body></FictionBook>
      """.trimIndent()

    assertEquals(ImportErrorCode.DOCTYPE_FORBIDDEN, expectImportError(xml.toByteArray()).code)
  }

  @Test
  fun nonFb2AndEmptyBooksAreRejected() {
    assertEquals(
      ImportErrorCode.NOT_FB2,
      expectImportError("<root><body><p>Text</p></body></root>".toByteArray()).code,
    )
    assertEquals(
      ImportErrorCode.NO_TEXT,
      expectImportError(bookXml(body = "<section><title>Empty</title></section>").toByteArray()).code,
    )
  }

  @Test
  fun zipWithSingleFb2IsParsedAndPathIsRetained() {
    val zip = writeZip("book.fb2" to bookXml(body = "<p>Из архива.</p>").toByteArray())

    val inspection = newParser().inspect(zip, "book.fb2.zip")
    assertTrue(inspection is Fb2Inspection.SingleBook)
    val book = newParser().parse(zip, "book.fb2.zip")

    assertEquals(BookFormat.FB2_ZIP, book.format)
    assertEquals("book.fb2", book.archiveEntryPath)
    assertEquals("Из архива.", book.readingText)
  }

  @Test
  fun zipWithMultipleBooksRequiresExplicitVariant() {
    val zip =
      writeZip(
        "a.fb2" to bookXml(title = "A", body = "<p>А.</p>").toByteArray(),
        "b.FB2" to bookXml(title = "B", body = "<p>Б.</p>").toByteArray(),
      )
    val inspection = newParser().inspect(zip, "collection.zip")

    assertTrue(inspection is Fb2Inspection.NeedsSelection)
    assertEquals(2, inspection.candidates.size)
    try {
      newParser().parse(zip, "collection.zip")
      fail("Selection should be required")
    } catch (expected: Fb2SelectionRequiredException) {
      assertEquals(2, expected.candidates.size)
    }
    val second = newParser().parse(zip, "collection.zip", inspection.candidates[1].entryIndex)
    assertEquals("B", second.metadata.title)
    assertEquals("b.FB2", second.archiveEntryPath)
  }

  @Test
  fun duplicateZipPathsRemainDistinctCandidates() {
    val zip =
      writeZip(
        "same.fb2" to bookXml(title = "One", body = "<p>Один.</p>").toByteArray(),
        "same.fb2" to bookXml(title = "Two", body = "<p>Два.</p>").toByteArray(),
      )

    val inspection = newParser().inspect(zip, "duplicates.zip")

    check(inspection is Fb2Inspection.NeedsSelection)
    assertEquals(listOf("same.fb2", "same.fb2 (вариант 2)"), inspection.candidates.map { it.displayPath })
    assertTrue(inspection.candidates[0].entryIndex != inspection.candidates[1].entryIndex)
  }

  @Test
  fun zipPathTraversalIsRejected() {
    val zip = writeZip("../escape.fb2" to bookXml(body = "<p>Text</p>").toByteArray())

    val error =
      try {
        newParser().inspect(zip, "unsafe.zip")
        null
      } catch (exception: Fb2ImportException) {
        exception
      }
    assertNotNull(error)
    assertEquals(ImportErrorCode.UNSAFE_ZIP_PATH, error?.code)
  }

  @Test
  fun rawXmlWithZipExtensionIsDetectedByContent() {
    val file = temporaryFolder.newFile("wrong.zip")
    file.writeText(bookXml(body = "<p>Не архив.</p>"), Charsets.UTF_8)

    val inspection = newParser().inspect(file, "wrong.zip")

    assertTrue(inspection is Fb2Inspection.SingleBook)
    assertEquals(BookFormat.FB2, (inspection as Fb2Inspection.SingleBook).format)
  }

  @Test
  fun sourceSizeAndXmlDepthLimitsAreEnforced() {
    val sizeLimited = newParser(Fb2Limits(maxSourceBytes = 20))
    val sizeError =
      try {
        sizeLimited.inspect(writeBytes(bookXml(body = "<p>Text.</p>").toByteArray()), "book.fb2")
        null
      } catch (exception: Fb2ImportException) {
        exception
      }
    assertEquals(ImportErrorCode.SOURCE_TOO_LARGE, sizeError?.code)

    val deepXml = bookXml(body = "<p>${"<x>".repeat(8)}deep${"</x>".repeat(8)}</p>")
    val depthError =
      try {
        newParser(Fb2Limits(maxXmlDepth = 6)).parse(writeBytes(deepXml.toByteArray()), "deep.fb2")
        null
      } catch (exception: Fb2ImportException) {
        exception
      }
    assertEquals(ImportErrorCode.XML_DEPTH_EXCEEDED, depthError?.code)
  }

  @Test
  fun parserChecksCooperativeCancellationWhileStreaming() {
    val file = writeBytes(bookXml(body = "<p>Текст для отмены.</p>").toByteArray())
    var checks = 0
    val cancelled =
      try {
        newParser().parse(
          file,
          "cancel.fb2",
          cancellationCheck = {
            checks++
            checks > 3
          },
        )
        false
      } catch (_: CancellationException) {
        true
      }

    assertTrue(cancelled)
    assertTrue(checks >= 4)
  }

  @Test
  fun multipleMainBodiesMaintainDocumentOrder() {
    val xml =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <FictionBook>
        <description><title-info><book-title>Bodies</book-title></title-info></description>
        <body><p>Первый.</p></body>
        <body><p>Второй.</p></body>
      </FictionBook>
      """.trimIndent()

    val book = parseText(xml)

    assertEquals("Первый.\n\nВторой.", book.readingText)
    assertEquals(2, book.sections.count { it.isSynthetic })
  }

  @Test
  fun metadataAndNotesAreTruncatedAtGraphemeBoundariesWithWarnings() {
    val longTitle = "Ж".repeat(30)
    val longAuthor = "А".repeat(30)
    val longNote = "н".repeat(40)
    val xml =
      bookXml(
        title = longTitle,
        author = "<first-name>$longAuthor</first-name>",
        body = "<p>Слово<a type=\"note\" l:href=\"#n\">1</a>.</p>",
        notesBody = "<body name=\"notes\"><section id=\"n\"><p>$longNote</p></section></body>",
      )
    val limited =
      Fb2Parser(
        limits =
          Fb2Limits(
            maxSectionTitleGraphemes = 5,
            maxAuthorGraphemes = 7,
            maxNoteCodeUnits = 10,
          ),
        parserFactory = { KXmlParser() },
      )
    val file = writeBytes(xml.toByteArray())

    val book = limited.parse(file, "truncated.fb2")

    assertEquals(5, UnicodeText.graphemes(book.metadata.title).size)
    assertEquals(7, UnicodeText.graphemes(book.metadata.authorDisplay).size)
    assertEquals(10, book.notes.single().blocks.single().length)
    assertTrue(book.warnings.any { it.code == Fb2WarningCode.METADATA_TRUNCATED })
    assertTrue(book.warnings.any { it.code == Fb2WarningCode.NOTE_TRUNCATED })
  }

  @Test
  fun multipleNoteTargetsRemainInOrderAndUnknownInlineTextIsPreserved() {
    val xml =
      bookXml(
        body =
          "<p><unknown>Слово</unknown><a type=\"note\" l:href=\"#a\">1</a><a type=\"note\" l:href=\"#b\">2</a>.</p>",
        notesBody =
          "<body name=\"notes\"><section id=\"a\"><p>Первая.</p></section><section id=\"b\"><p>Вторая.</p></section></body>",
      )

    val book = parseText(xml)

    assertEquals(listOf("a", "b"), book.words.single().noteIds)
    assertEquals("Слово.", book.readingText)
    assertEquals(2, book.notes.size)
  }

  @Test
  fun damagedCoverDoesNotBlockBookImport() {
    val xml =
      bookXml(
        cover = true,
        body = "<p>Текст.</p>",
      ).replace(
        "</FictionBook>",
        "<binary id=\"cover\" content-type=\"image/png\">not-base64!</binary></FictionBook>",
      )

    val book = parseText(xml)

    assertEquals("Текст.", book.readingText)
    assertEquals(null, book.coverBytes)
    assertTrue(book.warnings.any { it.code == Fb2WarningCode.COVER_REJECTED })
  }

  @Test
  fun zipWithoutFb2AndDamagedCrcAreRejected() {
    val noBook = writeZip("notes.txt" to "text".toByteArray())
    assertEquals(
      ImportErrorCode.ZIP_HAS_NO_FB2,
      runCatching { newParser().inspect(noBook, "none.zip") }
        .exceptionOrNull()
        .let { (it as Fb2ImportException).code },
    )

    val damaged = writeZip("book.fb2" to bookXml(body = "<p>CRC text.</p>").toByteArray())
    val bytes = damaged.readBytes()
    val offset = bytes.size / 2
    bytes[offset + 2] = (bytes[offset + 2].toInt() xor 1).toByte()
    damaged.writeBytes(bytes)
    val error =
      runCatching { newParser().inspect(damaged, "damaged.zip") }
        .exceptionOrNull()
    assertTrue(error is Fb2ImportException)
    assertEquals(ImportErrorCode.BAD_ZIP, (error as Fb2ImportException).code)
  }

  @Test
  fun zipEntryAndXmlAttributeLimitsFailClosed() {
    val bomb = writeZip("book.fb2" to ByteArray(128) { 'x'.code.toByte() })
    val zipError =
      runCatching {
        Fb2Parser(
          limits = Fb2Limits(maxZipEntryBytes = 32),
          parserFactory = { KXmlParser() },
        ).inspect(bomb, "bomb.zip")
      }.exceptionOrNull()
    assertTrue(zipError is Fb2ImportException)
    assertEquals(ImportErrorCode.ZIP_LIMIT_EXCEEDED, (zipError as Fb2ImportException).code)

    val attributes =
      bookXml(body = "<p class=\"one\" id=\"two\">Текст.</p>")
    val attributeError =
      runCatching {
        Fb2Parser(
          limits = Fb2Limits(maxAttributesPerElement = 1),
          parserFactory = { KXmlParser() },
        ).parse(writeBytes(attributes.toByteArray()), "attributes.fb2")
      }.exceptionOrNull()
    assertTrue(attributeError is Fb2ImportException)
    assertEquals(
      ImportErrorCode.XML_VALUE_TOO_LARGE,
      (attributeError as Fb2ImportException).code,
    )
  }

  private fun parseText(xml: String): ParsedFb2 = parseBytes(xml.toByteArray(Charsets.UTF_8))

  private fun parseBytes(bytes: ByteArray): ParsedFb2 {
    val file = writeBytes(bytes)
    return newParser().parse(file, "synthetic.fb2")
  }

  private fun writeBytes(bytes: ByteArray): File {
    val file = temporaryFolder.newFile()
    FileOutputStream(file).use { it.write(bytes) }
    return file
  }

  private fun expectImportError(bytes: ByteArray): Fb2ImportException {
    val file = writeBytes(bytes)
    return try {
      newParser().parse(file, "invalid.fb2")
      fail("Expected Fb2ImportException")
      error("unreachable")
    } catch (exception: Fb2ImportException) {
      exception
    }
  }

  private fun writeZip(vararg entries: Pair<String, ByteArray>): File {
    val file = temporaryFolder.newFile()
    val localData = ByteArrayOutputStream()
    val centralData = ByteArrayOutputStream()
    val localOutput = DataOutputStream(localData)
    val centralOutput = DataOutputStream(centralData)
    for ((name, bytes) in entries) {
      val nameBytes = name.toByteArray(Charsets.UTF_8)
      val crc = CRC32().apply { update(bytes) }.value
      val localOffset = localData.size()
      localOutput.writeLittleEndianInt(0x04034B50)
      localOutput.writeLittleEndianShort(20)
      localOutput.writeLittleEndianShort(0x0800)
      localOutput.writeLittleEndianShort(0)
      localOutput.writeLittleEndianShort(0)
      localOutput.writeLittleEndianShort(0)
      localOutput.writeLittleEndianInt(crc.toInt())
      localOutput.writeLittleEndianInt(bytes.size)
      localOutput.writeLittleEndianInt(bytes.size)
      localOutput.writeLittleEndianShort(nameBytes.size)
      localOutput.writeLittleEndianShort(0)
      localOutput.write(nameBytes)
      localOutput.write(bytes)

      centralOutput.writeLittleEndianInt(0x02014B50)
      centralOutput.writeLittleEndianShort(20)
      centralOutput.writeLittleEndianShort(20)
      centralOutput.writeLittleEndianShort(0x0800)
      centralOutput.writeLittleEndianShort(0)
      centralOutput.writeLittleEndianShort(0)
      centralOutput.writeLittleEndianShort(0)
      centralOutput.writeLittleEndianInt(crc.toInt())
      centralOutput.writeLittleEndianInt(bytes.size)
      centralOutput.writeLittleEndianInt(bytes.size)
      centralOutput.writeLittleEndianShort(nameBytes.size)
      centralOutput.writeLittleEndianShort(0)
      centralOutput.writeLittleEndianShort(0)
      centralOutput.writeLittleEndianShort(0)
      centralOutput.writeLittleEndianShort(0)
      centralOutput.writeLittleEndianInt(0)
      centralOutput.writeLittleEndianInt(localOffset)
      centralOutput.write(nameBytes)
    }
    localOutput.flush()
    centralOutput.flush()
    val centralOffset = localData.size()
    localData.write(centralData.toByteArray())
    val eocd = DataOutputStream(localData)
    eocd.writeLittleEndianInt(0x06054B50)
    eocd.writeLittleEndianShort(0)
    eocd.writeLittleEndianShort(0)
    eocd.writeLittleEndianShort(entries.size)
    eocd.writeLittleEndianShort(entries.size)
    eocd.writeLittleEndianInt(centralData.size())
    eocd.writeLittleEndianInt(centralOffset)
    eocd.writeLittleEndianShort(0)
    eocd.flush()
    file.writeBytes(localData.toByteArray())
    return file
  }

  private fun DataOutputStream.writeLittleEndianShort(value: Int) {
    writeByte(value and 0xFF)
    writeByte((value ushr 8) and 0xFF)
  }

  private fun DataOutputStream.writeLittleEndianInt(value: Int) {
    writeByte(value and 0xFF)
    writeByte((value ushr 8) and 0xFF)
    writeByte((value ushr 16) and 0xFF)
    writeByte((value ushr 24) and 0xFF)
  }

  private fun bookXml(
    title: String = "Тестовая книга",
    author: String = "<first-name>Иван</first-name><last-name>Тестов</last-name>",
    body: String,
    notesBody: String = "",
    cover: Boolean = false,
  ): String =
    """
    <?xml version="1.0" encoding="UTF-8"?>
    <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0"
                 xmlns:l="http://www.w3.org/1999/xlink">
      <description>
        <title-info>
          <book-title>$title</book-title>
          <author>$author</author>
          <lang>ru</lang>
          ${if (cover) "<coverpage><image l:href=\"#cover\"/></coverpage>" else ""}
        </title-info>
      </description>
      <body>$body</body>
      $notesBody
    </FictionBook>
    """.trimIndent()
}
