// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.localsharedindex

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.indexing.IdFilter
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
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
  val modificationCount: Long get() = ready { epoch.get() }

  val hasDonors: Boolean get() = ready { donorByKey.isNotEmpty() }

  val hasBlobDonors: Boolean get() = ready { donorByBlobKey.isNotEmpty() }

  // Every public member waits for the saved list being read in the background (AliasRegistry.loadInBackground): answering
  // or changing aliases before it is in would answer nothing for restored aliases, or let it restore stale ones.
  private inline fun <T> ready(action: () -> T): T {
    AliasRegistry.awaitLoaded()
    return action()
  }

  /** [fileId], already recorded as a donor, has git blob key [blobKey]. */
  fun recordBlobDonor(blobKey: ByteArray, fileId: Int): Unit = ready {
    val key = ContentKey(blobKey)
    donorByBlobKey.put(key, fileId)?.let { old -> if (old != fileId) blobKeyOfDonor.remove(old, key) }
    blobKeyOfDonor[fileId] = key
  }

  fun donorForBlob(blobKey: ByteArray): Int? = ready { donorByBlobKey[ContentKey(blobKey)] }

  /** The platform indexed [fileId] with content [key]: it can stand in for other files with the same key. */
  fun recordDonor(key: ByteArray, fileId: Int, length: Long, name: CharSequence): Unit = ready {
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
  fun mayHaveDonor(length: Long, name: CharSequence): Boolean = ready { signatures.containsKey(signature(length, name)) }

  fun donorFor(key: ByteArray): Int? = ready { donorByKey[ContentKey(key)]?.fileId }

  private fun forgetDonorKey(fileId: Int) {
    blobKeyOfDonor.remove(fileId)?.let { donorByBlobKey.remove(it, fileId) }
    val key = keyOfDonor.remove(fileId) ?: return
    donorByKey.computeIfPresent(key) { _, donor -> if (donor.fileId == fileId) { dropSignature(donor); null } else donor }
  }

  private fun dropSignature(donor: Donor) {
    signatures.computeIfPresent(donor.signature) { _, n -> if (n <= 1) null else n - 1 }
  }

  fun alias(fileId: Int, donor: Int): Unit = ready {
    link(fileId, donor)
    epoch.incrementAndGet()
    AliasRegistry.changed()
  }

  // Restoring a saved alias goes through here only: it is no change against the saved list.
  private fun link(fileId: Int, donor: Int) {
    donorOfAlias[fileId] = donor
    aliasesOfDonor.computeIfAbsent(donor) { ConcurrentHashMap.newKeySet() }.add(fileId)
  }

  fun donorOf(fileId: Int): Int? = ready { donorOfAlias[fileId] }

  fun aliasesOf(donor: Int): Set<Int> = ready { aliasesOfDonor[donor].orEmpty() }

  val hasAliases: Boolean get() = ready { donorOfAlias.isNotEmpty() }

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
  fun forgetFile(fileId: Int): Unit = ready {
    donorOfAlias.remove(fileId)?.let { donor ->
      aliasesOfDonor[donor]?.remove(fileId)
      epoch.incrementAndGet()
      AliasRegistry.changed()
    }
    forgetDonorKey(fileId)
  }

  /** The donor's data changes: its aliases must be indexed themselves. Returns them. */
  fun dropDonor(donor: Int): Set<Int> = ready {
    forgetDonorKey(donor)
    val aliases = aliasesOfDonor.remove(donor).orEmpty()
    aliases.forEach { donorOfAlias.remove(it) }
    if (aliases.isNotEmpty()) {
      epoch.incrementAndGet()
      AliasRegistry.changed()
    }
    aliases
  }

  /** Drops the donors [isGone] names (their aliases would borrow nothing) and returns their aliases. */
  fun dropDonorsWhere(isGone: (Int) -> Boolean): Set<Int> = ready {
    val aliases = HashSet<Int>()
    for (donor in keyOfDonor.keys.toList()) if (isGone(donor)) aliases += dropDonor(donor)
    aliases
  }

  fun clear(): Unit = ready {
    donorByKey.clear()
    keyOfDonor.clear()
    signatures.clear()
    donorByBlobKey.clear()
    blobKeyOfDonor.clear()
    donorOfAlias.clear()
    aliasesOfDonor.clear()
    epoch.incrementAndGet()
    Unit
  }

  /** Donors and aliases; scanning does not visit an aliased file again in a later session, so its alias must be kept. */
  fun writeDonors(out: DataOutputStream): Unit = ready {
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
    val aliases = donorOfAlias.entries.toList()
    out.writeInt(aliases.size)
    for ((alias, donor) in aliases) {
      out.writeInt(alias)
      out.writeInt(donor)
    }
  }

  /**
   * Reads what [writeDonors] wrote; runs while the other members wait (see [ready]). Returns the aliases whose donor is
   * not in the list: they are not restored, and their files must be indexed again, since the platform keeps them marked
   * indexed. Whether each donor still exists is checked later ([dropDonorsWhere]), off the index initialization.
   */
  fun readDonors(input: DataInputStream): List<Int> {
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
      if (!keyOfDonor.containsKey(fileId)) return@repeat
      donorByBlobKey[key] = fileId
      blobKeyOfDonor[fileId] = key
    }
    val orphans = ArrayList<Int>()
    repeat(input.readInt()) {
      val alias = input.readInt()
      val donor = input.readInt()
      if (keyOfDonor.containsKey(donor)) link(alias, donor) else orphans += alias
    }
    epoch.incrementAndGet()
    return orphans
  }
}

