// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.ai.sidebar.GitProjectRepos
import java.nio.file.Files

/** The project's repositories: none without a .git at the base path, the base path with one (directory or worktree file). */
class ProjectReposInIdeTest : BasePlatformTestCase() {
  fun testNoRepositoryGivesNoRoots() {
    val dir = Files.createTempDirectory("phantom-ai-repos")
    try {
      assertTrue(GitProjectRepos(project) { dir.toString() }.roots().isEmpty())
      assertTrue(GitProjectRepos(project) { null }.roots().isEmpty())
    } finally {
      dir.toFile().deleteRecursively()
    }
  }

  fun testBasePathWithGitDirectoryOrFileIsTheRoot() {
    val dir = Files.createTempDirectory("phantom-ai-repos")
    val linked = Files.createTempDirectory("phantom-ai-repos")
    try {
      Files.createDirectory(dir.resolve(".git"))
      Files.writeString(linked.resolve(".git"), "gitdir: /elsewhere")
      assertEquals(listOf(dir.toString()), GitProjectRepos(project) { dir.toString() }.roots())
      assertEquals(listOf(linked.toString()), GitProjectRepos(project) { linked.toString() }.roots())
    } finally {
      dir.toFile().deleteRecursively()
      linked.toFile().deleteRecursively()
    }
  }
}
