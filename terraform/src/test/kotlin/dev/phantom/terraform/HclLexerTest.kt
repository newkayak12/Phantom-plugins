// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.terraform

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class HclLexerTest : BasePlatformTestCase() {
  fun testHighlightingLexerRestartsOnlyOutsideStrings() {
    val lexer = HclLexer()
    val text = "a = \"x${'$'}{b}y\"\n"
    lexer.start(text)
    val states = ArrayList<Pair<String, Int>>()
    while (lexer.tokenType != null) {
      states += text.substring(lexer.tokenStart, lexer.tokenEnd) to lexer.state
      lexer.advance()
    }
    assertEquals(
      listOf("a" to 0, " " to 0, "=" to 0, " " to 0, "\"" to 0, "x" to 1, "${'$'}{" to 1, "b" to 1, "}" to 1, "y" to 1, "\"" to 1, "\n" to 0),
      states,
    )
  }
}
