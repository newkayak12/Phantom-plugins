// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.workspaces.core

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.WelcomeScreenProjectProvider
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
import javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED

/** The Projects and Files tool window: the open and recent projects by repository ([ProjectsList]), beside Project. */
class ProjectsToolWindowFactory : ToolWindowFactory, DumbAware {
  // Home has the same list as its Project view pane
  override suspend fun isApplicableAsync(project: Project): Boolean =
    ProjectTabsService.getInstance().isEnabled && !WelcomeScreenProjectProvider.isWelcomeScreenProject(project)

  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val list = ProjectsList(project, toolWindow.disposable)
    val panel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
      border = JBUI.Borders.empty()
      add(JBPanel<JBPanel<*>>(BorderLayout()).apply {
        border = JBUI.Borders.empty(4, 8)
        add(list.searchField, BorderLayout.CENTER)
      }, BorderLayout.NORTH)
      add(ScrollPaneFactory.createScrollPane(list.list, VERTICAL_SCROLLBAR_AS_NEEDED, HORIZONTAL_SCROLLBAR_NEVER), BorderLayout.CENTER)
    }
    val content = toolWindow.contentManager.factory.createContent(panel, null, false)
    content.preferredFocusableComponent = list.searchField.textEditor
    toolWindow.contentManager.addContent(content)
  }
}

internal object ProjectsToolWindow {
  const val ID: String = "Projects and Files"
  private val SHOWN = Key.create<Boolean>("phantom.workspaces.projectsToolWindowShown")

  /**
   * Shows the tool window once when a project window opens, so the open projects are in view without the tab strip; closed
   * after that, it stays closed until the project is opened again.
   */
  fun showOnce(project: Project) {
    if (!ProjectTabsService.getInstance().isEnabled || project.getUserData(SHOWN) == true) return
    if (WelcomeScreenProjectProvider.isWelcomeScreenProject(project)) return
    project.putUserData(SHOWN, true)
    ToolWindowManager.getInstance(project).invokeLater {
      if (!project.isDisposed) ToolWindowManager.getInstance(project).getToolWindow(ID)?.show()
    }
  }
}
