// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.workspaces

import dev.phantom.workspaces.core.GitCheckouts
import dev.phantom.workspaces.core.WorktreeRename
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

/** `git worktree move` with real git: the folder and git's worktree list follow the new name. */
class WorktreeRenameTest {
  @get:Rule
  val temp = TemporaryFolder()

  private fun git(dir: Path, vararg args: String) {
    val process = ProcessBuilder(listOf("git", "-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false") + args)
      .directory(dir.toFile()).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    assertEquals(output, 0, process.waitFor())
  }

  // a repository with one commit and a linked worktree next to it, on branch feature
  private fun repository(): Pair<Path, Path> {
    val root = temp.root.toPath().toRealPath()
    val repo = Files.createDirectories(root.resolve("repo"))
    git(repo, "init", "-q", "-b", "main")
    Files.writeString(repo.resolve("a.txt"), "a")
    git(repo, "add", ".")
    git(repo, "commit", "-q", "-m", "init")
    git(repo, "worktree", "add", "-q", "-b", "feature", root.resolve("feature").toString())
    return repo to root.resolve("feature")
  }

  @Test
  fun `moving a worktree renames its folder and git lists the new one`() {
    val (repo, worktree) = repository()
    val target = WorktreeRename.target(worktree, " login ")!!
    assertEquals(worktree.resolveSibling("login"), target)
    assertNull(WorktreeRename.move(worktree, target))
    assertFalse(Files.exists(worktree))
    assertTrue(Files.isRegularFile(target.resolve("a.txt")))
    val listed = GitCheckouts.worktrees(repo.resolve(".git"))
    assertEquals(listOf(target.toString()), listed.map { Path.of(it.root).toRealPath().toString() })
    assertEquals("feature", listed.single().branch)
    assertEquals("feature", GitCheckouts.read(target)!!.branch)
  }

  @Test
  fun `the main checkout and a taken name are refused and nothing moves`() {
    val (repo, worktree) = repository()
    assertNotNull(WorktreeRename.move(repo, repo.resolveSibling("other")))
    assertTrue(Files.isDirectory(repo))
    Files.createDirectories(worktree.resolveSibling("taken"))
    assertNotNull(WorktreeRename.move(worktree, worktree.resolveSibling("taken")))
    assertTrue(Files.isRegularFile(worktree.resolve("a.txt")))
  }

  @Test
  fun `a name is one plain folder name`() {
    val path = Path.of("/r/feature")
    assertNull(WorktreeRename.target(path, " "))
    assertNull(WorktreeRename.target(path, "a/b"))
    assertNull(WorktreeRename.target(path, ".."))
    assertEquals(Path.of("/r/b"), WorktreeRename.target(path, "b"))
  }
}
