// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.graphql.lang

import com.intellij.psi.TokenType
import junit.framework.TestCase

class GraphQLLexerTest : TestCase() {
  private fun tokens(text: String): List<String> {
    val lexer = GraphQLLexer()
    lexer.start(text)
    val out = mutableListOf<String>()
    while (lexer.tokenType != null) {
      val type = lexer.tokenType.toString()
      // Punctuation tokens are named after their text, so they are listed once
      if (lexer.tokenType != TokenType.WHITE_SPACE) out += if (type == lexer.tokenText) type else "$type:${lexer.tokenText}"
      lexer.advance()
    }
    return out
  }

  fun testSchemaTokens() {
    assertEquals(
      listOf("KEYWORD:type", "NAME:Book", "{", "NAME:id", ":", "NAME:ID", "!", "KEYWORD:type", ":", "[", "NAME:String", "]", "}", "COMMENT:# done"),
      tokens("type Book { id: ID! type: [String] }\n# done"),
    )
  }

  fun testStringsNumbersAndSpread() {
    assertEquals(
      listOf("STRING:\"\"\"a \\\"\"\" \"b\"\"\"", "NUMBER:-1.5e3", ",", "STRING:\"x\\\"y\"", "...", "NAME:F", "@", "$"),
      tokens("\"\"\"a \\\"\"\" \"b\"\"\" -1.5e3, \"x\\\"y\" ...F @$"),
    )
  }

  fun testUnterminatedStringStopsAtLineEnd() {
    assertEquals(listOf("STRING:\"open", "NAME:next"), tokens("\"open\nnext"))
  }
}