internal object AliasRegistry {
  private val byIndex = ConcurrentHashMap<String, IndexAliases>()

  fun of(indexName: String): IndexAliases = byIndex.computeIfAbsent(indexName) { IndexAliases() }

  fun clear(indexName: String) {
    byIndex[indexName]?.clear()
  }

  fun clearAll() = byIndex.values.forEach { it.clear() }

  // Exists while an alias was added or dropped after the last save. A process that ends without saving (a crash) leaves
  // it behind: the next one cannot tell which files the platform marked indexed without data (see [load]).
  @Volatile
  private var marker: Path? = null
  private val dirty = AtomicBoolean()
  private val markerLock = Any()

  /** An alias was added or dropped; the saved list is out of date until the next [save]. */
  fun changed() {
    if (!dirty.compareAndSet(false, true)) return
    val marker = marker ?: return
    synchronized(markerLock) {
      runCatching { if (!Files.exists(marker)) Files.createFile(marker) }
        .onFailure { LOG.warn("Phantom local shared index: cannot create $marker", it) }
    }
  }

  /**
   * Donors are kept across restarts so that a checkout opened in a later session finds them, and aliases because the
   * platform keeps aliased files marked indexed (IndexingStamp): scanning in the next session does not ask about them
   * again. File ids are only valid for the VFS they were taken from ([vfsStamp], its creation timestamp).
   */
  @Synchronized
  fun save(file: Path, vfsStamp: Long) {
    dirty.set(false) // a change from here on is either in the snapshot below or sets it again
    runCatching {
      val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
      DataOutputStream(Files.newOutputStream(tmp).buffered()).use { out ->
        out.writeInt(FORMAT)
        out.writeLong(vfsStamp)
        val indexes = byIndex.entries.toList()
        out.writeInt(indexes.size)
        for ((name, aliases) in indexes) {
          out.writeUTF(name)
          aliases.writeDonors(out)
        }
      }
      Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }.onSuccess {
      synchronized(markerLock) { if (!dirty.get()) runCatching { Files.deleteIfExists(file.resolveSibling(MARKER)) } }
    }.onFailure {
      dirty.set(true)
      LOG.warn("Phantom local shared index: cannot save the donor list", it)
    }
  }

  /**
   * Restores the saved list. [Restored.trusted] is false when aliases of the last session may be missing: the change
   * marker is there (it ended without saving) or the list cannot be read (damaged, or written by a release that did not
   * save aliases). The registry is then empty, and the platform's indexed state of the aliased indexes cannot be trusted.
   */
  fun load(file: Path, vfsStamp: Long): Restored {
    val marker = file.resolveSibling(MARKER).also { marker = it }
    if (Files.exists(marker)) {
      clearAll()
      dirty.set(true)
      return Restored(emptySet(), trusted = false)
    }
    dirty.set(false)
    if (!Files.isRegularFile(file)) return Restored(emptySet(), trusted = true)
    val orphans = HashSet<Int>()
    val read = runCatching {
      DataInputStream(Files.newInputStream(file).buffered()).use { input ->
        check(input.readInt() == FORMAT) { "another format" }
        // Another VFS: its file ids mean nothing here, and a new VFS comes with empty indexes.
        if (input.readLong() != vfsStamp) return Restored(emptySet(), trusted = true)
        repeat(input.readInt()) { orphans += of(input.readUTF()).readDonors(input) }
      }
    }
    if (read.isSuccess) return Restored(orphans, trusted = true)
    LOG.warn("Phantom local shared index: cannot read the donor list, starting without it", read.exceptionOrNull())
    clearAll()
    changed()
    return Restored(emptySet(), trusted = false)
  }

  /**
   * Drops donors that no longer exist ([exists] is false) from every index and returns their aliases. Every index lists
   * mostly the same files, so each is checked once.
   */
  fun dropGoneDonors(exists: (Int) -> Boolean): Set<Int> {
    val checked = HashMap<Int, Boolean>()
    return byIndex.values.flatMapTo(HashSet()) { aliases -> aliases.dropDonorsWhere { !checked.getOrPut(it) { exists(it) } } }
  }

  // Set while the saved list is read in the background; every IndexAliases member waits for it (IndexAliases.ready).
  @Volatile
  private var loading: CompletableFuture<Restored>? = null
  @Volatile
  private var loader: Thread? = null

  /**
   * Runs [read] (normally [load]) on a pooled thread and returns at once: reading a list of a few hundred thousand donors
   * takes about half a second warm and two cold, which in FileBasedIndexInfrastructureExtension.initialize delayed the
   * index initialization until the project's first scanning request came too late and a full scan replaced the
   * dirty-files scan (unindexedFilesScannerStartup.kt, invalidateProjectFilterIfFirstScanningNotRequested).
   */
  fun loadInBackground(read: () -> Restored): CompletableFuture<Restored> {
    val future = CompletableFuture<Restored>()
    loading = future
    ApplicationManager.getApplication().executeOnPooledThread {
      loader = Thread.currentThread()
      try {
        runCatching(read).fold(future::complete, future::completeExceptionally)
      }
      finally {
        loader = null
        if (loading === future) loading = null
      }
    }
    return future
  }

  /** Waits until a [loadInBackground] read finished, unless called by that read itself. */
  fun awaitLoaded() {
    val future = loading ?: return
    if (future.isDone || Thread.currentThread() === loader) return
    runCatching { future.join() }
  }

  /** [orphans]: files of aliases that could not be restored (see [IndexAliases.readDonors]). */
  class Restored(val orphans: Set<Int>, val trusted: Boolean)

  const val MARKER = "aliases.dirty"

  private const val FORMAT = 4
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
