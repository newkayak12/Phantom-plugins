// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.core

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import dev.phantom.ai.toolwindow.AgentToolWindowPanel
import dev.phantom.ai.toolwindow.ToolWindowActions

/** The "Phantom AI Integration" tool window: exactly one content, the worktree tree beside the agent terminal tabs. */
class AiToolWindowFactory : ToolWindowFactory, DumbAware {
  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val panel = AgentToolWindowPanel(project, toolWindow.disposable)
    val content = ContentFactory.getInstance().createContent(panel, "", false)
    content.isCloseable = false
    toolWindow.contentManager.addContent(content)
    ToolWindowActions.install(toolWindow, project, panel)
  }
}
