// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import dev.phantom.ai.usage.StatusLineUsage
import java.io.File
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class StatusLineUsageTest {
  private fun res(n: String) = File("src/test/resources/usage/$n").readText()

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
}
