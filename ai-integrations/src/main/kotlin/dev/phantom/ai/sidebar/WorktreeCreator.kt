// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.sidebar

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import dev.phantom.ai.core.bestEffort
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Output of one git command: whether it succeeded, its stdout lines and its error text. */
data class GitOut(val success: Boolean, val lines: List<String>, val error: String = "")

/** Runs one git command in a repository path. Fakeable; the real one is [Git4IdeaRun]. Blocking: off the EDT. */
fun interface GitRun {
  fun run(repo: String, command: GitCommand, args: List<String>): GitOut
}

/** git4idea only, as [GitWorktreeSource]: never `git` directly. */
class Git4IdeaRun(private val project: Project = ProjectManager.getInstance().defaultProject) : GitRun {
  override fun run(repo: String, command: GitCommand, args: List<String>): GitOut = bestEffort {
    val handler = GitLineHandler(project, Path.of(repo), command)
    handler.addParameters(args)
    handler.setSilent(true)
    val result = Git.getInstance().runCommand(handler)
    GitOut(result.success(), result.output, result.errorOutputAsJoinedString)
  }.getOrElse { GitOut(false, emptyList(), it.message ?: it.toString()) }
}

/** Names as Orca makes them (`worktree-logic.ts`, `worktree-branch-name.ts`). Pure. */
object WorktreeNames {
  /** Runs of characters other than letters, digits, `.`, `_`, `-` become `-`; no `..`, no leading or trailing `.`/`-`. */
  fun sanitize(name: String): String =
    name.trim().replace(Regex("[^\\p{L}\\p{N}._-]+"), "-").replace(Regex("\\.{2,}"), ".").trim('.', '-')

  /** `<prefix>/<name>`, or just the name without a prefix. */
  fun branch(prefix: String?, name: String): String = prefix?.let(::sanitize)?.takeIf { it.isNotEmpty() }?.let { "$it/$name" } ?: name

  /** [name], else `name-2`, `name-3`, … : the first whose directory and branch are both free. */
  fun free(name: String, taken: (String) -> Boolean): String =
    generateSequence(1) { it + 1 }.map { if (it == 1) name else "$name-$it" }.first { !taken(it) }
}

/** A created worktree: its directory, its new branch, the ref it starts from. */
data class CreatedWorktree(val path: String, val branch: String, val base: String)

/**
 * A worktree per task, as Orca's "New workspace": under `~/orca/workspaces/<repo>/<name>` on a new branch
 * `<git user>/<name>` from the remote's default branch (fetched first), with the ignored files `.worktreeinclude` lists
 * copied over. Removal leaves an unmerged branch alone. Blocking: off the EDT.
 */
class WorktreeCreator(
  private val git: GitRun = Git4IdeaRun(),
  private val root: String = System.getProperty("phantom.ai.workspacesDir") ?: (System.getProperty("user.home") + "/orca/workspaces"),
) {
  /** The ref a new worktree starts from by default: `origin/HEAD`'s branch (e.g. origin/main), else the current branch. */
  fun defaultBase(repo: String): String? =
    line(repo, GitCommand.REV_PARSE, "--abbrev-ref", "origin/HEAD")?.takeIf { it != "origin/HEAD" }
      ?: line(repo, GitCommand.REV_PARSE, "--abbrev-ref", "HEAD")?.takeIf { it != "HEAD" }

  fun create(repo: String, name: String, base: String?): Result<CreatedWorktree> {
    val clean = WorktreeNames.sanitize(name)
    if (clean.isEmpty()) return Result.failure(IllegalArgumentException("Empty worktree name"))
    // best effort: offline, or no origin, still creates from what is there
    git.run(repo, GitCommand.FETCH, listOf("--quiet", "origin"))
    val from = base?.trim()?.takeIf { it.isNotEmpty() } ?: defaultBase(repo)
      ?: return Result.failure(IllegalStateException("No branch to start from"))
    val prefix = line(repo, GitCommand.CONFIG, "--get", "user.name")
    val branches = git.run(repo, GitCommand.BRANCH, listOf("--list", "--format=%(refname:short)")).lines.map { it.trim() }.toSet()
    val dir = File(root, File(repo).name)
    val unique = WorktreeNames.free(clean) { File(dir, it).exists() || WorktreeNames.branch(prefix, it) in branches }
    val branch = WorktreeNames.branch(prefix, unique)
    val path = File(dir, unique).path
    dir.mkdirs()
    val add = git.run(repo, GitCommand.WORKTREE, listOf("add", "--no-track", "-b", branch, path, from))
    if (!add.success) return Result.failure(IllegalStateException(add.error.ifBlank { "git worktree add failed" }))
    git.run(repo, GitCommand.CONFIG, listOf("--local", "branch.$branch.base", from))
    copyIncluded(repo, path)
    SharedWorktrees.invalidate(repo)
    return Result.success(CreatedWorktree(path, branch, from))
  }

  /**
   * `git worktree remove [--force]`, then `git branch -d` of [branch]: a branch not merged yet stays. Returns the error
   * text when git refuses to remove the worktree (changed files without [force]); null when it is gone.
   */
  fun remove(repo: String, path: String, branch: String?, force: Boolean): String? {
    val args = listOf("remove") + (if (force) listOf("--force") else emptyList()) + path
    val out = git.run(repo, GitCommand.WORKTREE, args)
    if (!out.success) return out.error.ifBlank { "git worktree remove failed" }
    SharedWorktrees.invalidate(repo)
    branch?.let { git.run(repo, GitCommand.BRANCH, listOf("-d", it)) }
    return null
  }

  /** `.worktreeinclude` in the repository: one literal path per line (no globs), copied when missing in the worktree. */
  private fun copyIncluded(repo: String, worktree: String) {
    val list = File(repo, ".worktreeinclude").takeIf { it.isFile } ?: return
    list.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") && !it.contains("..") }.forEach { rel ->
      bestEffort {
        val from = File(repo, rel)
        val to = File(worktree, rel)
        if (!from.exists() || to.exists()) return@bestEffort
        if (from.isDirectory) from.copyRecursively(to) else {
          to.parentFile.mkdirs()
          Files.copy(from.toPath(), to.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
        }
      }
    }
  }

  private fun line(repo: String, command: GitCommand, vararg args: String): String? =
    git.run(repo, command, args.toList()).takeIf { it.success }?.lines?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
}
