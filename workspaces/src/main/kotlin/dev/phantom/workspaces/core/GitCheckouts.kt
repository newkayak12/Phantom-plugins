// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.workspaces.core

import com.intellij.util.SystemProperties
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * The git checkout a project folder is: [commonDir] is the repository's `.git` directory, the same for its main checkout
 * and all of its worktrees; [head] is the HEAD file of this checkout; [branch] is the branch it is on (a short commit
 * hash when detached).
 */
data class GitCheckout(val commonDir: String, val head: String, val branch: String?, val isMain: Boolean)

/** A worktree a repository lists: the folder it is checked out in and its [branch]. */
data class GitWorktree(val root: String, val branch: String?)

/** Reads a project's git checkout from its `.git` entry without running git; worktrees have a `.git` file. */
object GitCheckouts {
  private const val GITDIR = "gitdir:"
  private const val REF = "ref:"
  private const val SHORT_HASH = 7
  private const val WORKTREES = "worktrees"

  /**
   * The checkout [root] is in: its own `.git`, else the nearest one of a folder above it (a project opened in a
   * subfolder of a repository). The search stops below [home], so a repository there (dotfiles) does not claim every
   * project under it, unless [root] is [home] itself. Null when none is found or it cannot be read. Reads files: not on
   * the EDT.
   */
  fun read(root: Path, home: Path = Path.of(SystemProperties.getUserHome())): GitCheckout? = try {
    generateSequence(root.toAbsolutePath().normalize()) { dir -> dir.parent?.takeIf { it != home } }
      .firstOrNull { Files.exists(it.resolve(".git")) }
      ?.let(::readAt)
  }
  catch (_: IOException) {
    null
  }

  private fun readAt(root: Path): GitCheckout? {
    val dotGit = root.resolve(".git")
    return when {
      Files.isDirectory(dotGit) -> checkout(dotGit, dotGit)
      Files.isRegularFile(dotGit) -> {
        val line = Files.readAllLines(dotGit).firstOrNull { it.startsWith(GITDIR) }
        line?.let { checkout(root.resolve(it.removePrefix(GITDIR).trim()).normalize(), null) }
      }
      else -> null
    }
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

  /**
   * The worktrees of the repository whose `.git` directory is [commonDir], whether or not a project was ever opened in
   * them (ones another tool created, say): git keeps a folder per worktree in `.git/worktrees`, whose `gitdir` file names
   * the worktree's `.git` file (relative to that folder when git writes relative paths). A worktree whose folder is gone
   * (prunable, in git's words) is left out. Reads files without running git: not on the EDT.
   */
  fun worktrees(commonDir: Path): List<GitWorktree> {
    val folders = try {
      Files.list(commonDir.resolve(WORKTREES)).use { s -> s.filter(Files::isDirectory).sorted().toList() }
    }
    catch (_: IOException) {
      return emptyList()
    }
    return folders.mapNotNull { folder ->
      try {
        val gitdir = folder.resolve("gitdir").takeIf(Files::isRegularFile) ?: return@mapNotNull null
        val root = folder.resolve(Files.readString(gitdir).trim()).normalize().parent?.takeIf(Files::isDirectory)
        val head = folder.resolve("HEAD")
        root?.let { GitWorktree(it.toString(), if (Files.isRegularFile(head)) branch(Files.readString(head)) else null) }
      }
      catch (_: IOException) {
        null
      }
    }
  }

  /** The main checkout of the repository whose `.git` directory is [commonDir]; null for a bare repository. */
  fun mainRoot(commonDir: Path): Path? = commonDir.takeIf { it.fileName?.toString() == ".git" }?.parent

  /** The branch a HEAD file's [text] names: `ref: refs/heads/main` is main, a detached commit its short hash. */
  fun branch(text: String): String? {
    val head = text.trim()
    if (head.startsWith(REF)) return head.removePrefix(REF).trim().removePrefix("refs/heads/").ifEmpty { null }
    return if (head.length >= SHORT_HASH && head.all { it in '0'..'9' || it in 'a'..'f' }) head.take(SHORT_HASH) else null
  }
}
