// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.localsharedindex

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.vfs.VirtualFile
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections

/**
 * The git blob id of a file whose size and modification time still match its entry in the git index, read from the
 * index of the enclosing work tree, without reading the file. A fresh checkout or worktree has such an entry for every
 * tracked file, so scanning can find a file's donor without loading its content. Anything git itself would re-check
 * (racily clean entries, conflicts, split indexes) gives null, and the caller reads the content instead.
 */
internal object GitBlobs {
  internal class Entry(val blob: ByteArray, val size: Int, val mtimeMs: Long)

  private class Snapshot(val stamp: Long, val length: Long, val checkedAt: Long, val entries: Map<String, Entry>?)

  private class WorkTree(val prefix: String, val index: Path)

  private val NONE = WorkTree("", Path.of(""))

  // Bounded so a long session over many projects and worktrees does not keep every directory and parsed index alive;
  // a parsed index holds an entry per tracked file. Evicted entries are found or parsed again.
  private val workTreeOfDir = lru<String, WorkTree>(MAX_DIRS)
  private val snapshots = lru<Path, Snapshot>(MAX_INDEXES)

  internal val cachedDirs: Int get() = workTreeOfDir.size
  internal val cachedIndexes: Int get() = snapshots.size

  private fun <K, V> lru(max: Int): MutableMap<K, V> = Collections.synchronizedMap(object : LinkedHashMap<K, V>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > max
  })

  fun blobOf(file: VirtualFile): ByteArray? {
    if (!file.isInLocalFileSystem) return null
    val dir = file.parent ?: return null
    val workTree = workTreeOf(dir).takeIf { it !== NONE } ?: return null
    val path = file.path
    if (!path.startsWith(workTree.prefix)) return null
    val entry = snapshotOf(workTree.index)?.get(path.substring(workTree.prefix.length)) ?: return null
    if (entry.size != file.length.toInt() || entry.mtimeMs != file.timeStamp) return null
    return entry.blob
  }

  fun clear() {
    workTreeOfDir.clear()
    snapshots.clear()
  }

  private fun workTreeOf(dir: VirtualFile): WorkTree {
    workTreeOfDir[dir.path]?.let { return it }
    // The disk, not the VFS: a VFS directory whose children were loaded before `git init` or a clone finished lacks .git.
    val dotGit = Path.of(dir.path, ".git")
    val found = when {
      Files.isDirectory(dotGit) -> WorkTree(dir.path + "/", dotGit.resolve("index"))
      Files.isRegularFile(dotGit) -> gitDirOf(dotGit)?.let { WorkTree(dir.path + "/", it.resolve("index")) } ?: NONE
      else -> dir.parent?.let(::workTreeOf) ?: NONE
    }
    workTreeOfDir[dir.path] = found
    return found
  }

  // A linked worktree or submodule: ".git" is a file "gitdir: <path>".
  private fun gitDirOf(dotGit: Path): Path? = runCatching {
    val line = Files.readAllLines(dotGit).firstOrNull()?.takeIf { it.startsWith("gitdir:") } ?: return null
    dotGit.parent.resolve(line.removePrefix("gitdir:").trim()).normalize()
  }.getOrNull()

  private fun snapshotOf(index: Path): Map<String, Entry>? {
    val now = System.currentTimeMillis()
    val old = snapshots[index]
    // The index is stat'ed at most once a second, not once per file.
    if (old != null && now - old.checkedAt < 1000) return old.entries
    val stamp = runCatching { Files.getLastModifiedTime(index).toInstant().let { it.epochSecond * NANOS + it.nano } }.getOrNull()
      ?: return null.also { snapshots.remove(index) }
    val length = runCatching { Files.size(index) }.getOrDefault(-1)
    if (old != null && old.stamp == stamp && old.length == length) {
      snapshots[index] = Snapshot(stamp, length, now, old.entries)
      return old.entries
    }
    val entries = runCatching { parse(Files.readAllBytes(index), stamp) }
      .onFailure { LOG.debug("Cannot read the git index $index", it) }
      .getOrNull()
    snapshots[index] = Snapshot(stamp, length, now, entries)
    return entries
  }

  /**
   * Git's index format (Documentation/gitformat-index.txt), versions 2 to 4. Entries modified when the index was written
   * or later are left out, as git treats them as racily clean and compares their content. The comparison is in
   * nanoseconds, as git's with USE_NSEC: by seconds, a fresh checkout loses every file written in its last second.
   */
  internal fun parse(bytes: ByteArray, indexNanos: Long): Map<String, Entry>? {
    val buf = ByteBuffer.wrap(bytes)
    if (bytes.size < 12 || buf.getInt(0) != 0x44495243) return null // "DIRC"
    val version = buf.getInt(4)
    if (version !in 2..4) return null
    val count = buf.getInt(8)
    val result = HashMap<String, Entry>(count * 2)
    var pos = 12
    var previous = ""
    repeat(count) {
      val start = pos
      val mtimeS = buf.getInt(start + 8).toLong() and 0xffffffffL
      val mtimeNs = buf.getInt(start + 12).toLong() and 0xffffffffL
      val mode = buf.getInt(start + 24)
      val size = buf.getInt(start + 36)
      val blob = bytes.copyOfRange(start + 40, start + 60)
      val flags = buf.getShort(start + 60).toInt() and 0xffff
      pos = start + 62
      if (version >= 3 && flags and 0x4000 != 0) pos += 2
      val path: String
      if (version == 4) {
        var c = bytes[pos++].toInt() and 0xff
        var strip = c and 127
        while (c and 128 != 0) {
          c = bytes[pos++].toInt() and 0xff
          strip = ((strip + 1) shl 7) + (c and 127)
        }
        val end = indexOfNul(bytes, pos)
        path = previous.substring(0, previous.length - strip) + String(bytes, pos, end - pos, Charsets.UTF_8)
        pos = end + 1
      }
      else {
        val end = indexOfNul(bytes, pos)
        path = String(bytes, pos, end - pos, Charsets.UTF_8)
        pos = start + ((end - start + 8) and 7.inv())
      }
      previous = path
      val stage = (flags shr 12) and 3
      val regular = mode and 0xf000 == 0x8000
      if (stage == 0 && regular && mtimeS * NANOS + mtimeNs < indexNanos) result[path] = Entry(blob, size, mtimeS * 1000 + mtimeNs / 1_000_000)
    }
    // A split index keeps most entries in a shared file; such an index is not read.
    while (pos + 8 <= bytes.size - 20) {
      if (buf.getInt(pos) == 0x6c696e6b) return null // "link"
      pos += 8 + buf.getInt(pos + 4)
    }
    return result
  }

  private fun indexOfNul(bytes: ByteArray, from: Int): Int {
    var i = from
    while (bytes[i] != 0.toByte()) i++
    return i
  }

  private const val NANOS = 1_000_000_000L
  internal const val MAX_DIRS = 4096
  internal const val MAX_INDEXES = 8
  private val LOG = logger<GitBlobs>()
}
