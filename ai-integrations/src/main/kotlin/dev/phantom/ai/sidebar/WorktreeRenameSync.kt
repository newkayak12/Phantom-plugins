// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.sidebar

import dev.phantom.ai.core.AiState
import dev.phantom.ai.core.moveWorktree
import dev.phantom.workspaces.core.WorktreeRenameListener
import java.io.IOException
import java.nio.file.Path

/**
 * A worktree renamed in the Projects and Files list or in this tree (both through Phantom Workspaces' WorktreeRename):
 * its name, lineage, remembered sessions and folder follow the new path, and the tree shows the new name.
 */
class WorktreeRenameSync : WorktreeRenameListener {
  override fun renamed(oldPath: String, newPath: String) {
    val state = AiState.getInstance()
    val name = Path.of(newPath).fileName.toString()
    // the tree keeps paths as git lists them, the Projects and Files list resolves symbolic links (/var and /private/var
    // on macOS): a kept path is the old folder when it has the same name in the same parent folder
    val old = Path.of(oldPath)
    val known = (state.state.worktrees.flatMap { listOf(it.path, it.parentPath) } + state.state.sessions.map { it.worktree } +
      state.state.worktreeFolders.flatMap { it.worktrees }).filterNotNull().toSet()
    val aliases = known.filter { it == oldPath || sameFolder(Path.of(it), old) }.ifEmpty { listOf(oldPath) }
    for (alias in aliases) state.moveWorktree(alias, Path.of(alias).resolveSibling(name).toString(), name)
    SharedWorktrees.invalidateAll()
  }

  private fun sameFolder(a: Path, b: Path): Boolean =
    a.fileName == b.fileName && a.parent != null && b.parent != null && real(a.parent) == real(b.parent)

  private fun real(path: Path): Path = try {
    path.toRealPath()
  }
  catch (_: IOException) {
    path.normalize()
  }
}
