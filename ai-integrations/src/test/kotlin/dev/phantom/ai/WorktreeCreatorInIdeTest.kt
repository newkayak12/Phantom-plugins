// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.ai.sidebar.Git4IdeaRun
import dev.phantom.ai.sidebar.GitWorktreeSource
import dev.phantom.ai.sidebar.WorktreeCreator
import dev.phantom.ai.sidebar.WorktreeNames
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** Orca's new-workspace flow on a real repository cloned from a real origin, through git4idea. */
class WorktreeCreatorInIdeTest : BasePlatformTestCase() {
  private lateinit var base: File

  override fun setUp() {
    super.setUp()
    base = Files.createTempDirectory("wtnew").toRealPath().toFile()
  }

  override fun tearDown() {
    try {
      base.deleteRecursively()
    } finally {
      super.tearDown()
    }
  }

  private fun git(dir: File, vararg args: String): String {
    val p = ProcessBuilder(listOf("git", "-c", "user.email=t@t", "-c", "commit.gpgsign=false") + args)
      .directory(dir).redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText()
    assertTrue("git ${args.toList()} failed: $out", p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0)
    return out
  }

  private fun <T> offEdt(run: () -> T): T = ApplicationManager.getApplication().executeOnPooledThread<T> { run() }.get(60, TimeUnit.SECONDS)

  /** origin (bare, branch main) and a clone of it as the repository, user "Tester X", a `.env` listed in `.worktreeinclude`. */
  private fun repo(): File {
    val seed = File(base, "seed").also { it.mkdirs() }
    git(seed, "init", "-b", "main")
    File(seed, ".gitignore").writeText(".env\n")
    File(seed, ".worktreeinclude").writeText(".env\n")
    git(seed, "add", ".")
    git(seed, "-c", "user.name=t", "commit", "-m", "init")
    git(base, "clone", "--bare", seed.path, File(base, "origin.git").path)
    git(base, "clone", File(base, "origin.git").path, File(base, "app").path)
    val repo = File(base, "app")
    git(repo, "config", "user.name", "Tester X")
    File(repo, ".env").writeText("SECRET=1\n")
    return repo
  }

  fun testCreatesBranchAndWorktreeFromTheDefaultBranchAndCopiesIncludedFiles() {
    val repo = repo()
    val creator = WorktreeCreator(Git4IdeaRun(project), File(base, "workspaces").path)
    assertEquals("origin/main", offEdt { creator.defaultBase(repo.path) })

    val first = offEdt { creator.create(repo.path, "Fix login!", null) }.getOrThrow()
    assertEquals(File(base, "workspaces/app/Fix-login").path, first.path)
    assertEquals("Tester-X/Fix-login", first.branch)
    assertEquals("origin/main", first.base)
    assertEquals("SECRET=1\n", File(first.path, ".env").readText())
    assertEquals("origin/main", git(repo, "config", "--get", "branch.Tester-X/Fix-login.base").trim())

    // the same name again: the next free one
    val second = offEdt { creator.create(repo.path, "Fix login", null) }.getOrThrow()
    assertEquals("Tester-X/Fix-login-2", second.branch)
    val listed = offEdt { GitWorktreeSource(project).list(repo.path) }.map { it.branch }
    assertEquals(listOf("main", "Tester-X/Fix-login", "Tester-X/Fix-login-2"), listed)
  }

  fun testRemoveRefusesChangedFilesUntilForcedAndDeletesAMergedBranch() {
    val repo = repo()
    val creator = WorktreeCreator(Git4IdeaRun(project), File(base, "workspaces").path)
    val w = offEdt { creator.create(repo.path, "task", null) }.getOrThrow()
    File(w.path, "new.txt").writeText("work\n")

    assertNotNull(offEdt { creator.remove(repo.path, w.path, w.branch, force = false) })
    assertTrue(File(w.path).exists())

    assertNull(offEdt { creator.remove(repo.path, w.path, w.branch, force = true) })
    assertFalse(File(w.path).exists())
    assertEquals("", git(repo, "branch", "--list", w.branch).trim())
  }

  fun testRemoveKeepsAnUnmergedBranch() {
    val repo = repo()
    val creator = WorktreeCreator(Git4IdeaRun(project), File(base, "workspaces").path)
    val w = offEdt { creator.create(repo.path, "keep", null) }.getOrThrow()
    git(File(w.path), "-c", "user.name=t", "commit", "--allow-empty", "-m", "work")

    assertNull(offEdt { creator.remove(repo.path, w.path, w.branch, force = false) })
    assertFalse(File(w.path).exists())
    assertTrue(git(repo, "branch", "--list", w.branch).contains("keep"))
  }

  fun testNames() {
    assertEquals("a-b.c_d", WorktreeNames.sanitize("  a b..c_d ! "))
    assertEquals("한글-이름", WorktreeNames.sanitize("한글 이름"))
    assertEquals("x", WorktreeNames.branch(null, "x"))
    assertEquals("x", WorktreeNames.branch("", "x"))
    assertEquals("t-3", WorktreeNames.free("t") { it == "t" || it == "t-2" })
  }
}
