// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.localsharedindex

import com.intellij.openapi.diagnostic.logger
import com.intellij.util.indexing.IdFilter
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Per index: which file id was indexed for a content key (the donor), and which files were skipped because their key
 * matched a donor (the aliases). Queries of an alias are answered with the donor's data, which the platform already
 * holds because file-based indexes live in one storage per IDE.
 */
internal class IndexAliases {
  private val donorByKey = ConcurrentHashMap<ContentKey, Donor>()
  // Reverse of donorByKey, so that forgetting a donor is not a scan of every key.
  private val keyOfDonor = ConcurrentHashMap<Int, ContentKey>()
  private val donorOfAlias = ConcurrentHashMap<Int, Int>()
  private val aliasesOfDonor = ConcurrentHashMap<Int, MutableSet<Int>>()
  // Length and name of every donor: scanning reads and hashes a file only when a donor could match it.
  private val signatures = ConcurrentHashMap<Long, Int>()
  // The same donors keyed by their git blob id instead of a hash of their content (see GitBlobs): scanning a git checkout
  // finds a donor without reading the file.
  private val donorByBlobKey = ConcurrentHashMap<ContentKey, Int>()
  private val blobKeyOfDonor = ConcurrentHashMap<Int, ContentKey>()
  // Bumped whenever the set of aliases changes: aliasing changes query results without a platform index update, which is
  // what moves the index's own modification stamp.
  private val epoch = AtomicLong()

  /** Changes whenever an alias is added or removed; mixed into the index's modification stamp. */
  val modificationCount: Long get() = epoch.get()

  val hasDonors: Boolean get() = donorByKey.isNotEmpty()

  val hasBlobDonors: Boolean get() = donorByBlobKey.isNotEmpty()

  /** [fileId], already recorded as a donor, has git blob key [blobKey]. */
  fun recordBlobDonor(blobKey: ByteArray, fileId: Int) {
    val key = ContentKey(blobKey)
    donorByBlobKey.put(key, fileId)?.let { old -> if (old != fileId) blobKeyOfDonor.remove(old, key) }
    blobKeyOfDonor[fileId] = key
  }

  fun donorForBlob(blobKey: ByteArray): Int? = donorByBlobKey[ContentKey(blobKey)]

  /** The platform indexed [fileId] with content [key]: it can stand in for other files with the same key. */
  fun recordDonor(key: ByteArray, fileId: Int, length: Long, name: CharSequence) {
    val contentKey = ContentKey(key)
    forgetDonorKey(fileId)
    val signature = signature(length, name)
    donorByKey.put(contentKey, Donor(fileId, signature))?.let { old ->
      dropSignature(old)
      keyOfDonor.remove(old.fileId, contentKey)
    }
    keyOfDonor[fileId] = contentKey
    signatures.merge(signature, 1, Int::plus)
  }

  /** Whether some donor has this length and name; cheap, no file access. */
  fun mayHaveDonor(length: Long, name: CharSequence): Boolean = signatures.containsKey(signature(length, name))

  fun donorFor(key: ByteArray): Int? = donorByKey[ContentKey(key)]?.fileId

  private fun forgetDonorKey(fileId: Int) {
    blobKeyOfDonor.remove(fileId)?.let { donorByBlobKey.remove(it, fileId) }
    val key = keyOfDonor.remove(fileId) ?: return
    donorByKey.computeIfPresent(key) { _, donor -> if (donor.fileId == fileId) { dropSignature(donor); null } else donor }
  }

  private fun dropSignature(donor: Donor) {
    signatures.computeIfPresent(donor.signature) { _, n -> if (n <= 1) null else n - 1 }
  }

  fun alias(fileId: Int, donor: Int) {
    donorOfAlias[fileId] = donor
    aliasesOfDonor.computeIfAbsent(donor) { ConcurrentHashMap.newKeySet() }.add(fileId)
    epoch.incrementAndGet()
  }

  fun donorOf(fileId: Int): Int? = donorOfAlias[fileId]

  fun aliasesOf(donor: Int): Set<Int> = aliasesOfDonor[donor].orEmpty()

  val hasAliases: Boolean get() = donorOfAlias.isNotEmpty()

