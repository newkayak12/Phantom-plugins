// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.workspaces.core

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.ide.RecentProjectsManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.util.concurrency.annotations.RequiresEdt
import java.awt.Desktop
import java.io.File

/** How a folder in the Projects and Files list is deleted, if at all. No UI and no platform state, so it is tested as is. */
enum class FolderDeletion {
  /** Open in a window: close it first. */
  NONE,

  /** A worktree: `git worktree remove`, which refuses one with changes, then gone from the list. */
  WORKTREE,

  /** Already gone from disk: only dropped from the list (and, for a worktree, from git's worktree list). */
  MISSING,

  /** Any other folder (a main checkout without worktrees, a plain folder): to the Trash, so it can be put back. */
  TRASH,
  ;

  companion object {
    fun of(row: WorkspaceRow, hasWorktrees: Boolean, exists: Boolean = true): FolderDeletion = when {
      row.isOpen -> NONE
      !exists -> MISSING
      row.isIndented && row.repository != null -> WORKTREE
      // the main checkout holds the repository its worktrees use
      row.isMain && hasWorktrees -> NONE
      else -> TRASH
    }
  }
}

/** Deletes [row]'s folder after asking, off the EDT, then forgets it as a recent project and calls [done] on the EDT. */
@RequiresEdt
internal fun deleteFolder(project: Project, row: WorkspaceRow, kind: FolderDeletion, done: () -> Unit) {
  if (kind == FolderDeletion.NONE) return
  if (kind == FolderDeletion.MISSING) {
    ApplicationManager.getApplication().executeOnPooledThread {
      row.repository?.let(::pruneWorktrees)
      ApplicationManager.getApplication().invokeLater {
        RecentProjectsManager.getInstance().removePath(row.path)
        done()
      }
    }
    return
  }
  val message = PhantomWorkspacesBundle.message(
    if (kind == FolderDeletion.WORKTREE) "list.delete.worktree.confirm" else "list.delete.folder.confirm", row.name, row.path)
  val title = PhantomWorkspacesBundle.message("list.delete.title")
  if (Messages.showYesNoDialog(project, message, title, Messages.getWarningIcon()) != Messages.YES) return
  ApplicationManager.getApplication().executeOnPooledThread {
    val error = when (kind) {
      FolderDeletion.WORKTREE -> removeWorktree(row.repository!!, row.path)
      else -> try {
        if (Desktop.getDesktop().moveToTrash(File(row.path))) null else PhantomWorkspacesBundle.message("list.delete.trash.failed")
      }
      catch (e: IllegalArgumentException) {
        e.message
      }
    }
    ApplicationManager.getApplication().invokeLater {
      if (error != null) {
        Messages.showErrorDialog(project, error, title)
      }
      else {
        RecentProjectsManager.getInstance().removePath(row.path)
        done()
      }
    }
  }
}

private const val GIT_TIMEOUT_MS = 60_000

/** `git worktree prune`: forgets worktrees whose folders are gone. */
internal fun pruneWorktrees(gitDir: String) {
  git("--git-dir=$gitDir", "worktree", "prune")
}

/** `git worktree remove` without --force: a worktree with changes or untracked files stays, and git says why. */
internal fun removeWorktree(gitDir: String, path: String): String? = git("--git-dir=$gitDir", "worktree", "remove", path)

/**
 * Runs git; null when it succeeded, else why not. GeneralCommandLine finds git on the PATH of the user's shell (a macOS
 * app started from the Dock has only the system PATH), and ExecUtil reads the output while it waits, so the timeout holds
 * even when git hangs. Git missing or failing to start is an answer here, not an exception on a pooled thread.
 */
private fun git(vararg args: String): String? {
  val command = GeneralCommandLine(listOf("git") + args).withRedirectErrorStream(true)
  val output = try {
    ExecUtil.execAndGetOutput(command, GIT_TIMEOUT_MS)
  }
  catch (e: ExecutionException) {
    return e.message ?: PhantomWorkspacesBundle.message("list.delete.git.failed")
  }
  return when {
    output.isTimeout -> PhantomWorkspacesBundle.message("list.delete.git.timeout")
    output.exitCode == 0 -> null
    else -> output.stdout.trim().ifEmpty { PhantomWorkspacesBundle.message("list.delete.git.failed") }
  }
}
