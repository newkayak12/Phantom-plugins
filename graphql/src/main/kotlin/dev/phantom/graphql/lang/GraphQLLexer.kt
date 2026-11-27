// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.graphql.lang

import com.intellij.lexer.LexerBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import dev.phantom.graphql.lang.GraphQLTokenTypes as T

/** Lexes GraphQL (October 2021 spec) without state: every token, block strings included, is complete on its own. */
class GraphQLLexer : LexerBase() {
  private var buffer: CharSequence = ""
  private var end = 0
  private var tokenStart = 0
  private var tokenEnd = 0
  private var type: IElementType? = null

  override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
    this.buffer = buffer
    end = endOffset
    tokenEnd = startOffset
    advance()
  }

  override fun getState(): Int = 0

  override fun getTokenType(): IElementType? = type

  override fun getTokenStart(): Int = tokenStart

  override fun getTokenEnd(): Int = tokenEnd

  override fun getBufferSequence(): CharSequence = buffer

  override fun getBufferEnd(): Int = end

  override fun advance() {
    tokenStart = tokenEnd
    if (tokenStart >= end) {
      type = null
      return
    }
    val c = buffer[tokenStart]
    var i = tokenStart + 1
    type = when {
      c.isWhitespace() || c == '\uFEFF' -> {
        while (i < end && (buffer[i].isWhitespace() || buffer[i] == '\uFEFF')) i++
        TokenType.WHITE_SPACE
      }
      c == '#' -> {
        while (i < end && buffer[i] != '\n' && buffer[i] != '\r') i++
        T.COMMENT
      }
      c == '"' -> {
        i = stringEnd()
        T.STRING
      }
      isNameStart(c) -> {
        while (i < end && isNamePart(buffer[i])) i++
        if (buffer.subSequence(tokenStart, i).toString() in T.KEYWORDS) T.KEYWORD else T.NAME
      }
      c.isDigit() || c == '-' && i < end && buffer[i].isDigit() -> {
        i = numberEnd()
        T.NUMBER
      }
      c == '.' && startsWith(tokenStart, "...") -> {
        i = tokenStart + 3
        T.SPREAD
      }
      else -> T.PUNCTUATION[c] ?: TokenType.BAD_CHARACTER
    }
    tokenEnd = i
  }

  private fun startsWith(offset: Int, s: String) = offset + s.length <= end && buffer.subSequence(offset, offset + s.length).toString() == s

  /** A block string ends at the next unescaped triple quote, a plain one at its closing quote or the end of the line. */
  private fun stringEnd(): Int {
    if (startsWith(tokenStart, "\"\"\"")) {
      var i = tokenStart + 3
      while (i < end) {
        if (buffer[i] == '\\' && startsWith(i + 1, "\"\"\"")) {
          i += 4
        } else if (startsWith(i, "\"\"\"")) {
          return i + 3
        } else {
          i++
        }
      }
      return end
    }
    var i = tokenStart + 1
    while (i < end && buffer[i] != '\n' && buffer[i] != '\r') {
      when (buffer[i]) {
        '\\' -> i += 2
        '"' -> return i + 1
        else -> i++
      }
    }
    return minOf(i, end)
  }

  private fun numberEnd(): Int {
    var i = tokenStart + 1
    while (i < end && buffer[i].isDigit()) i++
    if (i + 1 < end && buffer[i] == '.' && buffer[i + 1].isDigit()) {
      i++
      while (i < end && buffer[i].isDigit()) i++
    }
    if (i < end && (buffer[i] == 'e' || buffer[i] == 'E')) {
      var j = i + 1
      if (j < end && (buffer[j] == '+' || buffer[j] == '-')) j++
      if (j < end && buffer[j].isDigit()) {
        i = j
        while (i < end && buffer[i].isDigit()) i++
      }
    }
    return i
  }

  private fun isNameStart(c: Char) = c == '_' || c in 'a'..'z' || c in 'A'..'Z'

  private fun isNamePart(c: Char) = isNameStart(c) || c in '0'..'9'
}
