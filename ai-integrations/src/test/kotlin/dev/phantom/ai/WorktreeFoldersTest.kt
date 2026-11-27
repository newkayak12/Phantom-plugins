// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import dev.phantom.ai.sidebar.FolderDrop
import dev.phantom.ai.sidebar.FolderNode
import dev.phantom.ai.sidebar.NestedWorktree
import dev.phantom.ai.sidebar.RepoNode
import dev.phantom.ai.sidebar.WorktreeFolders
import dev.phantom.ai.sidebar.WorktreeNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The worktree tree's folders: grouping and where a drop goes. */
class WorktreeFoldersTest {
  private fun node(path: String) = NestedWorktree(WorktreeNode(path, path, null, false, emptyList()), emptyList())

  @Test
  fun `folders show first with their worktrees in list order, empty ones too, then the rest`() {
    val top = listOf(node("/r/a"), node("/r/b"), node("/r/c"))
    val (grouped, rest) = WorktreeFolders.group(top, listOf("X" to listOf("/r/c", "/r/a"), "Y" to listOf("/r/a"), "Z" to emptyList()))
    assertEquals(listOf("X" to listOf("/r/a", "/r/c"), "Y" to emptyList<String>(), "Z" to emptyList()),
      grouped.map { (n, m) -> n to m.map { it.node.path } })
    assertEquals(listOf("/r/b"), rest.map { it.node.path })
  }

  @Test
  fun `a drop goes into the nearest folder, else to the top, never into another repository`() {
    val folder = FolderNode("/r", "X")
    val inFolder = WorktreeNode("/r/a", "a", null, false, emptyList())
    val repoOf = { p: String -> if (p.startsWith("/r/")) "/r" else "/s" }
    assertEquals(FolderDrop.Into("X"), WorktreeFolders.drop("/r", listOf(folder), repoOf))
    assertEquals(FolderDrop.Into("X"), WorktreeFolders.drop("/r", listOf(folder, inFolder), repoOf))
    assertEquals(FolderDrop.Top, WorktreeFolders.drop("/r", listOf(WorktreeNode("/r/b", "b", null, false, emptyList())), repoOf))
    assertEquals(FolderDrop.Top, WorktreeFolders.drop("/r", emptyList(), repoOf))
    assertEquals(FolderDrop.Top, WorktreeFolders.drop("/r", listOf(RepoNode("/r", "r", emptyList())), repoOf))
    assertNull(WorktreeFolders.drop("/r", listOf(RepoNode("/s", "s", emptyList())), repoOf))
    assertNull(WorktreeFolders.drop("/r", listOf(WorktreeNode("/s/b", "b", null, false, emptyList())), repoOf))
  }
}
