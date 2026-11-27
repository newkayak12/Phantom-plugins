// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.workspaces

import dev.phantom.workspaces.core.FolderDeletion
import dev.phantom.workspaces.core.WorkspaceRow
import dev.phantom.workspaces.core.pruneWorktrees
import dev.phantom.workspaces.core.removeWorktree
import junit.framework.TestCase
import java.nio.file.Files
import java.nio.file.Path

class FolderDeletionTest : TestCase() {
  private fun row(isIndented: Boolean = false, isMain: Boolean = false, repository: String? = null, key: Any? = null) =
    WorkspaceRow("/p", "p", "~/p", null, repository, isIndented, isMain, key, false)

  fun testAnOpenFolderIsNotDeleted() {
    assertEquals(FolderDeletion.NONE, FolderDeletion.of(row(isIndented = true, repository = "/r/.git", key = Any()), false))
  }

  fun testAWorktreeIsRemovedThroughGit() {
    assertEquals(FolderDeletion.WORKTREE, FolderDeletion.of(row(isIndented = true, repository = "/r/.git"), true))
  }

  fun testTheMainCheckoutOfWorktreesStays() {
    assertEquals(FolderDeletion.NONE, FolderDeletion.of(row(isMain = true, repository = "/r/.git"), true))
  }

  fun testOtherFoldersGoToTheTrash() {
    assertEquals(FolderDeletion.TRASH, FolderDeletion.of(row(isMain = true, repository = "/r/.git"), false))
    assertEquals(FolderDeletion.TRASH, FolderDeletion.of(row(), false))
  }

  fun testAFolderGoneFromDiskIsOnlyDroppedFromTheList() {
    assertEquals(FolderDeletion.MISSING, FolderDeletion.of(row(), false, exists = false))
    assertEquals(FolderDeletion.MISSING, FolderDeletion.of(row(isIndented = true, repository = "/r/.git"), true, exists = false))
    assertEquals(FolderDeletion.NONE, FolderDeletion.of(row(key = Any()), false, exists = false))
  }

  fun testPruneForgetsAWorktreeWhoseFolderIsGone() {
    val root = Files.createTempDirectory("folder-prune").toRealPath()
    val main = Files.createDirectory(root.resolve("main"))
    git(main, "init", "-q")
    git(main, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "--allow-empty", "-m", "first")
    val gone = root.resolve("gone")
    git(main, "worktree", "add", "-q", "-b", "gone", gone.toString())
    gone.toFile().deleteRecursively()
    pruneWorktrees(main.resolve(".git").toString())
    val list = ProcessBuilder("git", "-C", main.toString(), "worktree", "list").start().inputStream.bufferedReader().readText()
    assertFalse(list.contains(gone.toString()))
  }

  private fun git(dir: Path, vararg args: String) {
    val p = ProcessBuilder(listOf("git", "-C", dir.toString()) + args).redirectErrorStream(true).start()
    p.inputStream.readAllBytes()
    assertEquals(0, p.waitFor())
  }

  fun testAFailingGitIsReportedNotThrown() {
    val missing = Files.createTempDirectory("folder-no-repo").resolve("nothing")
    assertNotNull(removeWorktree(missing.resolve(".git").toString(), missing.toString()))
    pruneWorktrees(missing.resolve(".git").toString())
  }

  fun testRemovesACleanWorktreeAndKeepsOneWithChanges() {
    val root = Files.createTempDirectory("folder-deletion").toRealPath()
    val main = Files.createDirectory(root.resolve("main"))
    git(main, "init", "-q")
    git(main, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "--allow-empty", "-m", "first")
    val clean = root.resolve("clean")
    val dirty = root.resolve("dirty")
    git(main, "worktree", "add", "-q", "-b", "clean", clean.toString())
    git(main, "worktree", "add", "-q", "-b", "dirty", dirty.toString())
    Files.writeString(dirty.resolve("new.txt"), "x")
    val gitDir = main.resolve(".git").toString()

    assertNull(removeWorktree(gitDir, clean.toString()))
    assertFalse(Files.exists(clean))
    assertNotNull(removeWorktree(gitDir, dirty.toString()))
    assertTrue(Files.exists(dirty.resolve("new.txt")))
  }
}
