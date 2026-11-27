// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.grpc

import com.intellij.lexer.LexerBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

/** Lexes the proto2 / proto3 / editions syntax. Stateless: every token can be restarted from state 0. */
class ProtoLexer : LexerBase() {
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
    val next = if (tokenStart + 1 < end) buffer[tokenStart + 1] else ' '
    var i = tokenStart + 1
    type = when {
      c.isWhitespace() -> {
        i = buffer.skipWhile(i, end) { it.isWhitespace() }
        TokenType.WHITE_SPACE
      }
      c == '/' && next == '/' -> {
        i = buffer.skipWhile(i, end) { it != '\n' }
        ProtoTokens.LINE_COMMENT
      }
      c == '/' && next == '*' -> {
        i = buffer.blockCommentEnd(i + 1, end)
        ProtoTokens.BLOCK_COMMENT
      }
      c == '"' || c == '\'' -> {
        i = buffer.stringEnd(i, end, c)
        ProtoTokens.STRING
      }
      c.isLetter() || c == '_' -> {
        i = buffer.skipWhile(i, end) { it.isLetterOrDigit() || it == '_' }
        if (buffer.subSequence(tokenStart, i).toString() in ProtoTokens.KEYWORDS) ProtoTokens.KEYWORD else ProtoTokens.IDENT
      }
      c.isDigit() || c == '.' && next.isDigit() -> {
        i = buffer.numberEnd(tokenStart, end)
        if (INT.matches(buffer.subSequence(tokenStart, i))) ProtoTokens.INT else ProtoTokens.FLOAT
      }
      else -> PUNCTUATION[c] ?: ProtoTokens.OTHER
    }
    tokenEnd = i
  }

  private companion object {
    val INT = Regex("0[xX][0-9a-fA-F]+|[0-9]+")
    val PUNCTUATION = mapOf(
      '{' to ProtoTokens.LBRACE, '}' to ProtoTokens.RBRACE, '(' to ProtoTokens.LPAREN, ')' to ProtoTokens.RPAREN,
      '[' to ProtoTokens.LBRACKET, ']' to ProtoTokens.RBRACKET, '<' to ProtoTokens.LT, '>' to ProtoTokens.GT,
      ';' to ProtoTokens.SEMI, ',' to ProtoTokens.COMMA, '.' to ProtoTokens.DOT, '=' to ProtoTokens.EQ,
    )
  }
}
private inline fun CharSequence.skipWhile(from: Int, end: Int, accept: (Char) -> Boolean): Int {
  var i = from
  while (i < end && accept(this[i])) i++
  return i
}

/** The offset after the closing star-slash, or the end for an unterminated comment; `from` is just after the opening one. */
private fun CharSequence.blockCommentEnd(from: Int, end: Int): Int {
  var i = from
  while (i + 1 < end && !(this[i] == '*' && this[i + 1] == '/')) i++
  return minOf(end, i + 2)
}

/** The offset after the closing quote; an unterminated string ends before the line break. */
private fun CharSequence.stringEnd(from: Int, end: Int, quote: Char): Int {
  var i = from
  while (i < end && this[i] != quote && this[i] != '\n') i += if (this[i] == '\\') 2 else 1
  return if (i < end && this[i] == quote) i + 1 else minOf(i, end)
}

/** Decimal, hex and float literals; a sign belongs to the number only right after the exponent of a non-hex literal. */
private fun CharSequence.numberEnd(start: Int, end: Int): Int {
  val hex = start + 1 < end && this[start] == '0' && this[start + 1] in "xX"
  var i = start + 1
  while (i < end) {
    val d = this[i]
    val exponentSign = !hex && d in "+-" && this[i - 1] in "eE"
    if (d.isLetterOrDigit() || d in "._" || exponentSign) i++ else break
  }
  return i
}
