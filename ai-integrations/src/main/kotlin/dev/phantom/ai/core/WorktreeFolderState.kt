// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.core

// The worktree tree's folders and renamed worktrees in [AiState], apart from its class so it stays small.

// a list changed in place does not count as a change of the state: counted here so it is saved
private fun AiState.edited() {
  state.intIncrementModificationCount()
  changed()
}

fun AiState.folders(repo: String): List<WorktreeFolderEntry> = state.worktreeFolders.filter { it.repo == repo }

/** A new folder in [repo]; false when the name is empty or [repo] has a folder of that name. */
fun AiState.addFolder(repo: String, name: String): Boolean {
  val clean = name.trim()
  if (clean.isEmpty() || folders(repo).any { it.name == clean }) return false
  state.worktreeFolders.add(WorktreeFolderEntry().also { it.repo = repo; it.name = clean })
  edited()
  return true
}

fun AiState.renameFolder(repo: String, name: String, newName: String): Boolean {
  val clean = newName.trim()
  val folder = folders(repo).firstOrNull { it.name == name } ?: return false
  if (clean == name) return true
  if (clean.isEmpty() || folders(repo).any { it.name == clean }) return false
  folder.name = clean
  edited()
  return true
}

/** Deletes the folder; its worktrees go back to the top level. */
fun AiState.deleteFolder(repo: String, name: String) {
  state.worktreeFolders.removeIf { it.repo == repo && it.name == name }
  edited()
}

/** Puts [worktree] into [folder] of [repo], or at the top level when [folder] is null. */
fun AiState.moveToFolder(repo: String, worktree: String, folder: String?) {
  folders(repo).forEach { it.worktrees.remove(worktree) }
  folders(repo).firstOrNull { it.name == folder }?.worktrees?.add(worktree)
  edited()
}

/**
 * A worktree's folder moved from [old] to [new] (renamed in either view): its entry, the worktrees made from it, its
 * remembered sessions and its folder follow; the tree shows [name].
 */
fun AiState.moveWorktree(old: String, new: String, name: String) {
  state.worktrees.removeIf { it.path == new }
  state.worktrees.forEach { e ->
    if (e.path == old) e.path = new
    if (e.parentPath == old) e.parentPath = new
  }
  worktree(new).userName = name
  state.sessions.filter { it.worktree == old }.forEach { it.worktree = new }
  state.worktreeFolders.forEach { f -> f.worktrees.replaceAll { if (it == old) new else it } }
  edited()
}
