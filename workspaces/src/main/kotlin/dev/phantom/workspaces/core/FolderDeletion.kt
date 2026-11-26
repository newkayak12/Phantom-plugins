// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.workspaces.core

import com.intellij.ide.RecentProjectsManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.util.concurrency.annotations.RequiresEdt
import java.awt.Desktop
import java.io.File
import java.util.concurrent.TimeUnit

/** How a folder in the Projects and Files list is deleted, if at all. No UI and no platform state, so it is tested as is. */
enum class FolderDeletion {
  /** Open in a window: close it first. */
  NONE,

  /** A worktree: `git worktree remove`, which refuses one with changes, then gone from the list. */
  WORKTREE,

  /** Any other folder (a main checkout without worktrees, a plain folder): to the Trash, so it can be put back. */
  TRASH,
  ;

  companion object {
    fun of(row: WorkspaceRow, hasWorktrees: Boolean): FolderDeletion = when {
      row.isOpen -> NONE
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
  val message = PhantomWorkspacesBundle.message(
    if (kind == FolderDeletion.WORKTREE) "list.delete.worktree.confirm" else "list.delete.folder.confirm", row.name, row.path)
  val title = PhantomWorkspacesBundle.message("list.delete.title")
  if (Messages.showYesNoDialog(project, message, title, Messages.getWarningIcon()) != Messages.YES) return
  ApplicationManager.getApplication().executeOnPooledThread {
    val error = when (kind) {
      FolderDeletion.WORKTREE -> removeWorktree(row.repository!!, row.path)
      else -> if (Desktop.getDesktop().moveToTrash(File(row.path))) null else PhantomWorkspacesBundle.message("list.delete.trash.failed")
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

private const val GIT_TIMEOUT_SECONDS = 60L

/** `git worktree remove` without --force: a worktree with changes or untracked files stays, and git says why. */
internal fun removeWorktree(gitDir: String, path: String): String? {
  val process = ProcessBuilder("git", "--git-dir=$gitDir", "worktree", "remove", path).redirectErrorStream(true).start()
  val output = process.inputStream.bufferedReader().readText()
  if (!process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
    process.destroy()
    return "git worktree remove timed out"
  }
  return if (process.exitValue() == 0) null else output.trim().ifEmpty { "git worktree remove failed" }
}
