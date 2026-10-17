// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core

import com.intellij.lexer.LexerBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

/**
 * Single-pass SQL lexer for MySQL, PostgreSQL and H2 together: backtick, double-quote and bracket identifiers,
 * `--`/`#`/`/* */` comments, `'…'` strings with `''` and backslash escapes, PostgreSQL dollar-quoted bodies.
 *
 * Every token is a whole construct (an unclosed comment or string runs to the end), so the state is always 0 and
 * incremental relexing can restart at any token.
 */
class SqlLexer : LexerBase() {
  private var buffer: CharSequence = ""
  private var end = 0
  private var tokenStart = 0
  private var tokenEnd = 0
  private var type: IElementType? = null

  override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
    this.buffer = buffer
    this.end = endOffset
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
    val next = at(tokenStart + 1)
    when {
      c.isWhitespace() -> token(TokenType.WHITE_SPACE, skipWhile(tokenStart) { it.isWhitespace() })
      c == '-' && next == '-' || c == '#' -> token(SqlTokens.LINE_COMMENT, lineEnd(tokenStart))
      c == '/' && next == '*' -> token(SqlTokens.BLOCK_COMMENT, blockCommentEnd(tokenStart + 2))
      c == '\'' -> token(SqlTokens.STRING, quotedEnd(tokenStart + 1, '\'', backslash = true))
      (c == 'E' || c == 'e' || c == 'N' || c == 'n' || c == 'X' || c == 'x' || c == 'B' || c == 'b') && next == '\'' ->
        token(SqlTokens.STRING, quotedEnd(tokenStart + 2, '\'', backslash = true))
      c == '`' -> token(SqlTokens.QUOTED_IDENTIFIER, quotedEnd(tokenStart + 1, '`', backslash = false))
      c == '"' -> token(SqlTokens.QUOTED_IDENTIFIER, quotedEnd(tokenStart + 1, '"', backslash = false))
      c == '[' && isNameStart(next) && bracketIdentifierEnd(tokenStart) > 0 -> token(SqlTokens.QUOTED_IDENTIFIER, bracketIdentifierEnd(tokenStart))
      c == '$' -> dollar()
      c.isDigit() || c == '.' && next.isDigit() -> token(SqlTokens.NUMBER, numberEnd(tokenStart))
      isNameStart(c) -> {
        val wordEnd = skipWhile(tokenStart) { isNamePart(it) }
        token(if (SqlTokens.isKeyword(buffer.subSequence(tokenStart, wordEnd))) SqlTokens.KEYWORD else SqlTokens.IDENTIFIER, wordEnd)
      }
      c == '?' -> token(SqlTokens.PARAMETER, tokenStart + 1)
      c == ':' && isNameStart(next) && at(tokenStart - 1) != ':' -> token(SqlTokens.PARAMETER, skipWhile(tokenStart + 1) { isNamePart(it) })
      c == '@' -> token(SqlTokens.PARAMETER, skipWhile(skipWhile(tokenStart) { it == '@' }) { isNamePart(it) || it == '.' })
      c == ';' -> token(SqlTokens.SEMICOLON, tokenStart + 1)
      c == ',' -> token(SqlTokens.COMMA, tokenStart + 1)
      c == '.' -> token(SqlTokens.DOT, tokenStart + 1)
      c == '(' -> token(SqlTokens.LPAREN, tokenStart + 1)
      c == ')' -> token(SqlTokens.RPAREN, tokenStart + 1)
      c in OPERATOR_CHARS -> token(SqlTokens.OPERATOR, operatorEnd(tokenStart))
      else -> token(TokenType.BAD_CHARACTER, tokenStart + 1)
    }
  }

  private fun token(t: IElementType, to: Int) {
    type = t
    tokenEnd = to.coerceIn(tokenStart + 1, end)
  }

  private fun at(i: Int): Char = if (i in 0 until end) buffer[i] else '\u0000'

  private inline fun skipWhile(from: Int, p: (Char) -> Boolean): Int {
    var i = from
    while (i < end && p(buffer[i])) i++
    return i
  }

  private fun lineEnd(from: Int): Int = skipWhile(from) { it != '\n' }

  /** PostgreSQL nests block comments; MySQL and H2 do not, but nested comments there are vanishingly rare. */
  private fun blockCommentEnd(from: Int): Int {
    var depth = 1
    var i = from
    while (i < end) {
      if (buffer[i] == '*' && at(i + 1) == '/') {
        depth--
        i += 2
        if (depth == 0) return i
        continue
      }
      if (buffer[i] == '/' && at(i + 1) == '*') {
        depth++
        i += 2
        continue
      }
      i++
    }
    return end
  }

  private fun quotedEnd(from: Int, quote: Char, backslash: Boolean): Int {
    var i = from
    while (i < end) {
      val ch = buffer[i]
      if (backslash && ch == '\\') {
        i += 2
        continue
      }
      if (ch == quote) {
        if (at(i + 1) == quote) {
          i += 2
          continue
        }
        return i + 1
      }
      i++
    }
    return end
  }

  private fun bracketIdentifierEnd(from: Int): Int {
    var i = from + 1
    while (i < end && buffer[i] != ']' && buffer[i] != '\n') i++
    return if (i < end && buffer[i] == ']') i + 1 else -1
  }

  /** `$$…$$` or `$tag$…$tag$`; `$1` is a positional parameter. */
  private fun dollar() {
    val next = at(tokenStart + 1)
    if (next.isDigit()) {
      token(SqlTokens.PARAMETER, skipWhile(tokenStart + 1) { it.isDigit() })
      return
    }
    val tagEnd = if (next == '$') tokenStart + 1 else skipWhile(tokenStart + 1) { isNamePart(it) && it != '$' }
    if (at(tagEnd) != '$' || next != '$' && !isNameStart(next)) {
      token(SqlTokens.OPERATOR, tokenStart + 1)
      return
    }
    val tag = buffer.subSequence(tokenStart, tagEnd + 1).toString()
    var i = tagEnd + 1
    while (i + tag.length <= end) {
      if (buffer[i] == '$' && regionMatches(i, tag)) {
        token(SqlTokens.STRING, i + tag.length)
        return
      }
      i++
    }
    token(SqlTokens.STRING, end)
  }

  private fun regionMatches(at: Int, s: String): Boolean {
    for (k in s.indices) if (buffer[at + k] != s[k]) return false
    return true
  }

  /** A run like `<=`, `::` or `->>`, stopping before a comment start. */
  private fun operatorEnd(from: Int): Int {
    var i = from + 1
    while (i < end && buffer[i] in OPERATOR_CHARS && !(buffer[i] == '-' && at(i + 1) == '-') && !(buffer[i] == '/' && at(i + 1) == '*')) i++
    return i
  }

  private fun numberEnd(from: Int): Int {
    if (buffer[from] == '0' && (at(from + 1) == 'x' || at(from + 1) == 'X') && at(from + 2).isLetterOrDigit()) {
      return skipWhile(from + 2) { it.isLetterOrDigit() }
    }
    var i = skipWhile(from) { it.isDigit() }
    if (at(i) == '.' && at(i + 1) != '.') i = skipWhile(i + 1) { it.isDigit() }
    if ((at(i) == 'e' || at(i) == 'E') && (at(i + 1).isDigit() || (at(i + 1) == '+' || at(i + 1) == '-') && at(i + 2).isDigit())) {
      i = skipWhile(i + 2) { it.isDigit() }
    }
    return i
  }

  companion object {
    private const val OPERATOR_CHARS = "+-*/%<>=!~^&|:"

    fun isNameStart(c: Char): Boolean = c.isLetter() || c == '_'
    fun isNamePart(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '$'
  }
}
