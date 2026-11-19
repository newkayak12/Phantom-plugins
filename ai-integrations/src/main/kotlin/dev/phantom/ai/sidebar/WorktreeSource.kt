// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.sidebar

import dev.phantom.ai.core.bestEffort
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import java.nio.file.Path

/** One worktree of a repository: [branch] is null when detached; the first one listed is the main worktree. */
data class Worktree(val path: String, val branch: String?, val isMain: Boolean)

/** Lists the worktrees of a repository path. Fakeable; the real one is [GitWorktreeSource]. */
fun interface WorktreeSource {
  fun list(repoPath: String): List<Worktree>
}

/** Parses `git worktree list --porcelain`: blocks separated by a blank line, the first block is the main worktree. */
object WorktreePorcelain {
  fun parse(lines: List<String>): List<Worktree> {
    val out = ArrayList<Worktree>()
    var path: String? = null
    var branch: String? = null
    fun flush() {
      path?.let { out.add(Worktree(it, branch, isMain = out.isEmpty())) }
      path = null
      branch = null
    }
    for (raw in lines) {
      val line = raw.trimEnd()
      when {
        line.isEmpty() -> flush()
        line.startsWith("worktree ") -> {
          flush()
          path = line.removePrefix("worktree ")
        }
        line.startsWith("branch ") -> branch = line.removePrefix("branch ").removePrefix("refs/heads/")
      }
    }
    flush()
    return out
  }
}

/**
 * The real source, through git4idea only: [Git.runCommand] with a [GitLineHandler] for `worktree list --porcelain`
 * in the repository path itself. It does not use GitRepositoryManager, which only knows the repositories of open
 * projects; sidebar repositories usually are not open. Blocking: call off the EDT. Any failure lists nothing.
 */
class GitWorktreeSource(private val project: Project = ProjectManager.getInstance().defaultProject) : WorktreeSource {
  override fun list(repoPath: String): List<Worktree> = bestEffort {
    val handler = GitLineHandler(project, Path.of(repoPath), GitCommand.WORKTREE)
    handler.addParameters("list", "--porcelain")
    handler.setSilent(true)
    val result = Git.getInstance().runCommand(handler)
    if (result.success()) WorktreePorcelain.parse(result.output) else emptyList()
  }.getOrDefault(emptyList())
}

/**
 * [delegate] with each repository's list kept for [ttlMs]: every hook event (several a second while an agent works)
 * refreshes the tree, the board and the dashboard, and git then runs once per repository instead of once per view and
 * event. A worktree added outside Phantom shows up within [ttlMs]; Phantom's own create and remove [invalidate] at once.
 */
class CachedWorktreeSource(
  private val delegate: WorktreeSource,
  private val ttlMs: Long = 5_000,
  private val clock: () -> Long = System::currentTimeMillis,
) : WorktreeSource {
  private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<Worktree>>>()

  override fun list(repoPath: String): List<Worktree> {
    val now = clock()
    cache[repoPath]?.takeIf { now - it.first < ttlMs }?.let { return it.second }
    return delegate.list(repoPath).also { cache[repoPath] = now to it }
  }

  fun invalidate(repoPath: String) {
    cache.remove(repoPath)
  }

  fun invalidateAll() = cache.clear()
}

/** The views' shared worktree lists: one git run per repository however many views and hook events ask. */
object SharedWorktrees : WorktreeSource {
  private val cached = CachedWorktreeSource(GitWorktreeSource())

  override fun list(repoPath: String): List<Worktree> = cached.list(repoPath)

  fun invalidate(repoPath: String) = cached.invalidate(repoPath)

  fun invalidateAll() = cached.invalidateAll()
}