  /**
   * [filter] that also accepts a donor when it accepts one of the donor's aliases. MapReduceIndexBase.processAllKeys of an
   * index that traces key hashes (KeyHashLog) keeps only keys logged for files the filter accepts, and only donors are
   * logged. The result keeps the default filter scope type OTHER, so that KeyHashLog does not cache it per project: an
   * alias added later does not change the log, and a cached result would go on missing its keys.
   */
  fun widen(filter: IdFilter?): IdFilter? {
    if (filter == null || !hasAliases) return filter
    return object : IdFilter() {
      override fun containsFileId(id: Int): Boolean = filter.containsFileId(id) || aliasesOf(id).any(filter::containsFileId)
    }
  }

  /** [fileId] is indexed on its own now (changed or removed): it neither borrows nor lends data any more. */
  fun forgetFile(fileId: Int) {
    donorOfAlias.remove(fileId)?.let { donor ->
      aliasesOfDonor[donor]?.remove(fileId)
      epoch.incrementAndGet()
    }
    forgetDonorKey(fileId)
  }

  /** The donor's data changes: its aliases must be indexed themselves. Returns them. */
  fun dropDonor(donor: Int): Set<Int> {
    forgetDonorKey(donor)
    val aliases = aliasesOfDonor.remove(donor).orEmpty()
    aliases.forEach { donorOfAlias.remove(it) }
    if (aliases.isNotEmpty()) epoch.incrementAndGet()
    return aliases
  }

  fun clear() {
    donorByKey.clear()
    keyOfDonor.clear()
    signatures.clear()
    donorByBlobKey.clear()
    blobKeyOfDonor.clear()
    donorOfAlias.clear()
    aliasesOfDonor.clear()
    epoch.incrementAndGet()
  }

  fun writeDonors(out: DataOutputStream) {
    val snapshot = donorByKey.entries.toList()
    out.writeInt(snapshot.size)
    for ((key, donor) in snapshot) {
      out.write(key.bytes)
      out.writeInt(donor.fileId)
      out.writeLong(donor.signature)
    }
    val blobs = donorByBlobKey.entries.toList()
    out.writeInt(blobs.size)
    for ((key, fileId) in blobs) {
      out.write(key.bytes)
      out.writeInt(fileId)
    }
  }

  fun readDonors(input: DataInputStream) {
    repeat(input.readInt()) {
      val key = ContentKey(ByteArray(KEY_SIZE).also(input::readFully))
      val donor = Donor(input.readInt(), input.readLong())
      donorByKey[key] = donor
      keyOfDonor[donor.fileId] = key
      signatures.merge(donor.signature, 1, Int::plus)
    }
    repeat(input.readInt()) {
      val key = ContentKey(ByteArray(KEY_SIZE).also(input::readFully))
      val fileId = input.readInt()
      donorByBlobKey[key] = fileId
      blobKeyOfDonor[fileId] = key
    }
  }
}

internal object AliasRegistry {
  private val byIndex = ConcurrentHashMap<String, IndexAliases>()

  fun of(indexName: String): IndexAliases = byIndex.computeIfAbsent(indexName) { IndexAliases() }

  fun clear(indexName: String) {
    byIndex[indexName]?.clear()
  }

  fun clearAll() = byIndex.values.forEach { it.clear() }

  // Donors are kept across restarts so that a checkout opened in a later session finds them; aliases are rebuilt by scanning.
  @Synchronized
  fun save(file: Path) {
    runCatching {
      val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
      DataOutputStream(Files.newOutputStream(tmp).buffered()).use { out ->
        out.writeInt(FORMAT)
        val indexes = byIndex.entries.toList()
        out.writeInt(indexes.size)
        for ((name, aliases) in indexes) {
          out.writeUTF(name)
          aliases.writeDonors(out)
        }
      }
      Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }.onFailure { LOG.warn("Phantom local shared index: cannot save the donor list", it) }
  }

  fun load(file: Path) {
    if (!Files.isRegularFile(file)) return
    runCatching {
      DataInputStream(Files.newInputStream(file).buffered()).use { input ->
        if (input.readInt() != FORMAT) return
        repeat(input.readInt()) { of(input.readUTF()).readDonors(input) }
      }
    }.onFailure {
      LOG.warn("Phantom local shared index: cannot read the donor list, starting without it", it)
      clearAll()
    }
  }

  private const val FORMAT = 3
  private val LOG = logger<AliasRegistry>()
}

private const val KEY_SIZE = 20

private class Donor(val fileId: Int, val signature: Long)

private fun signature(length: Long, name: CharSequence): Long = length * 1_000_003L + name.toString().hashCode()

private class ContentKey(val bytes: ByteArray) {
  private val hash = bytes.contentHashCode()
  override fun hashCode() = hash
  override fun equals(other: Any?) = other is ContentKey && other.bytes.contentEquals(bytes)
}
