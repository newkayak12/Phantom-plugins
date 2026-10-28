// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.ai.sidebar.GitWorktreeSource
import dev.phantom.ai.sidebar.Worktree
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** The real source on a real git repo that is NOT an open project, with a linked worktree. */
class WorktreeSourceInIdeTest : BasePlatformTestCase() {
  private fun git(dir: File, vararg args: String) {
    val p = ProcessBuilder(listOf("git", "-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false") + args)
      .directory(dir).redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText()
    assertTrue("git ${args.toList()} failed: $out", p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0)
  }

  fun testListsMainAndLinkedWorktreeOfAClosedRepo() {
    val base = Files.createTempDirectory("wtsrc").toRealPath().toFile()
    try {
      val repo = File(base, "repo").also { it.mkdirs() }
      git(repo, "init", "-b", "main")
      git(repo, "commit", "--allow-empty", "-m", "init")
      val linked = File(base, "repo-feat")
      git(repo, "worktree", "add", "-b", "feat/x", linked.path)

      var listed: List<Worktree> = emptyList()
      ApplicationManager.getApplication().executeOnPooledThread {
        listed = GitWorktreeSource(project).list(repo.path)
      }.get(60, TimeUnit.SECONDS)

      assertEquals(listOf(repo.path to "main", linked.path to "feat/x"), listed.map { it.path to it.branch })
      assertEquals(listOf(true, false), listed.map { it.isMain })
      assertTrue(com.intellij.openapi.project.ProjectManager.getInstance().openProjects.none { it.basePath == repo.path })
    } finally {
      base.deleteRecursively()
    }
  }

  fun testNotARepoListsNothing() {
    val dir = Files.createTempDirectory("notrepo").toFile()
    try {
      var listed: List<Worktree>? = null
      ApplicationManager.getApplication().executeOnPooledThread { listed = GitWorktreeSource(project).list(dir.path) }.get(60, TimeUnit.SECONDS)
      assertEquals(emptyList<Worktree>(), listed)
    } finally {
      dir.deleteRecursively()
    }
  }
}
