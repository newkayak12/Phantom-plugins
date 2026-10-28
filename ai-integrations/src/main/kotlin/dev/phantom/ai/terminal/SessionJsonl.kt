// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.terminal

import dev.phantom.ai.core.bestEffort
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.text.Normalizer
import java.util.UUID

/** Claude's own session files: `<configDir>/projects/<encoded cwd>/<uuid>.jsonl`. Read only. */
object SessionJsonl {
  private val NON_ALNUM = Regex("[^a-zA-Z0-9]")

  /** Every non-alphanumeric character becomes one '-'; a trailing slash is dropped (not for the root). */
  fun encode(path: String): String {
    val unified = path.replace('\\', '/')
    val trimmed = if (unified == "/" || Regex("^[A-Za-z]:/$").matches(unified)) unified else unified.trimEnd('/')
    return NON_ALNUM.replace(trimmed, "-")
  }

  /** The raw encoding and, when it differs, the NFC one (macOS may hand out NFD names). */
  fun candidates(path: String): List<String> =
    listOf(encode(path), encode(Normalizer.normalize(path, Normalizer.Form.NFC))).distinct()

  /**
   * A session file: [createdMs] is the creation time, or the modification time where the file system has none (then the
   * resume filter also requires [modifiedMs] to be recent, so an older conversation's file is not taken for a new one).
   */
  data class JsonlFile(val id: String, val createdMs: Long, val modifiedMs: Long)

  /** Session files (file names that are UUIDs) of [worktree], newest modification first. */
  fun sessions(configDir: Path, worktree: String): List<JsonlFile> =
    candidates(worktree).asSequence()
      .map { configDir.resolve("projects").resolve(it) }
      .filter { Files.isDirectory(it) }
      .flatMap { dir -> Files.list(dir).use { it.toList() }.asSequence() }
      .filter { it.fileName.toString().endsWith(".jsonl") && Files.isRegularFile(it) }
      .mapNotNull { file ->
        val id = file.fileName.toString().removeSuffix(".jsonl")
        if (bestEffort { UUID.fromString(id) }.isFailure) return@mapNotNull null
        val attrs = Files.readAttributes(file, BasicFileAttributes::class.java)
        val modified = attrs.lastModifiedTime().toMillis()
        val created = attrs.creationTime().toMillis().takeIf { it > 0 } ?: modified
        JsonlFile(id, created, modified)
      }
      .sortedWith(compareByDescending<JsonlFile> { it.modifiedMs }.thenBy { it.id })
      .distinctBy { it.id }
      .toList()

  /** Session ids of [worktree], newest file first. */
  fun sessionIds(configDir: Path, worktree: String): List<String> = sessions(configDir, worktree).map { it.id }
}
