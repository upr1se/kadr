package com.upr1se.kadr.testing

import com.upr1se.kadr.fb2.Fb2Parser
import java.io.File
import org.kxml2.io.KXmlParser

object SyntheticFb2 {
  fun parser(limits: com.upr1se.kadr.fb2.Fb2Limits = com.upr1se.kadr.fb2.Fb2Limits()) =
    Fb2Parser(limits, parserFactory = { KXmlParser() })

  fun xml(
    title: String = "Синтетическая книга",
    author: String = "<first-name>Анна</first-name><last-name>Тестова</last-name>",
    body: String,
    notes: String = "",
    coverBase64: String? = null,
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
          ${if (coverBase64 != null) "<coverpage><image l:href=\"#cover\"/></coverpage>" else ""}
        </title-info>
      </description>
      <body>$body</body>
      $notes
      ${if (coverBase64 != null) "<binary id=\"cover\" content-type=\"image/png\">$coverBase64</binary>" else ""}
    </FictionBook>
    """.trimIndent()

  fun write(file: File, contents: String) {
    file.writeText(contents, Charsets.UTF_8)
  }
}
