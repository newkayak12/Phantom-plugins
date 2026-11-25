// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.workspaces.home

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.ex.ProjectClosingTransitionHandler
import com.intellij.openapi.wm.ex.WelcomeScreenProjectProvider
import com.intellij.openapi.wm.ex.getWelcomeScreenProjectProvider
import com.intellij.platform.ide.nonModalWelcomeScreen.isNonModalWelcomeScreenEnabled

/**
 * Closing the last open project (File | Close Project, the × in Projects and Files, or closing its window) brings Home
 * back in that project's frame, in place, instead of closing the frame and opening Home in a new one. The platform asks
 * this extension point (CloseProjectWindowHelper, and WelcomeScreenAwareCloseProjectAction of the welcome screen) before
 * it closes the last project; Home then opens with that project as projectToClose ([HomeProjectProvider]).
 */
internal class HomeClosingTransitionHandler : ProjectClosingTransitionHandler {
  override fun createTransitionHandler(projectToClose: Project): (suspend () -> Project?)? {
    if (!isNonModalWelcomeScreenEnabled || WelcomeScreenProjectProvider.isWelcomeScreenProject(projectToClose)) return null
    if (ProjectManager.getInstance().openProjects.any { it !== projectToClose }) return null
    val provider = getWelcomeScreenProjectProvider() as? HomeProjectProvider ?: return null
    return { WelcomeScreenProjectProvider.createOrOpenWelcomeScreenProject(provider, projectToClose) }
  }
}
