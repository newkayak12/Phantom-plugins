// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.remote

/** A terminal colour: a palette index (0-255) or 24-bit rgb. */
sealed interface AnsiColor {
  data class Indexed(val index: Int) : AnsiColor
  data class Rgb(val r: Int, val g: Int, val b: Int) : AnsiColor
}

/** A run of text sharing one style. */
data class Run(
  val text: String,
  val fg: AnsiColor? = null,
  val bg: AnsiColor? = null,
  val bold: Boolean = false,
  val italic: Boolean = false,
  val underline: Boolean = false,
  val inverse: Boolean = false,
)

/** Renders a terminal screen as one full-frame ANSI string for xterm.js. */
object ScreenAnsi {
  private const val ESC = "\u001b"

  fun render(lines: List<List<Run>>, cols: Int, rows: Int, cursorX: Int, cursorY: Int): String {
    val sb = StringBuilder("$ESC[H$ESC[2J")
    lines.take(rows).forEachIndexed { i, runs ->
      if (i > 0) sb.append("\r\n")
      for (r in runs) {
        val codes = sgr(r)
        if (codes.isEmpty()) sb.append(r.text) else sb.append(ESC).append('[').append(codes.joinToString(";")).append('m').append(r.text).append("$ESC[0m")
      }
    }
    sb.append("$ESC[${cursorY.coerceIn(0, maxOf(rows - 1, 0)) + 1};${cursorX.coerceIn(0, maxOf(cols - 1, 0)) + 1}H")
    return sb.toString()
  }

  private fun sgr(r: Run): List<String> = buildList {
    if (r.bold) add("1")
    if (r.italic) add("3")
    if (r.underline) add("4")
    if (r.inverse) add("7")
    r.fg?.let { addAll(color(it, 38)) }
    r.bg?.let { addAll(color(it, 48)) }
  }

  private fun color(c: AnsiColor, base: Int): List<String> = when (c) {
    is AnsiColor.Indexed -> listOf("$base", "5", "${c.index.coerceIn(0, 255)}")
    is AnsiColor.Rgb -> listOf("$base", "2", "${c.r.coerceIn(0, 255)}", "${c.g.coerceIn(0, 255)}", "${c.b.coerceIn(0, 255)}")
  }
}
