// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.sidebar

import com.intellij.openapi.project.Project
import dev.phantom.ai.core.bestEffort
import git4idea.repo.GitRepositoryManager
import java.nio.file.Files
import java.nio.file.Path

/** The git repository roots of the current project. Fakeable; the real one is [GitProjectRepos]. Blocking: call off the EDT. */
fun interface ProjectRepos {
  fun roots(): List<String>
}

/**
 * The roots [GitRepositoryManager] knows for the project, sorted; when it knows none, the project's base path if that holds
 * a `.git` entry (a directory, or the file of a linked worktree); otherwise nothing.
 */
class GitProjectRepos(
  private val project: Project,
  private val basePath: () -> String? = { project.basePath },
) : ProjectRepos {
  override fun roots(): List<String> {
    val known = bestEffort {
      GitRepositoryManager.getInstance(project).repositories.map { it.root.path }.sorted()
    }.getOrDefault(emptyList())
    if (known.isNotEmpty()) return known
    val base = basePath() ?: return emptyList()
    return if (Files.exists(Path.of(base, ".git"))) listOf(base) else emptyList()
  }
}
