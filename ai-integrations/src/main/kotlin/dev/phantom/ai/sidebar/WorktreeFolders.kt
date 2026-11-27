// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.sidebar

/** A folder row of the worktree tree: a group the user made in [repo], nothing on disk. */
data class FolderNode(val repo: String, val name: String)

/** Where a worktree dropped on a row goes: into a folder, or back to the top level of its repository. */
sealed interface FolderDrop {
  data class Into(val folder: String) : FolderDrop

  data object Top : FolderDrop
}

/** The user's folders of the worktree tree: which worktrees each shows and where a drop puts one. Pure. */
object WorktreeFolders {
  /**
   * [top] (a repository's top-level worktrees) split by [folders] (name to member paths): each folder, empty ones too, with
   * its members in [top]'s order, then the worktrees in no folder. A worktree listed in two folders shows in the first.
   */
  fun group(top: List<NestedWorktree>, folders: List<Pair<String, List<String>>>): Pair<List<Pair<String, List<NestedWorktree>>>, List<NestedWorktree>> {
    val placed = HashSet<String>()
    val grouped = folders.map { (name, members) -> name to top.filter { it.node.path in members && placed.add(it.node.path) } }
    return grouped to top.filter { it.node.path !in placed }
  }

  /**
   * Where a worktree of [repo] dropped on the row whose objects from the top down are [chain] goes: into the nearest
   * folder row, else to the top level (also below the last row, an empty [chain]). Null for a row of another repository;
   * [repoOf] gives a worktree row's repository.
   */
  fun drop(repo: String, chain: List<Any?>, repoOf: (String) -> String?): FolderDrop? {
    val rowRepo = when (val first = chain.firstOrNull()) {
      null -> repo
      is RepoNode -> first.path
      is FolderNode -> first.repo
      is WorktreeNode -> repoOf(first.path)
      is SessionRow -> repoOf(first.session.worktree)
      else -> null
    }
    if (rowRepo != repo) return null
    val folder = chain.lastOrNull { it is FolderNode } as FolderNode?
    return folder?.let { FolderDrop.Into(it.name) } ?: FolderDrop.Top
  }
}
