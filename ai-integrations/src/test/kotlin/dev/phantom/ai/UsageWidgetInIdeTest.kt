// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.ai.usage.ClaudeUsage
import dev.phantom.ai.usage.CodexUsage
import dev.phantom.ai.usage.UsagePoller
import dev.phantom.ai.usage.UsageSources
import dev.phantom.ai.usage.UsageText
import dev.phantom.ai.usage.UsageWidget
import dev.phantom.ai.usage.UsageWindow

class UsageWidgetInIdeTest : BasePlatformTestCase() {
  private fun w(p: Double, m: Int, reset: Long? = null) = UsageWindow(p, m, reset)

  private class Fake(val claude: ClaudeUsage?, val codex: CodexUsage?) : UsageSources {
    var claudeDir: String? = "unset"; var codexHome: String? = "unset"
    override fun claude(claudeDir: String?) = claude.also { this.claudeDir = claudeDir }
    override fun codex(codexHome: String?) = codex.also { this.codexHome = codexHome }
  }

  fun testFactoryRegistered() {
    assertTrue(StatusBarWidgetFactory.EP_NAME.extensionList.any { it.id == "dev.phantom.ai.usage" })
  }

  fun testRendersTextFromFakeSources() {
    val src = Fake(ClaudeUsage(w(42.0, 300, 1790000000000), w(13.0, 10080), w(5.0, 10080), 1), CodexUsage(w(10.0, 300), w(3.0, 10080), 1))
    val poller = UsagePoller(src, { "/acct/claude" }, { "/acct/codex" })
    val widget = UsageWidget(poller)
    poller.pollOnce()
    assertEquals("Claude 5h 42% · wk 13% · Fable 5% | Codex 5h 10% · wk 3%", widget.getText())
    assertEquals("/acct/claude", src.claudeDir); assertEquals("/acct/codex", src.codexHome)
    assertTrue(widget.getTooltipText().contains("5h resets"))
  }

  fun testUnavailableSourceIsDash() {
    val poller = UsagePoller(Fake(null, CodexUsage(w(10.0, 300), w(3.0, 10080), 1)), { null }, { null })
    val widget = UsageWidget(poller)
    poller.pollOnce()
    assertEquals("Claude — | Codex 5h 10% · wk 3%", widget.getText())
    val none = UsagePoller(Fake(ClaudeUsage(w(1.0, 300), w(2.0, 10080), null, 1), null), { null }, { null })
    none.pollOnce()
    assertEquals("Claude 5h 1% · wk 2% | Codex —", UsageWidget(none).getText())
    assertTrue(UsageText.tooltip(none.snapshot).contains("Codex: unavailable"))
  }

  fun testStatusLinePayloadReachesWidgetAndStartIsNoopInTests() {
    val poller = UsagePoller(Fake(null, null), { null }, { null }, now = { 99 })
    val widget = UsageWidget(poller)
    var updates = 0
    poller.listeners.add { updates++ }
    poller.onStatusLine("""{"rate_limits":{"five_hour":{"used_percentage":30,"resets_at":1790000000},"seven_day":{"used_percentage":8}}}""")
    assertEquals("Claude 5h 30% · wk 8% | Codex —", widget.getText())
    assertEquals(1, updates)
    poller.start() // unit-test mode: no timer, no real dirs
    poller.stop()
  }
}
