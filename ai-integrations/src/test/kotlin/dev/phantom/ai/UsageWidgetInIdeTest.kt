// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.ai.usage.CodexUsage
import dev.phantom.ai.usage.UsagePoller
import dev.phantom.ai.usage.UsageSources
import dev.phantom.ai.usage.UsageText
import dev.phantom.ai.usage.UsageWidget
import dev.phantom.ai.usage.UsageWindow

class UsageWidgetInIdeTest : BasePlatformTestCase() {
  private fun w(p: Double, m: Int, reset: Long? = null) = UsageWindow(p, m, reset)

  private class Fake(val codex: CodexUsage?) : UsageSources {
    var codexHome: String? = "unset"
    override fun codex(codexHome: String?) = codex.also { this.codexHome = codexHome }
  }

  private val statusLine = """{"rate_limits":{"five_hour":{"used_percentage":42,"resets_at":1790000000},"seven_day":{"used_percentage":13}}}"""

  fun testFactoryRegistered() {
    assertTrue(StatusBarWidgetFactory.EP_NAME.extensionList.any { it.id == "dev.phantom.ai.usage" })
  }

  fun testRendersTextFromFakeSources() {
    val src = Fake(CodexUsage(w(10.0, 300), w(3.0, 10080), 1))
    val poller = UsagePoller(src, { "/acct/codex" })
    val widget = UsageWidget(poller)
    poller.pollOnce()
    poller.onStatusLine(statusLine)
    assertEquals("Claude 5h 42% · wk 13% | Codex 5h 10% · wk 3%", widget.getText())
    assertEquals("/acct/codex", src.codexHome)
    assertTrue(widget.getTooltipText().contains("5h resets"))
  }

  fun testUnavailableSourceIsDash() {
    val poller = UsagePoller(Fake(CodexUsage(w(10.0, 300), w(3.0, 10080), 1)), { null })
    val widget = UsageWidget(poller)
    assertEquals("no data yet", "Claude — | Codex —", widget.getText())
    poller.pollOnce()
    assertEquals("Claude — | Codex 5h 10% · wk 3%", widget.getText())
    val none = UsagePoller(Fake(null), { null })
    none.pollOnce()
    none.onStatusLine(statusLine)
    assertEquals("Claude 5h 42% · wk 13% | Codex —", UsageWidget(none).getText())
    assertTrue(UsageText.tooltip(none.snapshot).contains("Codex: unavailable"))
  }

  fun testStatusLinePayloadReachesWidgetAndStartIsNoopInTests() {
    val poller = UsagePoller(Fake(null), { null }, now = { 99 })
    val widget = UsageWidget(poller)
    var updates = 0
    poller.listeners.add { updates++ }
    poller.onStatusLine("""{"rate_limits":{"five_hour":{"used_percentage":30,"resets_at":1790000000},"seven_day":{"used_percentage":8}}}""")
    assertEquals("Claude 5h 30% · wk 8% | Codex —", widget.getText())
    assertEquals(1, updates)
    poller.start() // unit-test mode: no timer, no real dirs
    poller.stop()
  }

  // the widget's poller does not create the hook server: it listens once it has started and the server exists
  fun testStatusLineListenerOnlyOnceStarted() {
    val server = dev.phantom.ai.hooks.HookServer.getInstance()
    val before = server.statuslineListeners.size
    val poller = UsagePoller(Fake(null), { null }, active = { false })
    poller.listenTo(server)
    assertEquals("not started: no listener", before, server.statuslineListeners.size)
    poller.begin()
    try {
      assertEquals(before + 1, server.statuslineListeners.size)
      assertFalse("the widget alone starts no Codex timer", poller.isCodexStarted)
      poller.listenTo(server)
      assertEquals("once only", before + 1, server.statuslineListeners.size)
    } finally {
      poller.stop()
    }
  }
}
