// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.workspaces.core

import com.intellij.ide.RecentProjectsManagerBase
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.InputValidatorEx
import com.intellij.openapi.ui.Messages
import com.intellij.util.concurrency.annotations.RequiresEdt
import com.intellij.util.messages.Topic
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** A worktree's folder moved from [oldPath] to [newPath]: views keyed by the path follow it. */
fun interface WorktreeRenameListener {
  fun renamed(oldPath: String, newPath: String)
}

/**
 * Renaming a worktree renames its folder: `git worktree move` to a folder of the new name next to it. The Projects and
 * Files list and Phantom AI Integration's worktree tree both rename through [rename], so the folder, the recent project
 * and both views show the same name. A worktree open as a project is refused: the platform has no way to move an open
 * project's base path, and closing and reopening it would lose its unsaved state.
 */
object WorktreeRename {
  @JvmField
  val TOPIC: Topic<WorktreeRenameListener> = Topic.create("Phantom worktree renamed", WorktreeRenameListener::class.java)

  private const val GIT_TIMEOUT_SECONDS = 60L

  /** The folder [path] moves to when named [name]: next to it; null when [name] is not one plain folder name. */
  fun target(path: Path, name: String): Path? {
    val clean = name.trim()
    if (clean.isEmpty() || clean in setOf(".", "..") || clean.any { it in "/\\" }) return null
    return path.resolveSibling(clean)
  }

  /** `git worktree move` of the worktree at [path] to [target]; git's message when it refuses, else null. Blocking. */
  fun move(path: Path, target: Path): String? {
    val checkout = GitCheckouts.read(path) ?: return PhantomWorkspacesBundle.message("worktree.rename.not.worktree")
    if (checkout.isMain) return PhantomWorkspacesBundle.message("worktree.rename.not.worktree")
    // git moves a worktree into an existing folder rather than refusing, as mv does
    if (Files.exists(target)) return PhantomWorkspacesBundle.message("worktree.rename.exists")
    val process = ProcessBuilder("git", "--git-dir=${checkout.commonDir}", "worktree", "move", path.toString(), target.toString())
      .redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    if (!process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
      process.destroy()
      return "git worktree move timed out"
    }
    return if (process.exitValue() == 0) null else output.trim().ifEmpty { "git worktree move failed" }
  }

  /** The open project whose base path is [path], if any. */
  fun openProjectIn(path: String): Project? {
    val real = real(Path.of(path))
    return ProjectManager.getInstance().openProjects.firstOrNull { p -> p.basePath?.let { real(Path.of(it)) == real } == true }
  }

  /**
   * Asks for a new name and renames the worktree at [path]: git runs on [background], then on the EDT the recent project
   * follows the new path, [TOPIC] is published and [done] gets the new path. Refuses a worktree open as a project.
   */
  @RequiresEdt
  fun rename(
    project: Project?,
    path: String,
    background: (Runnable) -> Unit = { ApplicationManager.getApplication().executeOnPooledThread(it) },
    done: (String) -> Unit = {},
  ) {
    val title = PhantomWorkspacesBundle.message("worktree.rename.title")
    val folder = Path.of(path)
    val current = folder.fileName?.toString().orEmpty()
    if (openProjectIn(path) != null) {
      Messages.showErrorDialog(project, PhantomWorkspacesBundle.message("worktree.rename.open", current), title)
      return
    }
    val validator = object : InputValidatorEx {
      override fun getErrorText(inputString: String): String? {
        val target = target(folder, inputString) ?: return PhantomWorkspacesBundle.message("worktree.rename.invalid")
        return if (target != folder && Files.exists(target)) PhantomWorkspacesBundle.message("worktree.rename.exists") else null
      }
    }
    val name = Messages.showInputDialog(project, PhantomWorkspacesBundle.message("worktree.rename.prompt"), title, null, current, validator)
      ?: return
    val target = target(folder, name) ?: return
    if (target == folder) return
    background(Runnable {
      // recent paths may name the folder another way (a symbolic link): matched now, while the folder still exists
      val manager = RecentProjectsManagerBase.getInstanceEx()
      val real = real(folder)
      val recent = manager.getRecentPaths().filter { real(Path.of(it)) == real }.toSet()
      val error = move(folder, target)
      ApplicationManager.getApplication().invokeLater {
        if (error != null) {
          Messages.showErrorDialog(project, error, title)
          return@invokeLater
        }
        val moved = target.toString()
        if (recent.isNotEmpty()) {
          manager.patchRecentPaths { if (it in recent) moved else it }
          // the name stored when it was last open is the old folder name
          manager.getProjectMetaInfo(moved)?.displayName = null
        }
        ApplicationManager.getApplication().messageBus.syncPublisher(TOPIC).renamed(path, moved)
        done(moved)
      }
    })
  }

  private fun real(path: Path): Path = try {
    path.toRealPath()
  }
  catch (_: IOException) {
    path.toAbsolutePath().normalize()
  }
}
