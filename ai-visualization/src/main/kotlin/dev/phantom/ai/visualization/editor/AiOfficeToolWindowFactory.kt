// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.visualization.editor

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import dev.phantom.ai.visualization.actions.ApiCheck
import javax.swing.SwingConstants

/** The "AI Visualization" tool window: the same office as the editor tab, reachable from its stripe icon like AI Integration's. */
class AiOfficeToolWindowFactory : ToolWindowFactory, DumbAware {
  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val component = if (ApiCheck.available) {
      val editor = AiOfficeEditor(project, AiOfficeFile.of(project))
      Disposer.register(toolWindow.disposable, editor)
      editor.component
    } else {
      JBLabel("Phantom AI Visualization needs Phantom AI Integration 0.3.8 or later.", SwingConstants.CENTER)
    }
    val content = ContentFactory.getInstance().createContent(component, "", false)
    content.isCloseable = false
    toolWindow.contentManager.addContent(content)
  }
}
