// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.usage

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.util.Consumer
import java.awt.event.MouseEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** Text and tooltip of the usage widget. No login prompt: an unavailable source is an em dash. */
object UsageText {
  const val DASH = "—"

  fun text(s: UsageSnapshot): String {
    val c = s.claude
    val claude = if (c == null || (c.session == null && c.weekly == null)) "Claude $DASH" else
      "Claude 5h ${pct(c.session)} · wk ${pct(c.weekly)}"
    val x = s.codex
    val codex = if (x == null) "Codex $DASH" else "Codex 5h ${pct(x.session)} · wk ${pct(x.weekly)}"
    return "$claude | $codex"
  }

  fun tooltip(s: UsageSnapshot, zone: ZoneId = ZoneId.systemDefault()): String {
    val f = DateTimeFormatter.ofPattern("EEE MMM d HH:mm").withZone(zone)
    fun reset(label: String, w: UsageWindow?) = w?.resetsAtMs?.let { "$label resets ${f.format(Instant.ofEpochMilli(it))}" }
    val c = s.claude
    val x = s.codex
    val lines = listOf(
      "Claude: " + (if (c == null) "unavailable" else listOfNotNull(reset("5h", c.session), reset("wk", c.weekly)).joinToString("; ").ifEmpty { "no reset times" }),
      "Codex: " + (if (x == null) "unavailable" else listOfNotNull(reset("5h", x.session), reset("wk", x.weekly)).joinToString("; ").ifEmpty { "no reset times" }),
    )
    return lines.joinToString("<br>", "<html>", "</html>")
  }

  private fun pct(w: UsageWindow?): String = w?.let { "${it.usedPercent.roundToInt()}%" } ?: DASH
}

class UsageStatusBarWidgetFactory : StatusBarWidgetFactory {
  override fun getId(): String = UsageWidget.ID
  override fun getDisplayName(): String = "AI Usage"
  override fun isAvailable(project: Project): Boolean = true
  override fun createWidget(project: Project): StatusBarWidget = UsageWidget(UsagePoller.getInstance())
  override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}

class UsageWidget(private val poller: UsagePoller) : StatusBarWidget, StatusBarWidget.TextPresentation {
  private var statusBar: StatusBar? = null
  private val listener: () -> Unit = { statusBar?.updateWidget(ID) }

  override fun ID(): String = ID
  override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

  override fun install(statusBar: StatusBar) {
    this.statusBar = statusBar
    poller.listeners.add(listener)
    poller.start()
  }

  override fun dispose() {
    poller.listeners.remove(listener)
    statusBar = null
  }

  override fun getText(): String = UsageText.text(poller.snapshot)
  override fun getTooltipText(): String = UsageText.tooltip(poller.snapshot)
  override fun getAlignment(): Float = 0f
  // Codex is looked at on a click too, for a user who wants its numbers before running a Codex session here
  override fun getClickConsumer(): Consumer<MouseEvent> = Consumer { poller.refreshNow() }

  companion object {
    const val ID = "dev.phantom.ai.usage"
  }
}
