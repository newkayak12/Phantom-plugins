// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.workspaces.core

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * The git checkout a project folder is: [commonDir] is the repository's `.git` directory, the same for its main checkout
 * and all of its worktrees; [head] is the HEAD file of this checkout; [branch] is the branch it is on (a short commit
 * hash when detached).
 */
data class GitCheckout(val commonDir: String, val head: String, val branch: String?, val isMain: Boolean)

/** Reads a project's git checkout from its `.git` entry without running git; worktrees have a `.git` file. */
object GitCheckouts {
  private const val GITDIR = "gitdir:"
  private const val REF = "ref:"
  private const val SHORT_HASH = 7

  /** The checkout at [root], or null when [root] has no `.git` or it cannot be read. Reads files: not on the EDT. */
  fun read(root: Path): GitCheckout? = try {
    val dotGit = root.resolve(".git")
    when {
      Files.isDirectory(dotGit) -> checkout(dotGit, dotGit)
      Files.isRegularFile(dotGit) -> {
        val line = Files.readAllLines(dotGit).firstOrNull { it.startsWith(GITDIR) }
        line?.let { checkout(root.resolve(it.removePrefix(GITDIR).trim()).normalize(), null) }
      }
      else -> null
    }
  }
  catch (_: IOException) {
    null
  }

  // A worktree's git dir is <repo>/.git/worktrees/<name> and its commondir file points back to <repo>/.git. A git dir
  // without one (a submodule's) is a repository of its own.
  private fun checkout(gitDir: Path, commonDir: Path?): GitCheckout {
    val listed = gitDir.resolve("commondir").takeIf(Files::isRegularFile)?.let { gitDir.resolve(Files.readString(it).trim()).normalize() }
    val worktrees = gitDir.parent?.takeIf { it.fileName?.toString() == "worktrees" }?.parent
    val common = real(commonDir ?: listed ?: worktrees ?: gitDir)
    val head = gitDir.resolve("HEAD")
    val branch = if (Files.isRegularFile(head)) branch(Files.readString(head)) else null
    return GitCheckout(common.toString(), head.toString(), branch, isMain = common == real(gitDir))
  }

  // the same repository reached through a symbolic link (/tmp and /private/tmp on macOS) is still the same
  private fun real(path: Path): Path = try {
    path.toRealPath()
  }
  catch (_: IOException) {
    path
  }

  /** The branch a HEAD file's [text] names: `ref: refs/heads/main` is main, a detached commit its short hash. */
  fun branch(text: String): String? {
    val head = text.trim()
    if (head.startsWith(REF)) return head.removePrefix(REF).trim().removePrefix("refs/heads/").ifEmpty { null }
    return if (head.length >= SHORT_HASH && head.all { it in '0'..'9' || it in 'a'..'f' }) head.take(SHORT_HASH) else null
  }
}
