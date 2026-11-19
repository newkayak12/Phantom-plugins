// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.http.core

import com.intellij.lexer.LexerBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

/**
 * Line-oriented lexer for `.http` files. Each line is classified by the section of the request block it is in
 * (between requests, headers, body, handler script) and split into tokens at once.
 *
 * The state is 0 only at the start of a line between requests, so incremental relexing restarts at those lines.
 */
class HttpLexer : LexerBase() {
  private var buffer: CharSequence = ""
  private var end = 0
  private var section = BETWEEN

  private val starts = IntArray(MAX_LINE_TOKENS)
  private val types = arrayOfNulls<IElementType>(MAX_LINE_TOKENS)
  private var count = 0
  private var current = 0
  /** End of the queued line tokens; also the start of the next line. */
  private var queueEnd = 0
  private var lineStartSection = BETWEEN

  override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
    this.buffer = buffer
    this.end = endOffset
    section = initialState % LINE_START_LIMIT
    count = 0
    current = 0
    queueEnd = startOffset
    advance()
  }

  override fun getState(): Int = if (current == 0) lineStartSection else lineStartSection + LINE_START_LIMIT
  override fun getTokenType(): IElementType? = if (current < count) types[current] else null
  override fun getTokenStart(): Int = starts[current]
  override fun getTokenEnd(): Int = if (current + 1 < count) starts[current + 1] else queueEnd
  override fun getBufferSequence(): CharSequence = buffer
  override fun getBufferEnd(): Int = end

  override fun advance() {
    if (current + 1 < count) {
      current++
      return
    }
    count = 0
    current = 0
    if (queueEnd >= end) return
    lexLine(queueEnd)
  }

  private fun add(start: Int, type: IElementType) {
    if (count > 0 && starts[count - 1] == start) {
      types[count - 1] = type
      return
    }
    if (count == MAX_LINE_TOKENS) return // pathological line: the rest joins the last token
    starts[count] = start
    types[count] = type
    count++
  }

  private fun lexLine(lineStart: Int) {
    lineStartSection = section
    var lineEnd = lineStart
    while (lineEnd < end && buffer[lineEnd] != '\n') lineEnd++
    val next = if (lineEnd < end) lineEnd + 1 else lineEnd
    val content = trimEnd(lineStart, lineEnd)
    val first = skipSpaces(lineStart, content)

    if (first == content) { // blank line
      add(lineStart, TokenType.WHITE_SPACE)
      if (section == AFTER_REQUEST || section == HEADERS) section = BODY
      queueEnd = next
      return
    }
    if (section != SCRIPT && section != LONE_SCRIPT && startsWith(first, content, "###")) {
      separator(lineStart, first, content)
      section = BETWEEN
    }
    else {
      when (section) {
        BETWEEN -> between(lineStart, first, content)
        AFTER_REQUEST, HEADERS -> headers(lineStart, first, content)
        BODY -> body(lineStart, first, content)
        SCRIPT, LONE_SCRIPT -> script(lineStart, content)
        else -> afterHandler(lineStart, first, content)
      }
    }
    if (content < next) add(content, TokenType.WHITE_SPACE)
    queueEnd = next
  }

  private fun separator(lineStart: Int, first: Int, content: Int) {
    if (first > lineStart) add(lineStart, TokenType.WHITE_SPACE)
    add(first, HttpTokens.SEPARATOR)
    val nameStart = skipSpaces(first + 3, content)
    if (nameStart > first + 3) add(first + 3, TokenType.WHITE_SPACE)
    if (nameStart < content) add(nameStart, HttpTokens.REQUEST_NAME)
  }

  private fun between(lineStart: Int, first: Int, content: Int) {
    if (first > lineStart) add(lineStart, TokenType.WHITE_SPACE)
    when {
      isComment(first, content) -> add(first, if (isDirective(first, content)) HttpTokens.DIRECTIVE else HttpTokens.COMMENT)
      buffer[first] == '@' -> fileVariable(first, content)
      // `> {% %}` with no request before it: a handler, never a request to `>` (it runs with no request)
      (buffer[first] == '>' || buffer[first] == '<') && (first + 1 == content || buffer[first + 1].isWhitespace()) -> {
        bodyLine(first, content)
        if (section == SCRIPT) section = LONE_SCRIPT else if (section == AFTER_HANDLER) section = BETWEEN
      }
      // `import ./a.http` and `run #name` / `run ./a.http (@v=x)`: a line of its own, no request follows from it
      keyword(first, content, "import") -> importLine(first, content)
      keyword(first, content, "run") -> runLine(first, content)
      else -> {
        requestLine(first, content)
        section = AFTER_REQUEST
      }
    }
  }

  private fun fileVariable(first: Int, content: Int) {
    var i = first + 1
    while (i < content && isNameChar(buffer[i])) i++
    add(first, HttpTokens.FILE_VARIABLE_NAME)
    i = whitespace(i, content)
    if (i < content && buffer[i] == '=') {
      add(i, HttpTokens.EQ)
      i = whitespace(i + 1, content)
    }
    if (i < content) text(i, content, HttpTokens.VALUE_TEXT, splitSpaces = false)
  }

  /** [word] followed by whitespace and something more. */
  private fun keyword(first: Int, content: Int, word: String): Boolean {
    val after = first + word.length
    return startsWith(first, content, word) && after < content && buffer[after].isWhitespace() && spaces(after, content) < content
  }

  private fun importLine(first: Int, content: Int) {
    add(first, HttpTokens.IMPORT_KEYWORD)
    add(whitespace(first + "import".length, content), HttpTokens.IMPORT_PATH)
  }

  private fun runLine(first: Int, content: Int) {
    add(first, HttpTokens.RUN_KEYWORD)
    val target = whitespace(first + "run".length, content)
    // `(@var=value, ...)` at the end of the line; a request name may itself contain spaces and parentheses before it
    var variables = -1
    if (buffer[content - 1] == ')') {
      var i = content - 1
      while (i > target && buffer[i] != '(') i--
      if (i > target && buffer[i] == '(' && spaces(i + 1, content) < content && buffer[spaces(i + 1, content)] == '@') variables = i
    }
    var targetEnd = if (variables > 0) variables else content
    while (targetEnd > target && buffer[targetEnd - 1].isWhitespace()) targetEnd--
    add(target, if (buffer[target] == '#') HttpTokens.RUN_REQUEST_NAME else HttpTokens.RUN_PATH)
    if (variables > 0) {
      if (variables > targetEnd) add(targetEnd, TokenType.WHITE_SPACE)
      add(variables, HttpTokens.RUN_VARIABLES)
    }
  }

  private fun requestLine(first: Int, content: Int) {
    var i = first
    var wordEnd = first
    while (wordEnd < content && !buffer[wordEnd].isWhitespace()) wordEnd++
    if (buffer.subSequence(first, wordEnd).toString() in HttpTokens.METHODS) {
      add(first, HttpTokens.METHOD)
      i = whitespace(wordEnd, content)
    }
    var targetEnd = content
    val version = versionStart(i, content)
    if (version > 0) {
      targetEnd = version
      while (targetEnd > i && buffer[targetEnd - 1].isWhitespace()) targetEnd--
    }
    text(i, targetEnd, HttpTokens.URL_TEXT, splitSpaces = true)
    if (version > 0) {
      if (version > targetEnd) add(targetEnd, TokenType.WHITE_SPACE)
      add(version, HttpTokens.HTTP_VERSION)
    }
  }

  /** Start of a trailing ` HTTP/1.1` or ` HTTP/2`, or -1. */
  private fun versionStart(from: Int, content: Int): Int {
    var i = content
    while (i > from && !buffer[i - 1].isWhitespace()) i--
    if (i == from || content - i < 6) return -1
    return if (startsWith(i, content, "HTTP/")) i else -1
  }

  private fun headers(lineStart: Int, first: Int, content: Int) {
    if (first > lineStart) add(lineStart, TokenType.WHITE_SPACE)
    val c = buffer[first]
    when {
      section == AFTER_REQUEST && first > lineStart && (c == '?' || c == '&') -> text(first, content, HttpTokens.URL_TEXT, splitSpaces = true)
      isComment(first, content) -> {
        add(first, HttpTokens.COMMENT)
        section = HEADERS
      }
      c == '>' || c == '<' -> {
        section = BODY
        bodyLine(first, content)
      }
      else -> {
        var colon = first
        while (colon < content && buffer[colon] != ':') colon++
        add(first, HttpTokens.HEADER_NAME)
        if (colon < content) {
          var nameEnd = colon
          while (nameEnd > first && buffer[nameEnd - 1].isWhitespace()) nameEnd--
          if (nameEnd < colon) add(nameEnd, TokenType.WHITE_SPACE)
          add(colon, HttpTokens.COLON)
          val value = whitespace(colon + 1, content)
          if (value < content) text(value, content, HttpTokens.HEADER_VALUE, splitSpaces = false)
        }
        section = HEADERS
      }
    }
  }

  private fun body(lineStart: Int, first: Int, content: Int) {
    val c = buffer[first]
    if (c == '<' || c == '>') {
      if (first > lineStart) add(lineStart, TokenType.WHITE_SPACE)
      bodyLine(first, content)
    }
    else {
      text(lineStart, content, HttpTokens.BODY_TEXT, splitSpaces = false)
    }
  }

  /** A body line starting with `<` or `>`: file input, handler, or (unsupported) response reference. */
  private fun bodyLine(first: Int, content: Int) {
    val next = if (first + 1 < content) buffer[first + 1] else ' '
    when {
      buffer[first] == '<' && next.isWhitespace() -> add(first, HttpTokens.INPUT_FILE)
      buffer[first] == '>' && next.isWhitespace() -> handler(first, content)
      buffer[first] == '<' -> text(first, content, HttpTokens.BODY_TEXT, splitSpaces = false) // `<xml>` body
      else -> add(first, HttpTokens.COMMENT) // `>>`, `<>`: not supported, kept out of the body
    }
  }

  private fun handler(first: Int, content: Int) {
    val open = spaces(first + 1, content)
    if (!startsWith(open, content, "{%")) {
      add(first, HttpTokens.HANDLER_FILE)
      section = AFTER_HANDLER
      return
    }
    add(first, HttpTokens.HANDLER_START)
    section = SCRIPT
    if (open + 2 < content) script(open + 2, content)
  }

  private fun script(from: Int, content: Int) {
    var i = from
    while (i + 1 < content && !(buffer[i] == '%' && buffer[i + 1] == '}')) i++
    if (i + 1 < content) {
      if (i > from) add(from, HttpTokens.SCRIPT)
      add(i, HttpTokens.HANDLER_END)
      if (i + 2 < content) add(i + 2, HttpTokens.COMMENT)
      section = if (section == LONE_SCRIPT) BETWEEN else AFTER_HANDLER
    }
    else {
      add(from, HttpTokens.SCRIPT)
    }
  }

  private fun afterHandler(lineStart: Int, first: Int, content: Int) {
    if (first > lineStart) add(lineStart, TokenType.WHITE_SPACE)
    if (buffer[first] == '>' && first + 1 < content && buffer[first + 1].isWhitespace()) handler(first, content)
    else add(first, HttpTokens.COMMENT)
  }

  /** Text with `{{variable}}` references; whitespace runs become separate tokens when [splitSpaces]. */
  private fun text(from: Int, to: Int, type: IElementType, splitSpaces: Boolean) {
    var i = from
    var textStart = from
    while (i < to) {
      val c = buffer[i]
      if (c == '{' && i + 1 < to && buffer[i + 1] == '{') {
        val close = indexOf("}}", i + 2, to)
        if (close >= 0) {
          if (i > textStart) add(textStart, type)
          add(i, HttpTokens.LBRACES)
          if (close > i + 2) add(i + 2, HttpTokens.VARIABLE_NAME)
          add(close, HttpTokens.RBRACES)
          i = close + 2
          textStart = i
          continue
        }
      }
      if (splitSpaces && c.isWhitespace()) {
        if (i > textStart) add(textStart, type)
        add(i, TokenType.WHITE_SPACE)
        i = spaces(i, to)
        textStart = i
        continue
      }
      i++
    }
    if (to > textStart) add(textStart, type)
  }

  private fun isComment(first: Int, content: Int): Boolean =
    buffer[first] == '#' || startsWith(first, content, "//")

  private fun isDirective(first: Int, content: Int): Boolean {
    val i = spaces(first + if (buffer[first] == '#') 1 else 2, content)
    return i < content && buffer[i] == '@'
  }

  /** Skips whitespace and emits it as a token, so it does not stick to the previous token. */
  private fun whitespace(from: Int, to: Int): Int {
    val i = spaces(from, to)
    if (i > from) add(from, TokenType.WHITE_SPACE)
    return i
  }

  private fun spaces(from: Int, to: Int): Int {
    var i = from
    while (i < to && buffer[i].isWhitespace()) i++
    return i
  }

  private fun skipSpaces(from: Int, to: Int): Int = spaces(from, to)

  private fun trimEnd(from: Int, to: Int): Int {
    var i = to
    while (i > from && buffer[i - 1].isWhitespace()) i--
    return i
  }

  private fun startsWith(at: Int, to: Int, s: String): Boolean {
    if (at + s.length > to) return false
    for (k in s.indices) if (buffer[at + k] != s[k]) return false
    return true
  }

  private fun indexOf(s: String, from: Int, to: Int): Int {
    var i = from
    while (i + s.length <= to) {
      if (startsWith(i, to, s)) return i
      i++
    }
    return -1
  }

  companion object {
    const val BETWEEN: Int = 0
    const val AFTER_REQUEST: Int = 1
    const val HEADERS: Int = 2
    const val BODY: Int = 3
    const val SCRIPT: Int = 4
    const val AFTER_HANDLER: Int = 5
    /** A handler script with no request line before it; the lines after it are between requests again. */
    const val LONE_SCRIPT: Int = 6
    private const val LINE_START_LIMIT = 8
    private const val MAX_LINE_TOKENS = 512

    fun isNameChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '-' || c == '.'
  }
}
