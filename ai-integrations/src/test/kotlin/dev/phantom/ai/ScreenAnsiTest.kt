// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import dev.phantom.ai.remote.AnsiColor
import dev.phantom.ai.remote.Run
import dev.phantom.ai.remote.ScreenAnsi
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenAnsiTest {
  private val e = "\u001b"

  @Test
  fun `plain lines are joined by crlf with none after the last, then cursor`() {
    val out = ScreenAnsi.render(listOf(listOf(Run("ab")), listOf(Run("cd"))), 80, 24, 2, 1)
    assertEquals("$e[H$e[2Jab\r\ncd$e[2;3H", out)
  }

  @Test
  fun `red bold run and 24-bit bg`() {
    val out = ScreenAnsi.render(listOf(listOf(Run("x", fg = AnsiColor.Indexed(1), bold = true), Run("y", bg = AnsiColor.Rgb(1, 2, 3)))), 10, 2, 0, 0)
    assertEquals("$e[H$e[2J$e[1;38;5;1mx$e[0m$e[48;2;1;2;3my$e[0m$e[1;1H", out)
  }

  @Test
  fun `italic underline inverse and indexed bg`() {
    val out = ScreenAnsi.render(listOf(listOf(Run("z", bg = AnsiColor.Indexed(200), italic = true, underline = true, inverse = true))), 10, 2, 0, 0)
    assertEquals("$e[H$e[2J$e[3;4;7;48;5;200mz$e[0m$e[1;1H", out)
  }

  @Test
  fun `cursor is clamped into the screen`() {
    assertEquals("$e[H$e[2J$e[2;5H", ScreenAnsi.render(emptyList(), 5, 2, 99, 99))
  }
}
