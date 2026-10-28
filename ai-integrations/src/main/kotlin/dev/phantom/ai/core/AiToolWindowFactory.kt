// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.core

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import javax.swing.JComponent

/** The "AI Integrations" tool window. Units add their tab with one line in [createToolWindowContent] below. */
class AiToolWindowFactory : ToolWindowFactory, DumbAware {
  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    // ===== tabs: append one addTab(...) line per unit =====
    val projects = dev.phantom.ai.sidebar.ProjectGroupsPanel(project)
    com.intellij.openapi.util.Disposer.register(toolWindow.disposable, projects)
    addTab(toolWindow, PhantomAiBundle.message("tab.projects"), projects)
    val board = dev.phantom.ai.board.WorkspaceBoardPanel(project)
    com.intellij.openapi.util.Disposer.register(toolWindow.disposable, board)
    addTab(toolWindow, PhantomAiBundle.message("tab.board"), board)
  }

  companion object {
    /** Adds a permanent (not closable) tab; agent terminals (U4) add closable contents themselves. */
    fun addTab(toolWindow: ToolWindow, title: String, component: JComponent) {
      val content = ContentFactory.getInstance().createContent(component, title, false)
      content.isCloseable = false
      toolWindow.contentManager.addContent(content)
    }
  }
}
