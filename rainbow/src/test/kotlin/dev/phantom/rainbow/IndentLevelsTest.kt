// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.rainbow

import dev.phantom.rainbow.core.IndentLevels
import dev.phantom.rainbow.core.IndentSegment
import org.junit.Assert.assertEquals
import org.junit.Test

class IndentLevelsTest {
  @Test
  fun `spaces split per indent size`() {
    assertEquals(listOf(IndentSegment(0, 4, 0), IndentSegment(4, 8, 1), IndentSegment(8, 10, 2)),
                 IndentLevels.segments("          x", 0, 10, 4, 4))
  }

  @Test
  fun `a tab is one level when tab and indent size match`() {
    assertEquals(listOf(IndentSegment(1, 2, 0), IndentSegment(2, 3, 1)), IndentLevels.segments("\n\t\tx", 1, 3, 4, 4))
  }

  @Test
  fun `spaces then a tab reach the next tab stop`() {
    // two spaces and a tab end at column 4, so the second tab is level 1
    assertEquals(listOf(IndentSegment(0, 3, 0), IndentSegment(3, 4, 1)), IndentLevels.segments("  \t\tx", 0, 4, 4, 4))
  }

  @Test
  fun `zero indent size falls back to four`() {
    assertEquals(listOf(IndentSegment(0, 4, 0), IndentSegment(4, 5, 1)), IndentLevels.segments("     x", 0, 5, 0, 0))
  }
}
