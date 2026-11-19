// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.sync.core

import com.intellij.openapi.diagnostic.logger
import com.intellij.settingsSync.core.InvalidVersionIdException
import java.io.IOException
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists

private val LOG = logger<GitSettingsRepository>()

/**
 * A settings repository the user owns (GitHub or any git remote), reached with the git command line so the user's
 * own SSH keys and credential helper are used. Phantom keeps a working copy in [workDir]; the remote branch is the
 * only source of truth, and the working copy is reset to it before every write.
 *
 * A file's version is the hash of the last commit on the remote branch that touched it. A write that expects a
 * different version, or whose push is rejected because the branch moved, fails with [InvalidVersionIdException]
 * and the sync engine retries after the next update.
 */
class GitSettingsRepository(val url: String, private val workDir: Path, private val branch: String = DEFAULT_BRANCH) {
  private val remoteRef = "refs/remotes/origin/$branch"

  @Synchronized
  @Throws(IOException::class)
  fun latestVersion(path: String): String? {
    if (!fetch()) return null
    // the commit that deleted a file also touches it, so first check that the file is in the branch
    if (run(listOf(gitExecutable(), "cat-file", "-e", "$remoteRef:$path"), workDir, failOnError = false).exitCode != 0) return null
    return git("log", "-1", "--format=%H", remoteRef, "--", path).text().ifEmpty { null }
  }

  /** The file's content and version on the remote branch, or nulls when it is not there. */
  @Synchronized
  @Throws(IOException::class)
  fun read(path: String): Pair<ByteArray?, String?> {
    val version = latestVersion(path) ?: return null to null
    return git("show", "$remoteRef:$path").stdout to version
  }

  /** Commits [content] at [path] and pushes it; returns the new version. */
  @Synchronized
  @Throws(IOException::class, InvalidVersionIdException::class)
  fun write(path: String, expectedVersion: String?, content: ByteArray): String {
    val current = latestVersion(path)
    if (current != expectedVersion) throw InvalidVersionIdException("$path is at $current on the remote, expected $expectedVersion")
    resetToRemote()
    val file = workDir.resolve(path)
    file.parent.createDirectories()
    Files.write(file, content)
    git("add", "--", path)
    commitAndPush("Update $path from ${hostName()}")
    return git("rev-parse", "HEAD").text()
  }

  @Synchronized
  @Throws(IOException::class)
  fun delete(path: String) {
    if (latestVersion(path) == null) return
    resetToRemote()
    git("rm", "-q", "--", path)
    commitAndPush("Delete $path from ${hostName()}")
  }

  /** Checks that the remote can be reached with the user's credentials. */
  @Throws(IOException::class)
  fun checkAccess() {
    run(listOf(gitExecutable(), "ls-remote", "--heads", url), workDir = null)
  }

  /** Fetches the branch; false when the remote has no such branch yet (a new, empty repository). */
  private fun fetch(): Boolean {
    ensureWorkingCopy()
    val heads = git("ls-remote", "--heads", "origin", branch).text()
    if (heads.isEmpty()) return false
    git("fetch", "--quiet", "--no-tags", "origin", "+refs/heads/$branch:$remoteRef")
    return true
  }

  private fun resetToRemote() {
    if (fetch()) {
      git("checkout", "--quiet", "-f", "-B", branch, remoteRef)
      git("clean", "-q", "-fdx")
    }
    else {
      // nothing pushed yet: start the branch from scratch, dropping any commit a failed push left behind
      git("symbolic-ref", "HEAD", "refs/heads/$branch")
      git("update-ref", "-d", "refs/heads/$branch")
      git("rm", "-q", "-rf", "--cached", "--ignore-unmatch", ".")
      git("clean", "-q", "-fdx")
    }
  }

  private fun commitAndPush(message: String) {
    git("-c", "user.name=${identity("user.name", "Phantom")}", "-c", "user.email=${identity("user.email", "phantom@localhost")}",
        "commit", "-q", "-m", message)
    val push = run(listOf(gitExecutable(), "push", "--porcelain", "origin", "$branch:refs/heads/$branch"), workDir, failOnError = false)
    if (push.exitCode != 0) {
      if ("rejected" in push.stderrText || "[rejected]" in push.text() || "fetch first" in push.stderrText) {
        throw InvalidVersionIdException("the remote branch moved: ${push.stderrText.lines().firstOrNull()}")
      }
      throw IOException("git push failed: ${push.stderrText.trim()}")
    }
  }

  /** The user's git identity when they have one, so commits in their repository carry their name. */
  private fun identity(key: String, fallback: String): String =
    run(listOf(gitExecutable(), "config", "--get", key), workDir, failOnError = false).takeIf { it.exitCode == 0 }?.text()?.ifEmpty { null }
    ?: fallback

  private fun ensureWorkingCopy() {
    if (workDir.resolve(".git").exists()) {
      if (git("remote", "get-url", "origin").text() != url) git("remote", "set-url", "origin", url)
      return
    }
    workDir.createDirectories()
    git("init", "-q")
    git("remote", "add", "origin", url)
  }

  private fun git(vararg args: String): Result = run(listOf(gitExecutable(), *args), workDir)

  class Result(val exitCode: Int, val stdout: ByteArray, val stderrText: String) {
    fun text(): String = stdout.toString(Charsets.UTF_8).trim()
  }

  companion object {
    const val DEFAULT_BRANCH: String = "main"
    private const val TIMEOUT_SECONDS = 120L

    fun gitExecutable(): String = listOf("/usr/bin/git", "/opt/homebrew/bin/git", "/usr/local/bin/git").firstOrNull { Path.of(it).exists() } ?: "git"

    private fun hostName(): String = runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("Phantom")

    /** Runs a command without a terminal, so git fails instead of prompting for a password. */
    @Throws(IOException::class)
    fun run(command: List<String>, workDir: Path?, failOnError: Boolean = true): Result {
      val builder = ProcessBuilder(command)
      workDir?.let { builder.directory(it.toFile()) }
      builder.environment()["GIT_TERMINAL_PROMPT"] = "0"
      builder.environment()["GIT_SSH_COMMAND"] = builder.environment()["GIT_SSH_COMMAND"] ?: "ssh -o BatchMode=yes"
      val process = builder.start()
      process.outputStream.close()
      val stderr = CompletableFuture.supplyAsync { process.errorStream.readBytes() }
      val stdout = process.inputStream.readBytes()
      if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        throw IOException("${command.drop(1).take(2).joinToString(" ")} timed out")
      }
      val result = Result(process.exitValue(), stdout, stderr.get().toString(Charsets.UTF_8))
      if (failOnError && result.exitCode != 0) {
        LOG.info("${command.joinToString(" ")} failed: ${result.stderrText.trim()}")
        throw IOException(result.stderrText.trim().ifEmpty { "git ${command.getOrNull(1)} exited with ${result.exitCode}" })
      }
      return result
    }

    internal fun deleteQuietly(path: Path) {
      runCatching { path.toFile().deleteRecursively(); path.deleteIfExists() }
    }
  }
}
