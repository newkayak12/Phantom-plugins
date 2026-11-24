// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.sidebar

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.util.concurrency.annotations.RequiresEdt
import dev.phantom.ai.core.PhantomAiBundle
import dev.phantom.ai.core.SessionStore

/**
 * Removes a worktree and its branch if merged, as in Orca: never while agent sessions run in it, and changed files need a
 * second, forced confirmation. Git runs on [background]; the sidebar's Remove worktree and the board's Done share it.
 */
class WorktreeRemoval(
  private val project: Project,
  private val creator: WorktreeCreator = WorktreeCreator(),
  private val background: (Runnable) -> Unit = { ApplicationManager.getApplication().executeOnPooledThread(it) },
) {
  /** [confirm]: ask first (the board's Done dialog has already asked). [removed] runs on the EDT once the worktree is gone. */
  @RequiresEdt
  fun remove(repo: String, path: String, branch: String?, confirm: Boolean = true, removed: () -> Unit = {}) {
    if (SessionStore.getInstance().forWorktree(path).isNotEmpty()) {
      Messages.showInfoMessage(project, PhantomAiBundle.message("worktree.remove.busy"), PhantomAiBundle.message("worktree.remove"))
      return
    }
    if (confirm && Messages.showYesNoDialog(project, PhantomAiBundle.message("worktree.remove.confirm", path, branch ?: "-"),
        PhantomAiBundle.message("worktree.remove"), null) != Messages.YES) return
    fun run(force: Boolean): Unit = background(Runnable {
      val error = creator.remove(repo, path, branch, force)
      ApplicationManager.getApplication().invokeLater({
        when {
          error == null -> removed()
          !force && Messages.showYesNoDialog(project, PhantomAiBundle.message("worktree.remove.force", error),
            PhantomAiBundle.message("worktree.remove"), null) == Messages.YES -> run(true)
          force -> Messages.showErrorDialog(project, error, PhantomAiBundle.message("worktree.remove"))
        }
      }, project.disposed)
    })
    run(false)
  }
}
