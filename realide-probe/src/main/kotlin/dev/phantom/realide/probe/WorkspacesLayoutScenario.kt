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
 * Workspaces 0.5.0: Detach to Separate Window on beta's tab frees beta's window 40 px off the stack with alpha back in
 * front; Window | Attach to Project Tabs puts beta back onto the stack.
 * Workspaces 0.6.0: Open Side by Side on alpha's tab puts beta on the left half and alpha on the right; making beta wider
 * narrows alpha; Exit Side by Side gives beta the whole place again.
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

    detachAndAttach(ctx, alpha, beta)
    sideBySide(ctx, alpha, beta)
  }

  /** Right-click on [tabOf]'s tab in [inFrameOf]'s strip and click [item]; whether it was there to click. */
  private suspend fun menu(ctx: ProbeContext, inFrameOf: Project, tabOf: Project, item: String): Boolean {
    val opened = ctx.edt {
      val tab = strip(inFrameOf)?.let(::tabs)?.get(tabOf.name) ?: return@edt false
      ProbeInput.popupTrigger(tab, Point(tab.width / 2, tab.height / 2))
      true
    }
    if (!opened) return false
    delay(500)
    return ctx.edt {
      val found = ProbeInput.openPopup()?.let(ProbeInput::items)?.firstOrNull { it.text == item && it.isEnabled } ?: return@edt false
      ProbeInput.doClick(found)
      true
    }
  }

  private suspend fun sideBySide(ctx: ProbeContext, alpha: Project, beta: Project) {
    val report = ctx.report
    val whole = ctx.edt { WindowManager.getInstance().getFrame(beta)?.bounds }
    val clicked = menu(ctx, beta, alpha, "Open Side by Side")
    delay(1500)
    fun pair(): Map<String, Any?> {
      val a = WindowManager.getInstance().getFrame(alpha)
      val b = WindowManager.getInstance().getFrame(beta)
      return mapOf(
        "alphaShown" to a?.isShowing, "betaShown" to b?.isShowing,
        // beta's right edge to alpha's left edge, and the two together against the whole place
        "seamGap" to if (a != null && b != null) a.x - (b.x + b.width) else null,
        "span" to if (a != null && b != null && whole != null) listOf(b.x - whole.x, a.x + a.width - (whole.x + whole.width)) else null,
        "betaWidth" to b?.width, "alphaWidth" to a?.width,
      )
    }
    report.step("split.open", mapOf("clicked" to true, "alphaShown" to true, "betaShown" to true, "seamGap" to 0, "span" to listOf(0, 0)),
                mapOf("clicked" to clicked, "whole" to whole?.let { listOf(it.x, it.y, it.width, it.height) }) + ctx.edt { pair() })

    val before = ctx.edt { WindowManager.getInstance().getFrame(beta)?.let { f -> f.setSize(f.width + 100, f.height); f.width } }
    delay(1500)
    report.step("split.resize", mapOf("seamGap" to 0, "span" to listOf(0, 0)), mapOf("betaWidthSet" to before) + ctx.edt { pair() })

    val exited = menu(ctx, beta, alpha, "Exit Side by Side")
    delay(1500)
    report.step("split.exit", mapOf("exited" to true, "alphaShown" to false, "betaShown" to true, "whole" to true),
                mapOf("exited" to exited, "alphaShown" to ctx.edt { WindowManager.getInstance().getFrame(alpha)?.isShowing },
                      "betaShown" to ctx.edt { WindowManager.getInstance().getFrame(beta)?.isShowing },
                      "whole" to ctx.edt { WindowManager.getInstance().getFrame(beta)?.bounds == whole }))
  }

  private suspend fun detachAndAttach(ctx: ProbeContext, alpha: Project, beta: Project) {
    val report = ctx.report
    // beta is in front since switch.off.keeps
    val opened = ctx.edt {
      val strip = strip(beta) ?: return@edt false
      val tab = tabs(strip)[beta.name] ?: return@edt false
      ProbeInput.popupTrigger(tab, Point(tab.width / 2, tab.height / 2))
      true
    }
    delay(500)
    val clicked = ctx.edt {
      val item = ProbeInput.openPopup()?.let(ProbeInput::items)?.firstOrNull { it.text == "Detach to Separate Window" } ?: return@edt false
      ProbeInput.doClick(item)
      true
    }
    ctx.waitUntil(10_000) { WindowManager.getInstance().getFrame(alpha)?.isShowing == true }
    delay(1000)
    report.step("detach.menu", mapOf("clicked" to true, "alphaShown" to true, "betaShown" to true, "offset" to listOf(40, 40)),
                mapOf("opened" to opened, "clicked" to clicked) + ctx.edt { frames(alpha, beta) })

    ctx.edt {
      val action = ActionManager.getInstance().getAction("PhantomWorkspaces.AttachProject")
      val event = AnActionEvent.createEvent(action, SimpleDataContext.getProjectContext(beta), null, ActionPlaces.UNKNOWN, ActionUiKind.NONE, null)
      ActionUtil.performAction(action, event)
    }
    delay(1500)
    report.step("attach.action", mapOf("alphaShown" to false, "betaShown" to true, "offset" to listOf(0, 0)), ctx.edt { frames(alpha, beta) })
  }

  private fun frames(alpha: Project, beta: Project): Map<String, Any?> {
    val a = WindowManager.getInstance().getFrame(alpha)
    val b = WindowManager.getInstance().getFrame(beta)
    return mapOf(
      "alphaShown" to a?.isShowing, "betaShown" to b?.isShowing,
      "offset" to if (a != null && b != null) listOf(b.x - a.x, b.y - a.y) else null,
      "alphaTabs" to strip(alpha)?.let { tabs(it).keys.toList() },
    )
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
