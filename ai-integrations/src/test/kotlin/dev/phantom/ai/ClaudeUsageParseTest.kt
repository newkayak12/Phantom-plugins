// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import dev.phantom.ai.usage.ClaudeUsageClient
import dev.phantom.ai.usage.HttpReply
import dev.phantom.ai.usage.UsageHttp
import java.io.File
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class ClaudeUsageParseTest {
  private fun res(n: String) = File("src/test/resources/usage/$n").readText()

  @Test fun parsesFiveHourSevenDayAndFableScoped() {
    val u = ClaudeUsageClient.parse(res("claude-oauth-usage.json"), 5L)!!
    assertEquals(42.5, u.session!!.usedPercent, 0.0001)
    assertEquals(300, u.session!!.windowMinutes)
    assertEquals(java.time.Instant.parse("2026-10-28T15:00:00Z").toEpochMilli(), u.session!!.resetsAtMs)
    assertEquals(13.0, u.weekly!!.usedPercent, 0.0001)
    assertEquals(1793000000_000L, u.weekly!!.resetsAtMs)
    assertEquals(7.0, u.fableWeekly!!.usedPercent, 0.0001)
  }

  @Test fun legacyFableKeyAndClampAndNullWindow() {
    val u = ClaudeUsageClient.parse(res("claude-oauth-usage-legacy-fable.json"), 5L)!!
    assertEquals(100.0, u.session!!.usedPercent, 0.0001)
    assertNull(u.weekly)
    assertEquals(5.0, u.fableWeekly!!.usedPercent, 0.0001)
  }

  @Test fun badJsonIsNull() = assertNull(ClaudeUsageClient.parse("nope", 1L))

  @Test fun requestHasUrlBearerAndBetaHeader() {
    var url = ""
    var headers = emptyMap<String, String>()
    val c = ClaudeUsageClient(UsageHttp { u, h -> url = u; headers = h; HttpReply(200, res("claude-oauth-usage.json")) }) { 9L }
    assertEquals(9L, c.fetch("T")!!.updatedAtMs)
    assertEquals("https://api.anthropic.com/api/oauth/usage", url)
    assertEquals("Bearer T", headers["Authorization"])
    assertEquals("oauth-2025-04-20", headers["anthropic-beta"])
  }

  @Test fun httpErrorAndIoFailureGiveNull() {
    assertNull(ClaudeUsageClient(UsageHttp { _, _ -> HttpReply(401, "{}") }).fetch("T"))
    assertNull(ClaudeUsageClient(UsageHttp { _, _ -> throw java.io.IOException("x") }).fetch("T"))
  }
}
