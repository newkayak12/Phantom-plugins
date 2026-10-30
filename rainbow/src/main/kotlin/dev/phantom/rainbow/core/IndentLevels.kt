// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.rainbow.core

/** A run of indentation, [start, end) in the text, at one indent level (0 for the outermost). */
data class IndentSegment(val start: Int, val end: Int, val level: Int)

object IndentLevels {
  /**
   * Splits the indentation [start, end) of one line into one segment per indent level. A tab moves to the next tab stop;
   * a level is [indentSize] columns wide.
   */
  fun segments(text: CharSequence, start: Int, end: Int, indentSize: Int, tabSize: Int): List<IndentSegment> {
    val size = if (indentSize > 0) indentSize else 4
    val tab = if (tabSize > 0) tabSize else size
    val out = ArrayList<IndentSegment>()
    var column = 0
    var segmentStart = start
    var level = 0
    for (i in start until end) {
      val charLevel = column / size
      if (charLevel != level) {
        if (i > segmentStart) out += IndentSegment(segmentStart, i, level)
        segmentStart = i
        level = charLevel
      }
      column = if (text[i] == '\t') (column / tab + 1) * tab else column + 1
    }
    if (end > segmentStart) out += IndentSegment(segmentStart, end, level)
    return out
  }
}
