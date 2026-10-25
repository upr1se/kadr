package com.upr1se.kadr.fb2

import com.upr1se.kadr.core.UserTextFallbacks
import android.util.Xml
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.io.SequenceInputStream
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.Locale
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import javax.xml.parsers.ParserConfigurationException
import kotlinx.coroutines.CancellationException
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException

class Fb2Parser(
  private val limits: Fb2Limits = Fb2Limits(),
  private val parserFactory: () -> XmlPullParser = { Xml.newPullParser() },
) {
  fun inspect(
    file: File,
    sourceFileName: String,
    cancellationCheck: () -> Boolean = { false },
  ): Fb2Inspection {
    validateSourceFile(file)
    val prefix = readPrefix(file, ZIP_MAGIC_PROBE_BYTES)
    return if (isZip(prefix)) {
      val candidates = scanZip(file, cancellationCheck)
      when (candidates.size) {
        0 -> throw Fb2ImportException(ImportErrorCode.ZIP_HAS_NO_FB2)
        1 ->
          Fb2Inspection.SingleBook(
            format = BookFormat.FB2_ZIP,
            selectedCandidate = candidates.single(),
          )
        else -> Fb2Inspection.NeedsSelection(candidates)
      }
    } else {
      Fb2Inspection.SingleBook(format = BookFormat.FB2, selectedCandidate = null)
    }
  }

  fun parse(
    file: File,
    sourceFileName: String,
    selectedEntryIndex: Int? = null,
    cancellationCheck: () -> Boolean = { false },
  ): ParsedFb2 {
    val inspection = inspect(file, sourceFileName, cancellationCheck)
    return parse(file, sourceFileName, inspection, selectedEntryIndex, cancellationCheck)
  }

  fun parse(
    file: File,
    sourceFileName: String,
    inspection: Fb2Inspection,
    selectedEntryIndex: Int? = null,
    cancellationCheck: () -> Boolean = { false },
  ): ParsedFb2 {
    validateSourceFile(file)
    val safeSourceName =
      UnicodeText.sanitizeDisplayFileName(sourceFileName, limits.maxSourceNameGraphemes)
    return when (inspection) {
      is Fb2Inspection.SingleBook -> {
        when (inspection.format) {
          BookFormat.FB2 -> parseRaw(file, safeSourceName, cancellationCheck)
          BookFormat.FB2_ZIP ->
            parseZipEntry(
              file = file,
              sourceName = safeSourceName,
              selectedEntryIndex =
                selectedEntryIndex ?: inspection.selectedCandidate?.entryIndex
                  ?: throw Fb2ImportException(ImportErrorCode.ZIP_HAS_NO_FB2),
              cancellationCheck = cancellationCheck,
            )
        }
      }
      is Fb2Inspection.NeedsSelection -> {
        val index =
          selectedEntryIndex
            ?: throw Fb2SelectionRequiredException(inspection.candidates)
        if (inspection.candidates.none { it.entryIndex == index }) {
          throw Fb2ImportException(ImportErrorCode.ZIP_HAS_NO_FB2)
        }
        parseZipEntry(file, safeSourceName, index, cancellationCheck)
      }
    }
  }

  private fun parseRaw(
    file: File,
    sourceName: String,
    cancellationCheck: () -> Boolean,
  ): ParsedFb2 {
    val stream = BoundedSourceInputStream(FileInputStream(file), limits.maxSourceBytes)
    try {
      return parseXml(stream, sourceName, BookFormat.FB2, null, null, cancellationCheck)
    } finally {
      runCatching { stream.close() }
    }
  }

  private fun parseZipEntry(
    file: File,
    sourceName: String,
    selectedEntryIndex: Int,
    cancellationCheck: () -> Boolean,
  ): ParsedFb2 {
    var entryCounter = 0
    var totalUncompressed = 0L
    var parsed: ParsedFb2? = null
    try {
      ZipInputStream(BufferedInputStream(FileInputStream(file))).use { zip ->
        while (true) {
          checkCancelled(cancellationCheck)
          val entry =
            try {
              zip.nextEntry
            } catch (exception: ZipException) {
              throw zipException(exception)
            } ?: break
          entryCounter++
          if (entryCounter > limits.maxZipEntries) {
            throw Fb2ImportException(ImportErrorCode.ZIP_LIMIT_EXCEEDED)
          }
          validateEntryName(entry.name)
          val bound = EntryBoundedInputStream(zip, limits.maxZipEntryBytes)
          if (entryCounter - 1 == selectedEntryIndex) {
            if (!entry.name.endsWith(".fb2", ignoreCase = true)) {
              throw Fb2ImportException(ImportErrorCode.ZIP_HAS_NO_FB2)
            }
            parsed =
              parseXml(
                input = bound,
                sourceName = sourceName,
                format = BookFormat.FB2_ZIP,
                archiveEntryPath = entry.name,
                archiveEntryIndex = selectedEntryIndex,
                cancellationCheck = cancellationCheck,
              )
            totalUncompressed += bound.consumed
          } else {
            totalUncompressed +=
              drainEntry(
                bound,
                limits.maxUncompressedZipBytes - totalUncompressed,
                cancellationCheck,
              )
          }
          if (totalUncompressed > limits.maxUncompressedZipBytes) {
            throw Fb2ImportException(ImportErrorCode.ZIP_LIMIT_EXCEEDED)
          }
          try {
            zip.closeEntry()
          } catch (exception: ZipException) {
            throw zipException(exception)
          }
        }
      }
    } catch (exception: Fb2ImportException) {
      throw exception
    } catch (exception: IOException) {
      throw Fb2ImportException(ImportErrorCode.BAD_ZIP, exception)
    }
    return parsed ?: throw Fb2ImportException(ImportErrorCode.ZIP_HAS_NO_FB2)
  }

  private fun parseXml(
    input: InputStream,
    sourceName: String,
    format: BookFormat,
    archiveEntryPath: String?,
    archiveEntryIndex: Int?,
    cancellationCheck: () -> Boolean,
  ): ParsedFb2 {
    val decoded =
      try {
        decodedInput(input)
      } catch (exception: Fb2ImportException) {
        throw exception
      } catch (exception: Exception) {
        throw Fb2ImportException(ImportErrorCode.BAD_ENCODING, exception)
      }
    val parser =
      try {
        parserFactory()
      } catch (exception: XmlPullParserException) {
        throw Fb2ImportException(ImportErrorCode.BAD_XML, exception)
      } catch (exception: ParserConfigurationException) {
        throw Fb2ImportException(ImportErrorCode.BAD_XML, exception)
      }
    enableNamespaces(parser)
    try {
      parser.setInput(decoded.reader)
      val session = XmlSession(parser, limits, cancellationCheck)
      session.advanceToRoot()
      val root = parser.name
      if (root != "FictionBook") {
        throw Fb2ImportException(ImportErrorCode.NOT_FB2)
      }
      val book = parseFictionBook(session)
      val metadata = buildMetadata(book, sourceName)
      if (decoded.conflict) {
        book.builder.addWarning(Fb2Warning(Fb2WarningCode.ENCODING_DECLARATION_CONFLICT))
      }
      val result =
        book.builder.build(
          sourceFileName = sourceName,
          format = format,
          archiveEntryPath = archiveEntryPath,
          archiveEntryIndex = archiveEntryIndex,
          metadata = metadata,
        )
      drainReader(decoded.reader)
      return result
    } catch (exception: Fb2ImportException) {
      throw exception
    } catch (exception: XmlPullParserException) {
      val cause = exception.cause
      if (cause is CharacterCodingException ||
        generateSequence(cause) { it.cause }.any { it is CharacterCodingException }
      ) {
        throw Fb2ImportException(ImportErrorCode.BAD_ENCODING, exception)
      }
      throw Fb2ImportException(ImportErrorCode.BAD_XML, exception)
    } catch (exception: CharacterCodingException) {
      throw Fb2ImportException(ImportErrorCode.BAD_ENCODING, exception)
    } catch (exception: IOException) {
      val cause = generateSequence(exception as Throwable) { it.cause }.toList()
      if (cause.any { it is CharacterCodingException }) {
        throw Fb2ImportException(ImportErrorCode.BAD_ENCODING, exception)
      }
      if (cause.any { it is ZipException }) {
        throw zipException(cause.filterIsInstance<ZipException>().first())
      }
      throw Fb2ImportException(ImportErrorCode.BAD_XML, exception)
    }
  }

  private fun parseFictionBook(session: XmlSession): ParsedBookParts {
    val builder = BookDraftBuilder(limits)
    val authors = mutableListOf<String>()
    val metadata = MutableMetadata()
    var coverRef: String? = null
    var mainBodyIndex = 0
    val rootDepth = session.depth
    while (true) {
      val event = session.next()
      if (event == XmlPullParser.END_TAG && session.depth < rootDepth) break
      if (event != XmlPullParser.START_TAG) continue
      when (session.parser.name) {
        "description" -> {
          val description = parseDescription(session)
          if (metadata.title.isBlank()) metadata.title = description.title
          if (metadata.language.isBlank() && description.language.isNotBlank()) {
            metadata.language = description.language
          }
          authors += description.authors
          coverRef = coverRef ?: description.coverRef
        }
        "body" -> {
          val bodyName = getAttribute(session.parser, "name")
          if (bodyName.equals("notes", ignoreCase = true)) {
            parseNotesBody(session, builder)
          } else {
            parseMainBody(session, builder, mainBodyIndex++)
          }
        }
        "binary" -> parseBinary(session, builder, coverRef)
        else -> session.skipSubtree()
      }
    }
    if (!session.isAtEndDocument) {
      while (true) {
        val event = session.next()
        if (event == XmlPullParser.END_DOCUMENT) break
        if (event == XmlPullParser.START_TAG) {
          throw Fb2ImportException(ImportErrorCode.BAD_XML)
        }
      }
    }
    return ParsedBookParts(builder, metadata, authors.distinct())
  }

  private fun parseDescription(session: XmlSession): DescriptionData {
    val titleInfo = MutableTitleInfo()
    val startDepth = session.depth
    while (true) {
      val event = session.next()
      if (event == XmlPullParser.END_TAG && session.depth < startDepth) break
      if (event != XmlPullParser.START_TAG) continue
      when (session.parser.name) {
        "title-info" -> titleInfo.merge(parseTitleInfo(session))
        else -> session.skipSubtree()
      }
    }
    return DescriptionData(
      title = titleInfo.title,
      language = titleInfo.language,
      authors = titleInfo.authors,
      coverRef = titleInfo.coverRef,
    )
  }

  private fun parseTitleInfo(session: XmlSession): MutableTitleInfo {
    val info = MutableTitleInfo()
    val startDepth = session.depth
    while (true) {
      val event = session.next()
      if (event == XmlPullParser.END_TAG && session.depth < startDepth) break
      if (event != XmlPullParser.START_TAG) continue
      when (session.parser.name) {
        "book-title" ->
          if (info.title.isBlank()) info.title = collectElementText(session, 64 * 1024)
        "author" -> info.authors += parseAuthor(session)
        "lang" -> info.language = collectElementText(session, 8 * 1024)
        "coverpage" -> info.coverRef = parseCoverReference(session)
        else -> session.skipSubtree()
      }
    }
    return info
  }

  private fun parseAuthor(session: XmlSession): String {
    var firstName = ""
    var middleName = ""
    var lastName = ""
    var nickname = ""
    val startDepth = session.depth
    while (true) {
      val event = session.next()
      if (event == XmlPullParser.END_TAG && session.depth < startDepth) break
      if (event != XmlPullParser.START_TAG) continue
      when (session.parser.name) {
        "first-name" -> firstName = collectElementText(session, 32 * 1024)
        "middle-name" -> middleName = collectElementText(session, 32 * 1024)
        "last-name" -> lastName = collectElementText(session, 32 * 1024)
        "nickname" -> nickname = collectElementText(session, 32 * 1024)
        else -> session.skipSubtree()
      }
    }
    val fullName =
      listOf(firstName, middleName, lastName)
        .map(::cleanMetadataText)
        .filter { it.isNotEmpty() }
        .joinToString(" ")
    return fullName.ifBlank { cleanMetadataText(nickname) }
  }

  private fun parseCoverReference(session: XmlSession): String? {
    var href: String? = null
    val startDepth = session.depth
    while (true) {
      val event = session.next()
      if (event == XmlPullParser.END_TAG && session.depth < startDepth) break
      if (event != XmlPullParser.START_TAG) continue
      if (session.parser.name == "image" && href == null) {
        href = getAttribute(session.parser, "href")?.trim()?.removePrefix("#")?.takeIf { it.isNotEmpty() }
      }
      session.skipSubtree()
    }
    return href
  }

  private fun parseMainBody(
    session: XmlSession,
    builder: BookDraftBuilder,
    bodyIndex: Int,
  ) {
    var bodyTitle = ""
    var sectionNumber = 0
    var synthetic: SectionDraft? = null
    val syntheticKey = "b$bodyIndex.direct"

    fun ensureSynthetic(): String {
      if (synthetic == null) {
        synthetic =
          builder.addSection(
            chapterKey = syntheticKey,
            parentKey = null,
            level = 0,
            siblingNumber = 1,
            isSynthetic = true,
          )
        synthetic.title = bodyTitle
      }
      return syntheticKey
    }

    val startDepth = session.depth
    while (true) {
      val event = session.next()
      if (event == XmlPullParser.END_TAG && session.depth < startDepth) break
      if (event != XmlPullParser.START_TAG) continue
      when (session.parser.name) {
        "title" -> {
          bodyTitle = parseTitleText(session)
          synthetic?.title = bodyTitle
        }
        "section" ->
          parseSection(
            session = session,
            builder = builder,
            bodyIndex = bodyIndex,
            parentKey = null,
            level = 0,
            siblingNumber = ++sectionNumber,
          )
        else -> {
          val key = ensureSynthetic()
          parseFlowElement(session, builder, key)
        }
      }
    }
  }

  private fun parseSection(
    session: XmlSession,
    builder: BookDraftBuilder,
    bodyIndex: Int,
    parentKey: String?,
    level: Int,
    siblingNumber: Int,
  ) {
    val chapterKey =
      if (parentKey == null) "b$bodyIndex.s$siblingNumber" else "$parentKey.s$siblingNumber"
    val section =
      builder.addSection(
        chapterKey = chapterKey,
        parentKey = parentKey,
        level = level,
        siblingNumber = siblingNumber,
        isSynthetic = false,
      )
    var childNumber = 0
    val startDepth = session.depth
    while (true) {
      val event = session.next()
      if (event == XmlPullParser.END_TAG && session.depth < startDepth) break
      if (event != XmlPullParser.START_TAG) continue
      when (session.parser.name) {
        "title" -> {
          section.title = parseTitleText(session)
          section.titleWasExplicit = section.title.isNotBlank()
          if (section.titleWasExplicit) {
            builder.addVisual(VisualKind.HEADING, section.title, chapterKey)
          }
        }
        "section" ->
          parseSection(
            session = session,
            builder = builder,
            bodyIndex = bodyIndex,
            parentKey = chapterKey,
            level = level + 1,
            siblingNumber = ++childNumber,
          )
        else -> parseFlowElement(session, builder, chapterKey)
      }
    }
  }

  private fun parseFlowElement(
    session: XmlSession,
    builder: BookDraftBuilder,
    chapterKey: String,
  ) {
    when (session.parser.name) {
      "p" -> parseParagraph(session, builder, chapterKey, BlockKind.PARAGRAPH)
      "subtitle" -> parseParagraph(session, builder, chapterKey, BlockKind.SUBTITLE)
      "v" -> parseParagraph(session, builder, chapterKey, BlockKind.VERSE_LINE)
      "empty-line" -> session.skipSubtree()
      "image" -> {
        builder.addVisual(VisualKind.IMAGE, UserTextFallbacks.IMAGE, chapterKey)
        session.skipSubtree()
      }
      "poem" -> parsePoem(session, builder, chapterKey)
      "epigraph",
      "cite" -> parseFlowContainer(session, builder, chapterKey)
      "table" -> parseTable(session, builder, chapterKey)
      "title" -> session.skipSubtree()
      else -> parseParagraph(session, builder, chapterKey, BlockKind.PARAGRAPH)
    }
  }

  private fun parseFlowContainer(
    session: XmlSession,
    builder: BookDraftBuilder,
    chapterKey: String,
  ) {
    val startDepth = session.depth
    while (true) {
      val event = session.next()
      if (event == XmlPullParser.END_TAG && session.depth < startDepth) break
      if (event != XmlPullParser.START_TAG) continue
      when (session.parser.name) {
        "title" -> session.skipSubtree()
        else -> parseFlowElement(session, builder, chapterKey)
      }
    }
  }

  private fun parsePoem(
    session: XmlSession,
    builder: BookDraftBuilder,
    chapterKey: String,
  ) {
    val startDepth = session.depth
    while (true) {
      val event = session.next()
      if (event == XmlPullParser.END_TAG && session.depth < startDepth) break
      if (event != XmlPullParser.START_TAG) continue
      when (session.parser.name) {
        "title" -> session.skipSubtree()
        "stanza" -> parseStanza(session, builder, chapterKey)
        else -> parseFlowElement(session, builder, chapterKey)
      }
    }
  }

  private fun parseStanza(
    session: XmlSession,
    builder: BookDraftBuilder,
    chapterKey: String,
  ) {
    val startDepth = session.depth
    while (true) {
      val event = session.next()
      if (event == XmlPullParser.END_TAG && session.depth < startDepth) break
      if (event != XmlPullParser.START_TAG) continue
      when (session.parser.name) {
        "v" -> parseParagraph(session, builder, chapterKey, BlockKind.VERSE_LINE)
        "title" -> session.skipSubtree()
        else -> parseFlowElement(session, builder, chapterKey)
      }
    }
    builder.markLastVerseStanza()
  }

  private fun parseTable(
    session: XmlSession,
    builder: BookDraftBuilder,
    chapterKey: String,
  ) {
    val startDepth = session.depth
    while (true) {
      val event = session.next()
      if (event == XmlPullParser.END_TAG && session.depth < startDepth) break
      if (event != XmlPullParser.START_TAG) continue
      when (session.parser.name) {
        "tr" -> parseTableRow(session, builder, chapterKey)
        else -> session.skipSubtree()
      }
    }
  }

  private fun parseTableRow(
    session: XmlSession,
    builder: BookDraftBuilder,
    chapterKey: String,
  ) {
    val row = InlineDraftBuilder()
    var hasCell = false
    val startDepth = session.depth
    while (true) {
      val event = session.next()
      if (event == XmlPullParser.END_TAG && session.depth < startDepth) break
      if (event != XmlPullParser.START_TAG) continue
      when (session.parser.name) {
        "td",
        "th" -> {
          val cell = InlineDraftBuilder()
          parseInlineElement(session, cell, builder, chapterKey, captureNoteLinks = true)
          val cellDraft = cell.build()
          if (!cellDraft.isBlank) {
            if (hasCell) row.appendText(" ")
            row.appendDraft(cellDraft)
            hasCell = true
          }
        }
        else -> session.skipSubtree()
      }
    }
    val draft = row.build()
    if (!draft.isBlank) builder.addBlock(BlockKind.TABLE_ROW, chapterKey, draft)
  }

  private fun parseParagraph(
    session: XmlSession,
    builder: BookDraftBuilder,
    chapterKey: String,
    kind: BlockKind,
  ) {
    val inline = InlineDraftBuilder()
    val startDepth = session.depth
    while (true) {
      val event = session.next()
      if (event == XmlPullParser.END_TAG && session.depth < startDepth) break
      when (event) {
        XmlPullParser.TEXT,
        XmlPullParser.CDSECT -> inline.appendText(session.parser.text.orEmpty())
        XmlPullParser.START_TAG ->
          parseInlineElement(session, inline, builder, chapterKey, captureNoteLinks = true)
      }
    }
    val draft = inline.build()
    if (!draft.isBlank) builder.addBlock(kind, chapterKey, draft)
  }

  private fun parseInlineElement(
    session: XmlSession,
    inline: InlineDraftBuilder,
    builder: BookDraftBuilder,
    chapterKey: String,
    captureNoteLinks: Boolean,
  ) {
    val tag = session.parser.name
    if (tag == "image") {
      builder.addVisual(VisualKind.IMAGE, UserTextFallbacks.IMAGE, chapterKey)
      session.skipSubtree()
      return
    }
    if (tag == "a" && captureNoteLinks && isNoteLink(session.parser)) {
      val noteId = getAttribute(session.parser, "href").orEmpty().trim().removePrefix("#")
      if (noteId.isNotEmpty()) {
        inline.appendNote(noteId, preferPrevious = inline.endsWithNonWhitespace())
      }
      session.skipSubtree()
      return
    }
    val startDepth = session.depth
    while (true) {
      val event = session.next()
      if (event == XmlPullParser.END_TAG && session.depth < startDepth) break
      when (event) {
        XmlPullParser.TEXT,
        XmlPullParser.CDSECT -> inline.appendText(session.parser.text.orEmpty())
        XmlPullParser.START_TAG ->
          parseInlineElement(
            session = session,
            inline = inline,
            builder = builder,
            chapterKey = chapterKey,
            captureNoteLinks = captureNoteLinks,
          )
      }
    }
  }

  private fun parseTitleText(session: XmlSession): String {
    val inline = InlineDraftBuilder()
    val dummy = BookDraftBuilder(limits)
    val startDepth = session.depth
    while (true) {
      val event = session.next()
      if (event == XmlPullParser.END_TAG && session.depth < startDepth) break
      when (event) {
        XmlPullParser.TEXT,
        XmlPullParser.CDSECT -> inline.appendText(session.parser.text.orEmpty())
        XmlPullParser.START_TAG ->
          parseInlineElement(
            session = session,
            inline = inline,
            builder = dummy,
            chapterKey = "title",
            captureNoteLinks = false,
          )
      }
    }
    return flattenInlineDraft(inline.build())
  }

  private fun parseNotesBody(
    session: XmlSession,
    builder: BookDraftBuilder,
  ) {
    val startDepth = session.depth
    while (true) {
      val event = session.next()
      if (event == XmlPullParser.END_TAG && session.depth < startDepth) break
      if (event != XmlPullParser.START_TAG) continue
      when (session.parser.name) {
        "section" -> parseNoteSection(session, builder)
        else -> session.skipSubtree()
      }
    }
  }

  private fun parseNoteSection(
    session: XmlSession,
    builder: BookDraftBuilder,
  ) {
    val noteId =
      getAttribute(session.parser, "id")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    val blocks = mutableListOf<String>()
    val startDepth = session.depth
    while (true) {
      val event = session.next()
      if (event == XmlPullParser.END_TAG && session.depth < startDepth) break
      if (event != XmlPullParser.START_TAG) continue
      when (session.parser.name) {
        "section" -> parseNoteSection(session, builder)
        "title" -> {
          val text = collectElementText(session, limits.maxNoteCodeUnits + 1)
          if (text.isNotBlank()) blocks += text
        }
        "p",
        "subtitle",
        "v" -> {
          val text = collectElementText(session, limits.maxNoteCodeUnits + 1)
          if (text.isNotBlank()) blocks += text
        }
        "table" -> {
          val text = collectElementText(session, limits.maxNoteCodeUnits + 1)
          if (text.isNotBlank()) blocks += text
        }
        else -> session.skipSubtree()
      }
    }
    if (noteId != null && blocks.isNotEmpty()) {
      builder.addNote(ParsedNote(noteId, blocks))
    }
  }

  private fun parseBinary(
    session: XmlSession,
    builder: BookDraftBuilder,
    coverRef: String?,
  ) {
    val id = getAttribute(session.parser, "id")?.trim()
    val contentType = getAttribute(session.parser, "content-type").orEmpty().lowercase(Locale.ROOT)
    val isCover = coverRef != null && id == coverRef
    if (!isCover) {
      session.skipSubtree()
      return
    }
    if (contentType.contains("svg") || contentType.startsWith("text/")) {
      builder.addWarning(Fb2Warning(Fb2WarningCode.COVER_REJECTED))
      session.skipSubtree()
      return
    }
    val encoded = StringBuilder()
    var rejected = false
    var tooLarge = false
    val startDepth = session.depth
    while (true) {
      val event = session.next()
      if (event == XmlPullParser.END_TAG && session.depth < startDepth) break
      if (event != XmlPullParser.TEXT && event != XmlPullParser.CDSECT) continue
      val text = session.parser.text.orEmpty()
      for (character in text) {
        when {
          character.isWhitespace() -> Unit
          character in BASE64_ALPHABET -> {
            if (!tooLarge) {
              encoded.append(character)
              if (encoded.length > MAX_BASE64_CHARS_FOR_COVER) tooLarge = true
            }
          }
          else -> rejected = true
        }
      }
    }
    if (rejected || tooLarge) {
      builder.addWarning(Fb2Warning(Fb2WarningCode.COVER_REJECTED))
      return
    }
    val bytes =
      try {
        Base64.getDecoder().decode(encoded.toString())
      } catch (_: IllegalArgumentException) {
        null
      }
    if (bytes == null || bytes.isEmpty() || bytes.size > limits.maxCoverBytes) {
      builder.addWarning(Fb2Warning(Fb2WarningCode.COVER_REJECTED))
      return
    }
    val signature = String(bytes.take(256).toByteArray(), Charsets.ISO_8859_1).trimStart()
    if (signature.startsWith("<svg", ignoreCase = true) || signature.startsWith("<?xml", ignoreCase = true)) {
      builder.addWarning(Fb2Warning(Fb2WarningCode.COVER_REJECTED))
      return
    }
    builder.setCover(bytes)
  }

  private fun collectElementText(session: XmlSession, maxChars: Int): String {
    val output = StringBuilder()
    val startDepth = session.depth
    while (true) {
      val event = session.next()
      if (event == XmlPullParser.END_TAG && session.depth < startDepth) break
      if ((event == XmlPullParser.TEXT || event == XmlPullParser.CDSECT) &&
        output.length < maxChars
      ) {
        val remaining = maxChars - output.length
        output.append(session.parser.text.orEmpty().take(remaining))
      }
    }
    return cleanMetadataText(output.toString())
  }

  private fun buildMetadata(parts: ParsedBookParts, sourceName: String): BookMetadata {
    val fileNameFallback =
      UnicodeText.stripBookExtensions(sourceName).ifBlank { UserTextFallbacks.UNTITLED }
    val rawTitle = parts.metadata.title.ifBlank { fileNameFallback }
    val rawAuthors = parts.authors.filter { it.isNotBlank() }
    val rawAuthor = rawAuthors.joinToString(", ").ifBlank { UserTextFallbacks.UNKNOWN_AUTHOR }
    val title = UnicodeText.truncateGraphemes(rawTitle, limits.maxSectionTitleGraphemes)
    val author = UnicodeText.truncateGraphemes(rawAuthor, limits.maxAuthorGraphemes)
    val language =
      parts.metadata.language
        .trim()
        .take(limits.maxLanguageChars)
        .ifBlank { null }
    if (title != rawTitle || author != rawAuthor || language != parts.metadata.language.trim().ifBlank { null }) {
      parts.builder.addWarning(Fb2Warning(Fb2WarningCode.METADATA_TRUNCATED))
    }
    return BookMetadata(title = title, authorDisplay = author, language = language)
  }

  private fun scanZip(
    file: File,
    cancellationCheck: () -> Boolean,
  ): List<ZipBookCandidate> {
    val rawCandidates = mutableListOf<RawZipCandidate>()
    var entryCount = 0
    var total = 0L
    try {
      ZipInputStream(BufferedInputStream(FileInputStream(file))).use { zip ->
        while (true) {
          checkCancelled(cancellationCheck)
          val entry =
            try {
              zip.nextEntry
            } catch (exception: ZipException) {
              throw zipException(exception)
            } ?: break
          entryCount++
          if (entryCount > limits.maxZipEntries) {
            throw Fb2ImportException(ImportErrorCode.ZIP_LIMIT_EXCEEDED)
          }
          validateEntryName(entry.name)
          val bound = EntryBoundedInputStream(zip, limits.maxZipEntryBytes)
          val bytes =
            drainEntry(bound, limits.maxUncompressedZipBytes - total, cancellationCheck)
          total += bytes
          if (entry.name.endsWith(".fb2", ignoreCase = true)) {
            rawCandidates += RawZipCandidate(entryCount - 1, entry.name, bytes)
          }
          try {
            zip.closeEntry()
          } catch (exception: ZipException) {
            throw zipException(exception)
          }
        }
      }
    } catch (exception: Fb2ImportException) {
      throw exception
    } catch (exception: IOException) {
      throw Fb2ImportException(ImportErrorCode.BAD_ZIP, exception)
    }
    val occurrences = mutableMapOf<String, Int>()
    return rawCandidates.map { candidate ->
      val occurrence = (occurrences[candidate.path] ?: 0) + 1
      occurrences[candidate.path] = occurrence
      ZipBookCandidate(
        entryIndex = candidate.entryIndex,
        path = candidate.path,
        sizeBytes = candidate.sizeBytes,
        displayPath =
          if (occurrence == 1) candidate.path else "${candidate.path} (вариант $occurrence)",
      )
    }
  }

  private fun validateEntryName(name: String) {
    if (name.length > limits.maxZipEntryNameChars) {
      throw Fb2ImportException(ImportErrorCode.ZIP_LIMIT_EXCEEDED)
    }
    if (name.any { it == '\u0000' || it.isISOControl() }) {
      throw Fb2ImportException(ImportErrorCode.UNSAFE_ZIP_PATH)
    }
    val normalized = name.replace('\\', '/')
    if (
      normalized.startsWith("/") ||
        WINDOWS_ABSOLUTE_PATH.matches(normalized) ||
        normalized.split('/').any { it == ".." }
    ) {
      throw Fb2ImportException(ImportErrorCode.UNSAFE_ZIP_PATH)
    }
  }

  private fun validateSourceFile(file: File) {
    if (!file.isFile || !file.canRead()) {
      throw Fb2ImportException(ImportErrorCode.SOURCE_UNAVAILABLE)
    }
    if (file.length() > limits.maxSourceBytes) {
      throw Fb2ImportException(ImportErrorCode.SOURCE_TOO_LARGE)
    }
  }

  private fun decodedInput(input: InputStream): DecodedInput {
    val prefix = ByteArray(ENCODING_PREFIX_BYTES)
    var prefixLength = 0
    while (prefixLength < prefix.size) {
      val read = input.read(prefix, prefixLength, prefix.size - prefixLength)
      if (read < 0) break
      if (read == 0) continue
      prefixLength += read
    }
    val bytes = prefix.copyOf(prefixLength)
    val bom = detectBom(bytes)
    val (charset, bomLength) =
      when (bom) {
        Bom.UTF8 -> SupportedCharset.UTF8 to 3
        Bom.UTF16_LE -> SupportedCharset.UTF16_LE to 2
        Bom.UTF16_BE -> SupportedCharset.UTF16_BE to 2
        null -> null to 0
      }
    val declaration =
      extractDeclaration(bytes, bomLength, charset?.charset ?: Charsets.ISO_8859_1)
    val declared = declaration.charset
    val actual =
      charset
        ?: when (declared) {
          null,
          "utf-8" -> SupportedCharset.UTF8
          "windows-1251",
          "cp1251" -> SupportedCharset.WINDOWS_1251
          "utf-16",
          "utf-16le",
          "utf-16be" -> throw Fb2ImportException(ImportErrorCode.UNSUPPORTED_ENCODING)
          else -> throw Fb2ImportException(ImportErrorCode.UNSUPPORTED_ENCODING)
        }
    val conflict = declared != null && !encodingsCompatible(actual, declared)
    val prefixText =
      try {
        String(bytes, bomLength, bytes.size - bomLength, actual.charset)
      } catch (_: Exception) {
        ""
      }
    if (DOCTYPE_PATTERN.containsMatchIn(prefixText)) {
      throw Fb2ImportException(ImportErrorCode.DOCTYPE_FORBIDDEN)
    }
    val remaining =
      SequenceInputStream(
        ByteArrayInputStream(bytes, bomLength, bytes.size - bomLength),
        input,
      )
    val decoder =
      actual.charset
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return DecodedInput(InputStreamReader(remaining, decoder), conflict)
  }

  private fun extractDeclaration(
    prefix: ByteArray,
    bomLength: Int,
    preliminaryCharset: Charset,
  ): Declaration {
    val text =
      try {
        String(prefix, bomLength, prefix.size - bomLength, preliminaryCharset)
      } catch (_: Exception) {
        return Declaration(null)
      }
    val match = XML_ENCODING_PATTERN.find(text) ?: return Declaration(null)
    return Declaration(match.groupValues[1].trim().lowercase(Locale.ROOT))
  }

  private fun encodingsCompatible(
    actual: SupportedCharset,
    declared: String,
  ): Boolean =
    when (actual) {
      SupportedCharset.UTF8 -> declared == "utf-8" || declared == "utf8"
      SupportedCharset.UTF16_LE,
      SupportedCharset.UTF16_BE ->
        declared == "utf-16" || declared == "utf-16le" || declared == "utf-16be"
      SupportedCharset.WINDOWS_1251 ->
        declared == "windows-1251" || declared == "cp1251"
    }

  private fun enableNamespaces(parser: XmlPullParser) {
    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
    runCatching { parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false) }
    runCatching { parser.setFeature(XmlPullParser.FEATURE_VALIDATION, false) }
  }

  private fun drainReader(reader: Reader) {
    val buffer = CharArray(8 * 1024)
    while (reader.read(buffer) >= 0) {
      // The XML parser owns semantic validation; this drain validates the decoder tail.
    }
  }

  private fun drainEntry(
    input: InputStream,
    remainingTotal: Long,
    cancellationCheck: () -> Boolean,
  ): Long {
    val buffer = ByteArray(8 * 1024)
    var total = 0L
    while (true) {
      checkCancelled(cancellationCheck)
      val read =
        try {
          input.read(buffer)
        } catch (exception: ZipException) {
          throw zipException(exception)
        } catch (exception: IOException) {
          throw Fb2ImportException(ImportErrorCode.BAD_ZIP, exception)
        }
      if (read < 0) break
      total += read
      if (total > limits.maxZipEntryBytes || total > remainingTotal) {
        throw Fb2ImportException(ImportErrorCode.ZIP_LIMIT_EXCEEDED)
      }
    }
    return total
  }

  private fun readPrefix(file: File, maxBytes: Int): ByteArray =
    FileInputStream(file).use { input ->
      val buffer = ByteArray(maxBytes)
      var length = 0
      while (length < buffer.size) {
        val read = input.read(buffer, length, buffer.size - length)
        if (read < 0) break
        if (read == 0) continue
        length += read
      }
      buffer.copyOf(length)
    }

  private fun isZip(prefix: ByteArray): Boolean =
    prefix.size >= 4 &&
      prefix[0] == 'P'.code.toByte() &&
      prefix[1] == 'K'.code.toByte() &&
      ((prefix[2] == 3.toByte() && prefix[3] == 4.toByte()) ||
        (prefix[2] == 5.toByte() && prefix[3] == 6.toByte()) ||
        (prefix[2] == 7.toByte() && prefix[3] == 8.toByte()))

  private fun detectBom(bytes: ByteArray): Bom? =
    when {
      bytes.size >= 3 &&
        bytes[0] == 0xEF.toByte() &&
        bytes[1] == 0xBB.toByte() &&
        bytes[2] == 0xBF.toByte() -> Bom.UTF8
      bytes.size >= 2 &&
        bytes[0] == 0xFF.toByte() &&
        bytes[1] == 0xFE.toByte() -> Bom.UTF16_LE
      bytes.size >= 2 &&
        bytes[0] == 0xFE.toByte() &&
        bytes[1] == 0xFF.toByte() -> Bom.UTF16_BE
      else -> null
    }

  private fun isNoteLink(parser: XmlPullParser): Boolean {
    val type = getAttribute(parser, "type")
    val href = getAttribute(parser, "href")
    return type.equals("note", ignoreCase = true) &&
      href?.trim()?.startsWith("#") == true
  }

  private fun getAttribute(parser: XmlPullParser, localName: String): String? {
    for (index in 0 until parser.attributeCount) {
      val name = parser.getAttributeName(index).substringAfter(':')
      if (name == localName) return parser.getAttributeValue(index)
    }
    return null
  }

  private fun cleanMetadataText(value: String): String =
    UnicodeText.normalizeInlineText(value)
      .replace(Regex("\\s+"), " ")
      .trim()

  private fun zipException(exception: ZipException): Fb2ImportException {
    val message = exception.message.orEmpty().lowercase(Locale.ROOT)
    val code =
      if (message.contains("encrypt") || message.contains("password")) {
        ImportErrorCode.ZIP_ENCRYPTED
      } else {
        ImportErrorCode.BAD_ZIP
      }
    return Fb2ImportException(code, exception)
  }

  private inner class XmlSession(
    val parser: XmlPullParser,
    private val limits: Fb2Limits,
    private val cancellationCheck: () -> Boolean,
  ) {
    var depth: Int = 0
      private set
    var isAtEndDocument: Boolean = false
      private set

    fun advanceToRoot() {
      while (true) {
        val event = next()
        if (event == XmlPullParser.START_TAG) return
        if (event == XmlPullParser.END_DOCUMENT) {
          throw Fb2ImportException(ImportErrorCode.BAD_XML)
        }
      }
    }

    fun next(): Int {
      checkCancelled(cancellationCheck)
      val event =
        try {
          parser.next()
        } catch (exception: XmlPullParserException) {
          throw exception
        } catch (exception: IOException) {
          throw exception
        }
      when (event) {
        XmlPullParser.START_TAG -> {
          depth++
          if (depth > limits.maxXmlDepth) {
            throw Fb2ImportException(ImportErrorCode.XML_DEPTH_EXCEEDED)
          }
          validateName(parser.name)
          if (parser.attributeCount > limits.maxAttributesPerElement) {
            throw Fb2ImportException(ImportErrorCode.XML_VALUE_TOO_LARGE)
          }
          for (index in 0 until parser.attributeCount) {
            validateName(parser.getAttributeName(index))
            validateValue(parser.getAttributeValue(index).orEmpty())
          }
        }
        XmlPullParser.END_TAG -> {
          validateName(parser.name)
          depth = (depth - 1).coerceAtLeast(0)
        }
        XmlPullParser.TEXT,
        XmlPullParser.CDSECT,
        XmlPullParser.COMMENT,
        XmlPullParser.IGNORABLE_WHITESPACE,
        XmlPullParser.PROCESSING_INSTRUCTION -> validateValue(parser.text.orEmpty())
        XmlPullParser.DOCDECL -> throw Fb2ImportException(ImportErrorCode.DOCTYPE_FORBIDDEN)
        XmlPullParser.ENTITY_REF -> throw Fb2ImportException(ImportErrorCode.DOCTYPE_FORBIDDEN)
        XmlPullParser.END_DOCUMENT -> isAtEndDocument = true
      }
      return event
    }

    fun skipSubtree() {
      val targetDepth = depth
      while (targetDepth > 0) {
        val event = next()
        if (event == XmlPullParser.END_TAG && depth < targetDepth) return
        if (event == XmlPullParser.END_DOCUMENT) {
          throw Fb2ImportException(ImportErrorCode.BAD_XML)
        }
      }
    }

    private fun validateName(name: String?) {
      if ((name?.length ?: 0) > limits.maxXmlEventChars) {
        throw Fb2ImportException(ImportErrorCode.XML_VALUE_TOO_LARGE)
      }
    }

    private fun validateValue(value: String) {
      if (value.length > limits.maxXmlEventChars) {
        throw Fb2ImportException(ImportErrorCode.XML_VALUE_TOO_LARGE)
      }
    }
  }

  private fun checkCancelled(cancellationCheck: () -> Boolean) {
    if (cancellationCheck()) throw CancellationException("Import cancelled")
  }

  private inner class BoundedSourceInputStream(
    private val delegate: InputStream,
    private val maxBytes: Long,
  ) : InputStream() {
    private var consumed = 0L

    override fun read(): Int {
      val value = delegate.read()
      if (value >= 0) {
        consumed++
        if (consumed > maxBytes) throw Fb2ImportException(ImportErrorCode.SOURCE_TOO_LARGE)
      }
      return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
      val read = delegate.read(buffer, offset, length)
      if (read > 0) {
        consumed += read
        if (consumed > maxBytes) throw Fb2ImportException(ImportErrorCode.SOURCE_TOO_LARGE)
      }
      return read
    }

    override fun close() {
      delegate.close()
    }
  }

  private class EntryBoundedInputStream(
    private val delegate: InputStream,
    private val maxBytes: Long,
  ) : InputStream() {
    var consumed = 0L
      private set

    override fun read(): Int {
      val value = delegate.read()
      if (value >= 0) {
        consumed++
        if (consumed > maxBytes) throw Fb2ImportException(ImportErrorCode.ZIP_LIMIT_EXCEEDED)
      }
      return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
      val read = delegate.read(buffer, offset, length)
      if (read > 0) {
        consumed += read
        if (consumed > maxBytes) throw Fb2ImportException(ImportErrorCode.ZIP_LIMIT_EXCEEDED)
      }
      return read
    }
  }

  private data class ParsedBookParts(
    val builder: BookDraftBuilder,
    val metadata: MutableMetadata,
    val authors: List<String>,
  )

  private data class MutableMetadata(
    var title: String = "",
    var language: String = "",
  )

  private class MutableTitleInfo {
    var title: String = ""
    var language: String = ""
    val authors = mutableListOf<String>()
    var coverRef: String? = null

    fun merge(other: MutableTitleInfo) {
      if (title.isBlank()) title = other.title
      if (language.isBlank()) language = other.language
      authors += other.authors
      if (coverRef == null) coverRef = other.coverRef
    }
  }

  private data class DescriptionData(
    val title: String,
    val language: String,
    val authors: List<String>,
    val coverRef: String?,
  )

  private data class RawZipCandidate(
    val entryIndex: Int,
    val path: String,
    val sizeBytes: Long,
  )

  private data class Declaration(
    val charset: String?,
  )

  private data class DecodedInput(
    val reader: Reader,
    val conflict: Boolean,
  )

  private enum class Bom {
    UTF8,
    UTF16_LE,
    UTF16_BE,
  }

  private enum class SupportedCharset(val charset: Charset) {
    UTF8(Charsets.UTF_8),
    UTF16_LE(Charsets.UTF_16LE),
    UTF16_BE(Charsets.UTF_16BE),
    WINDOWS_1251(Charset.forName("windows-1251")),
  }

  private companion object {
    const val ENCODING_PREFIX_BYTES = 16 * 1024
    const val ZIP_MAGIC_PROBE_BYTES = 8
    const val MAX_BASE64_CHARS_FOR_COVER = 14_000_000
    val XML_ENCODING_PATTERN = Regex("""<\?xml[^>]*\bencoding\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    val DOCTYPE_PATTERN = Regex("""<!DOCTYPE\b""", RegexOption.IGNORE_CASE)
    val WINDOWS_ABSOLUTE_PATH = Regex("""^[A-Za-z]:/""")
    val BASE64_ALPHABET =
      ('A'..'Z').toSet() + ('a'..'z').toSet() + ('0'..'9').toSet() + setOf('+', '/', '=')
  }
}

class Fb2SelectionRequiredException(
  val candidates: List<ZipBookCandidate>,
) : Exception()
