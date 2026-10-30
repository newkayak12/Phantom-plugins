// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.realide.probe

import com.intellij.ide.impl.OpenProjectTask
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.options.advanced.AdvancedSettings
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.components.JBLabel
import java.awt.Component
import java.awt.Container
import java.awt.Point
import javax.swing.JComponent
import kotlinx.coroutines.delay

/**
 * Workspaces 0.3.0: a project switched to takes the tool window layout of the one in front.
 * Opens beta next to alpha, moves beta's Project tool window to the right, switches to alpha (Next Project Window) and
 * expects alpha's Project tool window on the right too; then with the setting off, a change in alpha stays in alpha.
 * Workspaces 0.4.0: dragging beta's tab onto the left edge of alpha's moves it first, in both windows' strips.
 */
internal object WorkspacesLayoutScenario : ProbeScenario {
  private const val SYNC = "phantom.workspaces.sync.tool.windows"
  private const val TW = "Project"

  override suspend fun run(ctx: ProbeContext) {
    val alpha = ctx.project
    val report = ctx.report
    report.step("setting.default", mapOf("sync" to true), mapOf("sync" to ctx.edt { AdvancedSettings.getBoolean(SYNC) }))
    ctx.waitUntil(30_000) { ToolWindowManager.getInstance(alpha).getToolWindow(TW) != null }

    val beta = ProjectManagerEx.getInstanceEx().openProjectAsync(ctx.fixtures.resolve("beta"), OpenProjectTask {
      forceOpenInNewFrame = true
      showWelcomeScreen = false
    })
    val betaShown = beta != null && ctx.waitUntil(30_000) {
      WindowManager.getInstance().getFrame(beta)?.isShowing == true && ToolWindowManager.getInstance(beta).getToolWindow(TW) != null
    }
    report.step("beta.opened", mapOf("shown" to true), mapOf("shown" to betaShown))
    if (beta == null || !betaShown) return
    delay(1000)

    ctx.edt { ToolWindowManager.getInstance(beta).getToolWindow(TW)!!.setAnchor(ToolWindowAnchor.RIGHT, null) }
    delay(500)
    report.step("beta.moved", mapOf("beta" to "right"), mapOf("beta" to ctx.edt { anchor(beta) }, "alpha" to ctx.edt { anchor(alpha) }))

    switchFrom(ctx, beta)
    val alphaFront = ctx.waitUntil(10_000) { WindowManager.getInstance().getFrame(alpha)?.isShowing == true }
    delay(1000)
    report.step("switch.copies", mapOf("alphaFront" to true, "alpha" to "right"),
                mapOf("alphaFront" to alphaFront, "alpha" to ctx.edt { anchor(alpha) }, "beta" to ctx.edt { anchor(beta) }))

    ctx.edt { AdvancedSettings.setBoolean(SYNC, false) }
    ctx.edt { ToolWindowManager.getInstance(alpha).getToolWindow(TW)!!.setAnchor(ToolWindowAnchor.LEFT, null) }
    delay(500)
    switchFrom(ctx, alpha)
    ctx.waitUntil(10_000) { WindowManager.getInstance().getFrame(beta)?.isShowing == true }
    delay(1000)
    report.step("switch.off.keeps", mapOf("alpha" to "left", "beta" to "right"), mapOf("alpha" to ctx.edt { anchor(alpha) }, "beta" to ctx.edt { anchor(beta) }))
    ctx.edt { AdvancedSettings.setBoolean(SYNC, true) }

    val dragged = ctx.edt {
      val strip = strip(beta) ?: return@edt mapOf("strip" to false)
      val tabs = tabs(strip)
      val from = tabs[beta.name] ?: return@edt mapOf("tabs" to tabs.keys.toList())
      val onto = tabs[alpha.name] ?: return@edt mapOf("tabs" to tabs.keys.toList())
      val y = from.height / 2
      ProbeInput.drag(from, Point(from.width / 2, y), Point(onto.x - from.x + 2, y))
      mapOf("before" to tabs.keys.toList(), "stripSize" to listOf(strip.width, strip.height))
    }
    delay(1000)
    report.step("drag.reorders", mapOf("beta" to listOf(beta.name, alpha.name), "alpha" to listOf(beta.name, alpha.name)),
                dragged + ctx.edt { mapOf("beta" to strip(beta)?.let { tabs(it).keys.toList() }, "alpha" to strip(alpha)?.let { tabs(it).keys.toList() }) })
  }

  private fun strip(p: Project): JComponent? {
    fun walk(c: Component): JComponent? {
      if (c.javaClass.name == "dev.phantom.workspaces.core.ProjectTabStrip") return c as JComponent
      if (c is Container) for (child in c.components) walk(child)?.let { return it }
      return null
    }
    return WindowManager.getInstance().getFrame(p)?.rootPane?.let(::walk)
  }

  /** Tab title to tab component, in strip order. */
  private fun tabs(strip: JComponent): Map<String, JComponent> = strip.components.filterIsInstance<JComponent>().mapNotNull { c ->
    c.components.filterIsInstance<JBLabel>().firstOrNull()?.let { it.text to c }
  }.toMap()


  private fun anchor(p: Project): String? = ToolWindowManager.getInstance(p).getToolWindow(TW)?.anchor?.toString()?.lowercase()

  /** Window | Next Project Window from [from]'s frame: with two projects, the other one. */
  private suspend fun switchFrom(ctx: ProbeContext, from: Project) {
    ctx.edt {
      val action = ActionManager.getInstance().getAction("NextProjectWindow")
      val event = AnActionEvent.createEvent(action, SimpleDataContext.getProjectContext(from), null, ActionPlaces.UNKNOWN, ActionUiKind.NONE, null)
      ActionUtil.performAction(action, event)
    }
  }
}
