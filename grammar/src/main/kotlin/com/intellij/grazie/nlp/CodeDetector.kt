// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.grazie.nlp

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange

/**
 * Finds fragments of a text that look like code rather than prose: identifiers with dots, underscores, camel humps or
 * call parentheses (`foo.bar()`, `MAX_SIZE`, `getName`), and whole lines that read as statements (`x = y + 1;`, `} else {`)
 * or configuration entries (`limits:`, `cpu: 100m`).
 * People paste such fragments into comments and plain text without markup; grammar problems in them are noise.
 */
object CodeDetector {
  private val statementLine = Regex("""(^\s*[})\]].*)|(.*[;{]\s*$)|(.*\s(=|==|!=|->|=>|\+=|-=|&&|\|\|)\s.*)""")
  // a configuration entry: "limits:", "cpu: 100m", "key = value"
  private val keyValueLine = Regex("""\s*[\w.\-]+\s*[:=]\s*\S*\s*""")
  private val camelHump = Regex("""\p{Ll}\p{Lu}""")
  private val qualified = Regex("""\w[.:#]{1,2}\w""")

  @JvmStatic
  fun findCodeFragments(text: CharSequence): List<TextRange> {
    val result = ArrayList<TextRange>()
    var lineStart = 0
    while (lineStart <= text.length) {
      ProgressManager.checkCanceled()
      var lineEnd = lineStart
      while (lineEnd < text.length && text[lineEnd] != '\n') lineEnd++
      val line = text.subSequence(lineStart, lineEnd)
      if (line.isNotBlank()) {
        val tokens = tokens(line)
        val codeTokens = tokens.filter { looksLikeCodeToken(it.subSequence(line)) }
        if (statementLine.matches(line) || keyValueLine.matches(line) || tokens.size >= 2 && codeTokens.size * 2 >= tokens.size) {
          result.add(TextRange(lineStart, lineEnd))
        }
        else {
          codeTokens.forEach { result.add(it.shiftRight(lineStart)) }
        }
      }
      lineStart = lineEnd + 1
    }
    return result
  }

  @JvmStatic
  fun looksLikeCode(text: CharSequence): Boolean {
    val fragments = findCodeFragments(text)
    return fragments.sumOf { it.length } * 2 >= text.count { !it.isWhitespace() }
  }

  private fun tokens(line: CharSequence): List<TextRange> {
    val result = ArrayList<TextRange>()
    var i = 0
    while (i < line.length) {
      if (line[i].isWhitespace()) {
        i++
        continue
      }
      val start = i
      while (i < line.length && !line[i].isWhitespace()) i++
      // sentence punctuation around a token is prose
      var s = start
      var e = i
      while (s < e && line[s] in "\"'“‘(") s++
      while (e > s && line[e - 1] in ".,;:!?\"'”’") e--
      if (e > s) result.add(TextRange(s, e))
    }
    return result
  }

  private fun looksLikeCodeToken(token: CharSequence): Boolean {
    if (token.length < 2) return false
    if (token.contains("()") || token.endsWith(")") && token.contains("(") && !token.startsWith("(")) return true
    if (token.any { it == '{' || it == '}' || it == '<' || it == '>' || it == '=' || it == '[' || it == ']' }) return true
    if (token.contains('_') && token.any { it.isLetter() }) return true
    if (qualified.containsMatchIn(token) && !isAbbreviationOrNumber(token)) return true
    // getName, toString and HashMap are code; short ones like iPhone, macOS or eBay are names
    if (camelHump.containsMatchIn(token) && token.length > 6) return true
    return false
  }

  private fun isAbbreviationOrNumber(token: CharSequence): Boolean =
    token.all { it.isDigit() || it == '.' || it == ',' } || // 1.0, 3.14
    token.split('.').all { it.length <= 1 } // e.g, i.e
}
