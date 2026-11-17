// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.dashboard

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.ui.FrameWrapper
import dev.phantom.ai.core.PhantomAiBundle
import java.awt.Dimension

/** The Agent Dashboard as its own window (a [FrameWrapper], not a tool window). One per project; reopening raises it. */
class AgentDashboardFrame(val project: Project, val panel: AgentDashboardPanel) {
  private var wrapper: FrameWrapper? = null

  fun show() {
    wrapper?.takeIf { !Disposer.isDisposed(it) }?.let { it.show(); return }
    val w = FrameWrapper(project, "dev.phantom.ai.AgentDashboard", false, PhantomAiBundle.message("dashboard.title"), panel)
    w.setSize(Dimension(1100, 700))
    Disposer.register(w, panel)
    wrapper = w
    w.show()
  }

  fun isShowing(): Boolean = wrapper?.let { !Disposer.isDisposed(it) } == true

  companion object {
    private val frames = HashMap<Project, AgentDashboardFrame>()

    /** Opens (or raises) the dashboard window of [project]. */
    fun open(project: Project): AgentDashboardFrame {
      val f = synchronized(frames) {
        frames.getOrPut(project) {
          val panel = AgentDashboardPanel(project)
          Disposer.register(project, panel)
          Disposer.register(panel) { synchronized(frames) { frames.remove(project) } }
          AgentDashboardFrame(project, panel)
        }
      }
      f.show()
      return f
    }
  }
}

class OpenAgentDashboardAction : AnAction(), DumbAware {
  override fun actionPerformed(e: AnActionEvent) {
    AgentDashboardFrame.open(e.project ?: return)
  }
}
