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
import kotlinx.coroutines.delay

/**
 * Workspaces 0.3.0: a project switched to takes the tool window layout of the one in front.
 * Opens beta next to alpha, moves beta's Project tool window to the right, switches to alpha (Next Project Window) and
 * expects alpha's Project tool window on the right too; then with the setting off, a change in alpha stays in alpha.
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
  }


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
