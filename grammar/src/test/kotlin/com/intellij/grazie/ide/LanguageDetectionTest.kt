// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.
package com.intellij.grazie.ide

import com.intellij.grazie.nlp.Language
import com.intellij.grazie.GrazieTestBase
import com.intellij.grazie.jlanguage.Lang
import com.intellij.grazie.utils.HighlightingUtil
import com.intellij.grazie.utils.getLanguageIfAvailable
import com.intellij.openapi.util.registry.Registry

internal class LanguageDetectionTest: GrazieTestBase() {

  fun `test text in the middle is not French`() {
    enableProofreadingFor(setOf(Lang.FRENCH, Lang.AMERICAN_ENGLISH))
    myFixture.configureByText("a.java", """
      // use multiple instances within container
      
      // use multiple containers
      
      // use concurrent pinging within PF instance, using a worker pool
    """.trimIndent())
    myFixture.checkHighlighting()

    HighlightingUtil.getAllFileTexts(myFixture.file.viewProvider)
      .forEach {
        val contentLanguage = getLanguageIfAvailable(it)
        assertEquals(
          "Language of `$it` expected to be English, not $contentLanguage",
          Language.ENGLISH, contentLanguage
        )
      }
  }

  fun `test each text of a file with many texts gets its own language`() {
    enableProofreadingFor(setOf(Lang.GERMANY_GERMAN, Lang.AMERICAN_ENGLISH))
    assertTrue(Registry.`is`("grazie.batch.language.detector"))
    val english = "// the parser reads the whole file before it reports any of the problems it found"
    val german = "// der Parser liest die ganze Datei, bevor er die gefundenen Probleme meldet und das ist gut"
    // a field after each comment, so that the comments stay separate texts instead of one merged comment
    val body = (0 until 300).joinToString("\n") { "  ${if (it % 2 == 0) english else german} $it\n  int f$it;" }
    myFixture.configureByText("A.java", "class A {\n$body\n}")

    val texts = HighlightingUtil.getAllFileTexts(myFixture.file.viewProvider)
    assertEquals(300, texts.size)
    for ((index, text) in texts.withIndex()) {
      assertEquals("$text", if (index % 2 == 0) Language.ENGLISH else Language.GERMAN, getLanguageIfAvailable(text))
    }
  }
}
