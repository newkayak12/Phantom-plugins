// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import dev.phantom.ai.usage.ClaudeUsage
import dev.phantom.ai.usage.StatusLineUsage
import dev.phantom.ai.usage.UsageWindow
import java.io.File
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class StatusLineUsageTest {
  private fun res(n: String) = File("src/test/resources/usage/$n").readText()
  private fun u(at: Long, fable: Double? = null) =
    ClaudeUsage(UsageWindow(1.0, 300, null), null, fable?.let { UsageWindow(it, 10080, null) }, at)

  @Test fun parsesRateLimits() {
    val x = StatusLineUsage.parse(res("claude-statusline.json"), 7L)!!
    assertEquals(12.5, x.session!!.usedPercent, 0.0001)
    assertEquals(1738425600_000L, x.session!!.resetsAtMs)
    assertEquals(40.0, x.weekly!!.usedPercent, 0.0001)
    assertEquals(7L, x.updatedAtMs)
  }

  @Test fun payloadWithoutRateLimitsIsNull() {
    assertNull(StatusLineUsage.parse(res("claude-statusline-no-limits.json"), 1L))
    assertNull(StatusLineUsage.parse("garbage", 1L))
  }

  @Test fun newestWins() {
    assertEquals(20L, StatusLineUsage.merge(u(10), u(20))!!.updatedAtMs)
    assertEquals(30L, StatusLineUsage.merge(u(30), u(20))!!.updatedAtMs)
    assertNull(StatusLineUsage.merge(null, null))
    assertEquals(5L, StatusLineUsage.merge(u(5), null)!!.updatedAtMs)
  }

  @Test fun newerStatusLineKeepsPollFable() {
    assertEquals(9.0, StatusLineUsage.merge(u(30), u(20, 9.0))!!.fableWeekly!!.usedPercent, 0.0001)
  }
}
