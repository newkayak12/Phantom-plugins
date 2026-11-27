// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.terraform

import com.intellij.lexer.LexerBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import dev.phantom.terraform.HclTokens as T

/**
 * Lexes HCL native syntax as Terraform uses it. A quoted string is split into its text and its `${...}` / `%{...}`
 * parts so the expressions inside are parsed too; a heredoc stays one token. The state is 0 outside any string and 1
 * inside one, so the editor restarts lexing only between top-level tokens.
 */
class HclLexer : LexerBase() {
  private var buffer: CharSequence = ""
  private var end = 0
  private var tokenStart = 0
  private var tokenEnd = 0
  private var type: IElementType? = null
  private var state = 0

  /** One entry per open string: [IN_STRING] for its text, otherwise the depth of `{` inside its `${` or `%{`. */
  private val stack = ArrayList<Int>()

  override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
    this.buffer = buffer
    end = endOffset
    tokenEnd = startOffset
    stack.clear()
    advance()
  }

  override fun getState(): Int = state
  override fun getTokenType(): IElementType? = type
  override fun getTokenStart(): Int = tokenStart
  override fun getTokenEnd(): Int = tokenEnd
  override fun getBufferSequence(): CharSequence = buffer
  override fun getBufferEnd(): Int = end

  override fun advance() {
    tokenStart = tokenEnd
    state = if (stack.isEmpty()) 0 else 1
    if (tokenStart >= end) {
      type = null
      return
    }
    if (stack.lastOrNull() == IN_STRING) {
      stringPart()
    } else {
      code()
    }
  }

  private fun at(i: Int): Char = if (i < end) buffer[i] else '\u0000'

  private fun stringPart() {
    val c = buffer[tokenStart]
    when {
      c == '"' -> {
        stack.removeAt(stack.lastIndex)
        finish(tokenStart + 1, T.QUOTE_CLOSE)
      }
      // An unterminated string ends at the line break; the rest is code again.
      c == '\n' -> {
        stack.removeAt(stack.lastIndex)
        code()
      }
      c == '$' && at(tokenStart + 1) == '{' -> {
        stack.add(0)
        finish(tokenStart + 2, T.INTERPOLATION_START)
      }
      c == '%' && at(tokenStart + 1) == '{' -> {
        stack.add(0)
        finish(tokenStart + 2, T.DIRECTIVE_START)
      }
      else -> finish(textEnd(tokenStart), T.STRING_TEXT)
    }
  }

  /** The end of literal string text: a quote, a line break, or a `${` / `%{` that is not escaped as `$${` / `%%{`. */
  private fun textEnd(from: Int): Int {
    var i = from
    while (i < end) {
      val c = buffer[i]
      when {
        c == '"' || c == '\n' -> return i
        c == '\\' -> i += 2
        (c == '$' || c == '%') && at(i + 1) == c && at(i + 2) == '{' -> i += 3
        (c == '$' || c == '%') && at(i + 1) == '{' -> return i
        else -> i++
      }
    }
    return end
  }

  private fun code() {
    val c = buffer[tokenStart]
    val next = at(tokenStart + 1)
    when {
      c.isWhitespace() -> finish(skipWhile(tokenStart + 1) { it.isWhitespace() }, TokenType.WHITE_SPACE)
      c == '#' || c == '/' && next == '/' -> finish(skipWhile(tokenStart + 1) { it != '\n' }, T.LINE_COMMENT)
      c == '/' && next == '*' -> finish(blockCommentEnd(tokenStart + 2), T.BLOCK_COMMENT)
      c == '"' -> {
        stack.add(IN_STRING)
        finish(tokenStart + 1, T.QUOTE_OPEN)
      }
      c == '<' && next == '<' && heredocStart() -> finish(heredocEnd(), T.HEREDOC)
      c.isLetter() || c == '_' -> {
        val i = skipWhile(tokenStart + 1) { it.isLetterOrDigit() || it == '_' || it == '-' }
        finish(i, KEYWORDS[buffer.subSequence(tokenStart, i).toString()] ?: T.IDENT)
      }
      c.isDigit() -> finish(numberEnd(), T.NUMBER)
      c == '{' -> {
        if (stack.isNotEmpty()) stack[stack.lastIndex]++
        finish(tokenStart + 1, T.LBRACE)
      }
      c == '}' -> closeBrace()
      else -> punctuation(c, next)
    }
  }

  private fun closeBrace() {
    val depth = stack.lastOrNull()
    when {
      depth == null -> finish(tokenStart + 1, T.RBRACE)
      depth == 0 -> {
        stack.removeAt(stack.lastIndex)
        finish(tokenStart + 1, T.TEMPLATE_END)
      }
      else -> {
        stack[stack.lastIndex] = depth - 1
        finish(tokenStart + 1, T.RBRACE)
      }
    }
  }

  private fun punctuation(c: Char, next: Char) {
    val two = "$c$next"
    when {
      two == "=>" -> finish(tokenStart + 2, T.ARROW)
      two in TWO_CHAR_OPERATORS -> finish(tokenStart + 2, T.OPERATOR)
      c == '.' && next == '.' && at(tokenStart + 2) == '.' -> finish(tokenStart + 3, T.ELLIPSIS)
      c in "+-*/%<>!" -> finish(tokenStart + 1, T.OPERATOR)
      else -> finish(tokenStart + 1, PUNCTUATION[c] ?: T.OTHER)
    }
  }

  private fun finish(at: Int, tokenType: IElementType) {
    tokenEnd = at
    type = tokenType
  }

  private inline fun skipWhile(from: Int, accept: (Char) -> Boolean): Int {
    var i = from
    while (i < end && accept(buffer[i])) i++
    return i
  }

  private fun blockCommentEnd(from: Int): Int {
    var i = from
    while (i + 1 < end && !(buffer[i] == '*' && buffer[i + 1] == '/')) i++
    return minOf(end, i + 2)
  }

  /** `<<EOF` or `<<-EOF`, with the marker alone on the rest of the line. */
  private fun heredocStart(): Boolean {
    var i = tokenStart + 2
    if (at(i) == '-') i++
    return at(i).isLetter() || at(i) == '_'
  }

  /** The end of the line that holds only the closing marker, or the end of the text when it is missing. */
  private fun heredocEnd(): Int {
    var i = tokenStart + 2
    if (at(i) == '-') i++
    val markerEnd = skipWhile(i) { it.isLetterOrDigit() || it == '_' || it == '-' }
    val marker = buffer.subSequence(i, markerEnd).toString()
    var lineStart = skipWhile(markerEnd) { it != '\n' } + 1
    while (lineStart < end) {
      val lineEnd = skipWhile(lineStart) { it != '\n' }
      if (buffer.subSequence(lineStart, lineEnd).trim().toString() == marker) return lineEnd
      lineStart = lineEnd + 1
    }
    return end
  }

  /** Digits with an optional fraction and exponent; a `.` not followed by a digit is an attribute access, as in `a.0.b`. */
  private fun numberEnd(): Int {
    var i = skipWhile(tokenStart + 1) { it.isDigit() }
    if (at(i) == '.' && at(i + 1).isDigit()) i = skipWhile(i + 1) { it.isDigit() }
    if (at(i) in "eE") {
      var j = i + 1
      if (at(j) in "+-") j++
      if (at(j).isDigit()) i = skipWhile(j) { it.isDigit() }
    }
    return i
  }

  private companion object {
    const val IN_STRING = -1
    val KEYWORDS = mapOf(
      "true" to T.LITERAL_KEYWORD, "false" to T.LITERAL_KEYWORD, "null" to T.LITERAL_KEYWORD,
      "for" to T.FOR, "in" to T.IN, "if" to T.IF,
    )
    val TWO_CHAR_OPERATORS = setOf("==", "!=", "<=", ">=", "&&", "||")
    val PUNCTUATION = mapOf(
      '(' to T.LPAREN, ')' to T.RPAREN, '[' to T.LBRACKET, ']' to T.RBRACKET, '=' to T.EQ, ':' to T.COLON,
      '?' to T.QUESTION, ',' to T.COMMA, '.' to T.DOT, '~' to T.STRIP,
    )
  }
}
