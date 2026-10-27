// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.workspaces.core

import com.intellij.ide.ReopenProjectAction
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.AnActionListener
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.impl.ProjectWindowAction

/**
 * Window | Next/Previous Project Window, replacing the platform's: they go through the tabs, so a hidden project window
 * is shown in place (the platform's only call toFront, which does nothing to a hidden window).
 */
internal abstract class SwitchProjectAction(private val offset: Int) : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabled = ProjectManager.getInstance().openProjects.size > 1
  }

  override fun actionPerformed(e: AnActionEvent) {
    ProjectTabsService.getInstance().switchBy(e.project, offset)
  }
}

internal class NextProjectAction : SwitchProjectAction(1)

internal class PreviousProjectAction : SwitchProjectAction(-1)

/**
 * The project entries of the Window menu and Open Recent on a project already open bring its window to front with
 * toFront, which does nothing to a hidden window: it is switched to first, in place.
 */
internal class ProjectSwitchActionListener : AnActionListener {
  override fun beforeActionPerformed(action: AnAction, event: AnActionEvent) {
    val path = when (action) {
      is ProjectWindowAction -> action.projectLocation.toString()
      is ReopenProjectAction -> action.projectPath
      else -> return
    }
    val service = serviceIfCreated<ProjectTabsService>() ?: return
    if (service.isEnabled) service.switchTo(path)
  }
}
