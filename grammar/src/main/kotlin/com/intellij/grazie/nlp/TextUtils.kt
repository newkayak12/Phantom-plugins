// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.grazie.nlp

import com.intellij.openapi.util.TextRange

/** Splits text into words: runs of letters and digits, with apostrophes and hyphens inside a word kept in it */
object Words {
  fun of(text: CharSequence): List<String> = ranges(text).map { it.subSequence(text).toString() }

  fun ranges(text: CharSequence): List<TextRange> {
    val result = ArrayList<TextRange>()
    var i = 0
    while (i < text.length) {
      if (!text[i].isLetterOrDigit()) {
        i++
        continue
      }
      val start = i
      while (i < text.length && (text[i].isLetterOrDigit() || isInnerJoiner(text, i))) i++
      result.add(TextRange(start, i))
    }
    return result
  }

  private fun isInnerJoiner(text: CharSequence, i: Int): Boolean {
    val c = text[i]
    return (c == '\'' || c == '’' || c == '-') && i + 1 < text.length && text[i + 1].isLetter() && i > 0 && text[i - 1].isLetter()
  }
}

object Levenshtein {
  /** The edit distance between two strings, or [limit] if it is at least that */
  @JvmStatic
  @JvmOverloads
  fun distance(a: CharSequence, b: CharSequence, limit: Int = Int.MAX_VALUE): Int {
    if (a == b) return 0
    if (kotlin.math.abs(a.length - b.length) >= limit) return limit
    var previous = IntArray(b.length + 1) { it }
    var current = IntArray(b.length + 1)
    for (i in 1..a.length) {
      current[0] = i
      var rowMin = current[0]
      for (j in 1..b.length) {
        val cost = if (a[i - 1] == b[j - 1]) 0 else 1
        current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
        rowMin = minOf(rowMin, current[j])
      }
      if (rowMin >= limit) return limit
      val swap = previous
      previous = current
      current = swap
    }
    return minOf(previous[b.length], limit)
  }

  /** The edit distance counting a swap of two adjacent letters as one edit ("tagret" is 1 from "target") */
  @JvmStatic
  fun withTranspositions(a: CharSequence, b: CharSequence): Int {
    val d = Array(a.length + 1) { IntArray(b.length + 1) }
    for (i in 0..a.length) d[i][0] = i
    for (j in 0..b.length) d[0][j] = j
    for (i in 1..a.length) {
      for (j in 1..b.length) {
        val cost = if (a[i - 1] == b[j - 1]) 0 else 1
        d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
        if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
      }
    }
    return d[a.length][b.length]
  }
}

/**
 * Finds where the prose of a commit message starts, after the prefixes such messages often begin with: an issue id
 * ("IDEA-1234", "[ABC-12]", "#123"), a component or conventional-commit type ("parser:", "fix(ui):", "[build]",
 * "cleanup [ui]:"), or a combination of those. They are not part of a sentence and shouldn't be checked as one.
 */
object CommitPrefixStripper {
  private val prefix = Regex(
    """^\s*(?:(?:\[[^\]\n]{1,40}]|\(?[A-Z][A-Z0-9_]+-\d+\)?|#\d+|[\w./\-]{1,40}(?:\s?[\[(][^\])\n]{1,40}[\])])?!?:(?=\s))[\s,:;\-]*)+"""
  )

  @JvmStatic
  fun stripPrefix(text: CharSequence): Int {
    val match = prefix.find(text) ?: return 0
    var end = match.range.last + 1
    while (end < text.length && text[end].isWhitespace()) end++
    // never swallow a whole message: "Fix:" alone stays checked
    return if (end >= text.length) 0 else end
  }
}
