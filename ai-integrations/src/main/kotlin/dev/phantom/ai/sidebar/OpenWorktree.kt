// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.sidebar

import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.util.concurrency.annotations.RequiresEdt
import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.AgentSession
import dev.phantom.ai.terminal.AgentTerminalService
import dev.phantom.workspaces.core.ProjectTabsService
import java.nio.file.Path

/** Switches to the window of an open project at a path; false if none is open. Fakeable. */
fun interface ProjectSwitcher {
  fun switchTo(path: String): Boolean
}

/** Opens the project at a path in a new window; Workspaces picks the new frame up as a tab by itself. Fakeable. */
fun interface ProjectOpener {
  fun open(path: String)
}

/** The sidebar's actions on a worktree or a session. The only path that opens a window is [openInPhantom]. */
class SidebarActions(
  private val project: Project,
  private val switcher: ProjectSwitcher = ProjectSwitcher { ProjectTabsService.getInstance().switchTo(it) },
  private val opener: ProjectOpener = ProjectOpener { ProjectUtil.openOrImport(Path.of(it), project, true) },
) {
  private val terminal get() = AgentTerminalService.getInstance(project)

  /** Already open: bring its tab to front, no opener call. Else open it once. */
  @RequiresEdt
  fun openInPhantom(path: String) {
    if (switcher.switchTo(path)) return
    opener.open(path)
  }

  @RequiresEdt
  fun newSession(worktree: String, kind: AgentKind): String? = terminal.launch(worktree, kind)

  /** A live terminal is focused (unread cleared); else the session resumes under its own account; nothing without an id. */
  @RequiresEdt
  fun activate(session: AgentSession): String? = if (terminal.focus(session.tabId)) session.tabId else terminal.resume(session)

  fun canActivate(session: AgentSession): Boolean = terminal.canResume(session)

  companion object {
    fun openProjectCount(): Int = ProjectManager.getInstance().openProjects.size
  }
}
