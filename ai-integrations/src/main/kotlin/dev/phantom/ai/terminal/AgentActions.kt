// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.terminal

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.project.DumbAware
import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.AgentSession

/** Data keys the sidebar (U5) provides: the selected worktree path and the selected agent session. */
object AgentDataKeys {
  val WORKTREE: DataKey<String> = DataKey.create("phantom.ai.worktree")
  val SESSION: DataKey<AgentSession> = DataKey.create("phantom.ai.session")
}

abstract class NewSessionAction(private val kind: AgentKind) : AnAction(), DumbAware {
  override fun getActionUpdateThread() = ActionUpdateThread.BGT

  private fun worktree(e: AnActionEvent): String? =
    e.getData(AgentDataKeys.WORKTREE) ?: e.getData(AgentDataKeys.SESSION)?.worktree ?: e.project?.basePath

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = e.project != null && worktree(e) != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val path = worktree(e) ?: return
    AgentTerminalService.getInstance(project).launch(path, kind)
  }
}

class NewClaudeSessionAction : NewSessionAction(AgentKind.CLAUDE)

class NewCodexSessionAction : NewSessionAction(AgentKind.CODEX)

/** Resumes the selected session; disabled while there is no session id (hook or .jsonl). */
class ResumeSessionAction : AnAction(), DumbAware {
  override fun getActionUpdateThread() = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    val project = e.project
    val session = e.getData(AgentDataKeys.SESSION)
    e.presentation.isEnabled = project != null && session != null && AgentTerminalService.getInstance(project).canResume(session)
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val session = e.getData(AgentDataKeys.SESSION) ?: return
    AgentTerminalService.getInstance(project).resume(session)
  }
}
