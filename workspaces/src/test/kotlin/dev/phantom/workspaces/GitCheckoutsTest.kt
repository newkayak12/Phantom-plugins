// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.workspaces

import dev.phantom.workspaces.core.GitCheckouts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class GitCheckoutsTest {
  @get:Rule
  val temp = TemporaryFolder()

  private fun write(path: Path, text: String): Path {
    Files.createDirectories(path.parent)
    Files.writeString(path, text)
    return path
  }

  // what git worktree add writes: <worktree>/.git names the git dir, whose commondir points back to <repo>/.git
  private fun worktree(repo: Path, name: String, head: String, commondir: Boolean = true): Path {
    val gitDir = repo.resolve(".git/worktrees/$name")
    write(gitDir.resolve("HEAD"), head)
    if (commondir) write(gitDir.resolve("commondir"), "../..\n")
    val dir = temp.root.toPath().resolve("worktrees/$name")
    write(dir.resolve(".git"), "gitdir: $gitDir\n")
    return dir
  }

  private fun repository(head: String = "ref: refs/heads/main\n"): Path {
    val repo = temp.root.toPath().resolve("repo")
    write(repo.resolve(".git/HEAD"), head)
    return repo
  }

  @Test
  fun `a main checkout is its own repository, on its branch`() {
    val repo = repository()
    val checkout = GitCheckouts.read(repo)!!
    assertTrue(checkout.isMain)
    assertEquals("main", checkout.branch)
    assertEquals(repo.resolve(".git").toRealPath().toString(), checkout.commonDir)
    assertEquals(repo.resolve(".git/HEAD").toString(), checkout.head)
  }

  @Test
  fun `a worktree shares the repository of its main checkout and has its own branch and HEAD`() {
    val repo = repository()
    val tree = worktree(repo, "feature", "ref: refs/heads/feature/login\n")
    val main = GitCheckouts.read(repo)!!
    val checkout = GitCheckouts.read(tree)!!
    assertFalse(checkout.isMain)
    assertEquals(main.commonDir, checkout.commonDir)
    assertEquals("feature/login", checkout.branch)
    assertEquals(repo.resolve(".git/worktrees/feature/HEAD").toString(), checkout.head)
  }

  @Test
  fun `a worktree without a commondir file belongs to the repository above its worktrees folder`() {
    val repo = repository()
    val tree = worktree(repo, "fix", "ref: refs/heads/fix\n", commondir = false)
    assertEquals(GitCheckouts.read(repo)!!.commonDir, GitCheckouts.read(tree)!!.commonDir)
  }

  @Test
  fun `a relative gitdir is resolved against the project folder`() {
    val repo = repository()
    val gitDir = repo.resolve(".git/worktrees/rel")
    write(gitDir.resolve("HEAD"), "ref: refs/heads/rel\n")
    write(gitDir.resolve("commondir"), "../..\n")
    val tree = repo.resolve("nested/rel")
    write(tree.resolve(".git"), "gitdir: ../../.git/worktrees/rel\n")
    val checkout = GitCheckouts.read(tree)!!
    assertEquals("rel", checkout.branch)
    assertEquals(GitCheckouts.read(repo)!!.commonDir, checkout.commonDir)
  }

  @Test
  fun `a detached HEAD is named by its short hash`() {
    assertEquals("3f2c1ab", GitCheckouts.branch("3f2c1ab9d0e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8\n"))
    assertEquals("main", GitCheckouts.branch("ref: refs/heads/main"))
    assertNull(GitCheckouts.branch("garbage"))
  }

  @Test
  fun `a folder without git, or with an unreadable git file, is no checkout`() {
    val plain = temp.newFolder("plain").toPath()
    assertNull(GitCheckouts.read(plain))
    write(plain.resolve(".git"), "not a gitdir line\n")
    assertNull(GitCheckouts.read(plain))
  }
}
