// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.realide.probe

import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.JBLabel
import java.awt.Component
import java.awt.Container
import java.awt.Point
import javax.swing.JComponent
import javax.swing.JTree
import javax.swing.SwingUtilities

private const val TOOL_WINDOW = "AI Integrations"

/**
 * AI Integrations in a real IDE, through the UI only (no plugin class is imported; the tool window's components are found
 * by their Swing type, name and text). The fixture repo ("ai-repo", seeded into the sandbox's phantom-ai.xml by the
 * runIdeProbe task) is in the Projects tab. Steps: the tool window opens with its Projects and Board tabs; a card is
 * dragged to another column on the Board tab; New Claude Session is chosen from a worktree's right-click menu and an
 * agent terminal tab appears in the same tool window.
 */
internal object AiIntegrationsScenario : ProbeScenario {
  override suspend fun run(ctx: ProbeContext) {
    val report = ctx.report
    fun tw(): ToolWindow? = ToolWindowManager.getInstance(ctx.project).getToolWindow(TOOL_WINDOW)
    fun titles(): List<String> = tw()?.contentManager?.contents?.map { it.displayName.orEmpty() }.orEmpty()
    fun select(name: String): JComponent? = tw()?.contentManager?.contents?.firstOrNull { it.displayName == name }?.let { c ->
      tw()?.contentManager?.setSelectedContent(c)
      c.component
    }
    fun walk(c: Component, out: MutableList<Component> = ArrayList()): List<Component> {
      out += c
      if (c is Container) c.components.forEach { walk(it, out) }
      return out
    }

    ctx.edt { tw()?.show() }
    val opened = ctx.waitUntil { tw()?.isVisible == true && titles().containsAll(listOf("Projects", "Board")) }
    report.step(
      "toolwindow.opens",
      expected = mapOf("visible" to true, "hasProjectsTab" to true, "hasBoardTab" to true),
      observed = ctx.edt { mapOf("visible" to (tw()?.isVisible == true), "hasProjectsTab" to ("Projects" in titles()), "hasBoardTab" to ("Board" in titles()), "opened" to opened) },
    )

    // Board: drag the first card into another column
    val board = ctx.edt { select("Board") }
    fun cards(): List<JComponent> = board?.let { b -> walk(b).filterIsInstance<JComponent>().filter { it.name?.startsWith("card:") == true } }.orEmpty()
    fun columnTitles(): List<String> = board?.let { b -> walk(b).filterIsInstance<JBLabel>().map { it.text.orEmpty() }.filter { it.matches(Regex(".+ \\(\\d+\\)")) } }.orEmpty()
    val haveCard = ctx.waitUntil(30_000) { cards().isNotEmpty() && cards().first().isShowing }
    val before = ctx.edt { columnTitles() }
    val dragged = ctx.edt {
      val card = cards().firstOrNull()
      val label = columnTitles().let { t -> board?.let { b -> walk(b).filterIsInstance<JBLabel>().firstOrNull { it.text == t.getOrNull(1) } } }
      val body = label?.parent?.let { p -> p.components.firstOrNull { it !== label } }
      if (card == null || label == null || body == null || !card.isShowing) false
      else {
        val target = SwingUtilities.convertPoint(body, Point(body.width / 2, 10), card)
        ProbeInput.drag(card, Point(card.width / 2, card.height / 2), target)
        true
      }
    }
    val moved = ctx.waitUntil(10_000) { columnTitles().let { it.size >= 2 && it != before && it[1].endsWith("(1)") } }
    report.step(
      "board.drag.card",
      expected = mapOf("cardShown" to true, "dragged" to true, "movedToSecondColumn" to true, "via" to ProbeInput.VIA_QUEUE),
      observed = ctx.edt { mapOf("cardShown" to haveCard, "dragged" to dragged, "movedToSecondColumn" to moved, "via" to ProbeInput.VIA_QUEUE, "before" to before, "after" to columnTitles()) },
    )

    // Projects: right-click the worktree, New Claude Session, a terminal tab appears in the tool window
    val projects = ctx.edt { select("Projects") }
    fun tree(): JTree? = projects?.let { p -> walk(p).filterIsInstance<JTree>().firstOrNull() }
    fun worktreeRow(): Int? = tree()?.let { t ->
      (0 until t.rowCount).firstOrNull { t.getPathForRow(it).pathCount == 3 }
    }
    val haveRow = ctx.waitUntil(30_000) { tree()?.isShowing == true && worktreeRow() != null }
    val terminalsBefore = ctx.edt { titles().count { it.startsWith("claude") } }
    var clicked = false
    ctx.edt {
      val t = tree()
      val row = worktreeRow()
      if (t != null && row != null) {
        val r = t.getRowBounds(row)
        ProbeInput.popupTrigger(t, Point(r.x + r.width / 2, r.y + r.height / 2))
      }
    }
    val popup = ctx.waitUntilAny(10_000) { ProbeInput.openPopup()?.let { m -> ProbeInput.items(m).any { it.text == "New Claude Session" } } == true }
    ctx.edtAny {
      ProbeInput.openPopup()?.let { m -> ProbeInput.items(m).firstOrNull { it.text == "New Claude Session" }?.let { ProbeInput.doClick(it); clicked = true } }
    }
    val tab = ctx.waitUntil(30_000) { titles().count { it.startsWith("claude") } > terminalsBefore }
    // the tab opens before the shell runs its command: wait until the sandbox's claude stub has logged its start
    val callsLog = System.getProperty("phantom.ai.agentBinDir")?.let { java.io.File(it, "calls.log") }
    val stubRan = callsLog != null && ctx.waitUntilAny(30_000) { callsLog.isFile && callsLog.readLines().any { it.contains("/bin/claude") } }
    report.step(
      "terminal.new.claude.session",
      expected = mapOf("worktreeRow" to true, "menuHasNewClaudeSession" to true, "clicked" to true, "terminalTabAppeared" to true, "claudeStubStarted" to true, "via" to ProbeInput.VIA_POPUP),
      observed = ctx.edt { mapOf("worktreeRow" to haveRow, "menuHasNewClaudeSession" to popup, "clicked" to clicked, "terminalTabAppeared" to tab, "claudeStubStarted" to stubRan, "via" to ProbeInput.VIA_POPUP, "tabs" to titles()) },
    )
  }
}
