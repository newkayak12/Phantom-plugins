// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import dev.phantom.ai.terminal.TerminalDropText
import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalDropTextTest {
  private val start = "\u001b[200~"
  private val end = "\u001b[201~"

  @Test fun plainFileIsShellEscapedWithATrailingSpace() {
    assertEquals("/tmp/a.txt ", TerminalDropText.of(listOf("/tmp/a.txt")))
    assertEquals("'/tmp/my notes.md' ", TerminalDropText.of(listOf("/tmp/my notes.md")))
    assertEquals("'/tmp/it'\\''s.kt' ", TerminalDropText.of(listOf("/tmp/it's.kt")))
  }

  @Test fun imageIsABracketedPasteOfItsPath() {
    assertEquals("$start/tmp/shot.png$end ", TerminalDropText.of(listOf("/tmp/shot.png")))
    // spaces are backslash-escaped, never quoted
    assertEquals("$start/tmp/Screen\\ Shot\\ \\(1\\).PNG$end ", TerminalDropText.of(listOf("/tmp/Screen Shot (1).PNG")))
  }

  @Test fun noSpaceBetweenTwoImagesOnly() {
    assertEquals("$start/a.png$end$start/b.jpg$end /c.txt ", TerminalDropText.of(listOf("/a.png", "/b.jpg", "/c.txt")))
  }
}
