// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.tabs

import dev.phantom.tabs.core.TabGroupsStartup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TabGroupsStartupTest {
  @Test
  fun looksForPanesWithBackoffForFifteenSecondsAtMost() {
    val delays = TabGroupsStartup.RETRY_DELAYS
    assertEquals(listOf(250L, 500L, 1_000L, 2_000L), delays.take(4))
    assertTrue("no wait longer than two seconds", delays.all { it in 1..2_000L })
    assertEquals(15_000L, delays.sum())
    assertTrue("a handful of EDT trips, not one every 250 ms for minutes", delays.size <= 10)
  }
}
