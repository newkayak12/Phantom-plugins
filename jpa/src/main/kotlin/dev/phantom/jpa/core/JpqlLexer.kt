// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.jpa.core

import com.intellij.lexer.LexerBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

/** Hand-written JPQL/HQL lexer. No state between lines, so incremental relexing restarts anywhere. */
class JpqlLexer : LexerBase() {
  private var buffer: CharSequence = ""
  private var end = 0
  private var tokenStart = 0
  private var tokenEnd = 0
  private var tokenType: IElementType? = null

  override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
    this.buffer = buffer
    this.end = endOffset
    tokenEnd = startOffset
    advance()
  }

  override fun getState(): Int = 0
  override fun getTokenType(): IElementType? = tokenType
  override fun getTokenStart(): Int = tokenStart
  override fun getTokenEnd(): Int = tokenEnd
  override fun getBufferSequence(): CharSequence = buffer
  override fun getBufferEnd(): Int = end

  override fun advance() {
    tokenStart = tokenEnd
    if (tokenStart >= end) {
      tokenType = null
      return
    }
    var i = tokenStart
    val c = buffer[i]
    tokenType = when {
      c.isWhitespace() -> {
        while (i < end && buffer[i].isWhitespace()) i++
        TokenType.WHITE_SPACE
      }
      c == '-' && peek(i + 1) == '-' -> {
        while (i < end && buffer[i] != '\n') i++
        JpqlTokens.COMMENT
      }
      c == '/' && peek(i + 1) == '*' -> {
        i += 2
        while (i < end && !(buffer[i] == '*' && peek(i + 1) == '/')) i++
        i = minOf(end, i + 2)
        JpqlTokens.COMMENT
      }
      c == '\'' -> {
        i++
        while (i < end) {
          if (buffer[i] == '\'') {
            if (peek(i + 1) == '\'') i += 2 else { i++; break }
          }
          else i++
        }
        JpqlTokens.STRING
      }
      c == ':' && peek(i + 1)?.let { Character.isJavaIdentifierStart(it) } == true -> {
        i++
        while (i < end && Character.isJavaIdentifierPart(buffer[i])) i++
        JpqlTokens.PARAMETER
      }
      c == '?' -> {
        i++
        while (i < end && buffer[i].isDigit()) i++
        JpqlTokens.PARAMETER
      }
      c.isDigit() -> {
        while (i < end && (buffer[i].isLetterOrDigit() || buffer[i] == '.' && peek(i + 1)?.isDigit() == true)) i++
        JpqlTokens.NUMBER
      }
      Character.isJavaIdentifierStart(c) -> {
        while (i < end && Character.isJavaIdentifierPart(buffer[i])) i++
        val word = buffer.subSequence(tokenStart, i).toString()
        when {
          word == JpqlTokens.PLACEHOLDER_TEXT -> JpqlTokens.PLACEHOLDER
          word.uppercase() in JpqlTokens.KEYWORDS -> JpqlTokens.KEYWORD
          else -> JpqlTokens.IDENTIFIER
        }
      }
      else -> {
        i++
        when (c) {
          '.' -> JpqlTokens.DOT
          ',' -> JpqlTokens.COMMA
          '(' -> JpqlTokens.LPAREN
          ')' -> JpqlTokens.RPAREN
          '{' -> JpqlTokens.LBRACE
          '}' -> JpqlTokens.RBRACE
          '=', '<', '>', '!', '+', '-', '*', '/', '|', '%' -> {
            while (i < end && buffer[i] in "=<>|") i++
            JpqlTokens.OPERATOR
          }
          else -> TokenType.BAD_CHARACTER
        }
      }
    }
    tokenEnd = i
  }

  private fun peek(index: Int): Char? = if (index < end) buffer[index] else null
}
