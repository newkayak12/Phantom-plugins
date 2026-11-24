// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.localsharedindex

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import it.unimi.dsi.fastutil.bytes.ByteArrays
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap
import it.unimi.dsi.fastutil.objects.Object2IntOpenCustomHashMap
import it.unimi.dsi.fastutil.objects.ObjectOpenCustomHashSet
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Per index: which file id was indexed for a content key (the donor), and which files were skipped because their key
 * matched a donor (the aliases). Queries of an alias are answered with the donor's data, which the platform already
 * holds because file-based indexes live in one storage per IDE.
 *
 * A large project has a few hundred thousand donors per index, so the maps are fastutil primitive maps (no boxed file
 * ids, no entry objects) guarded by one read-write lock; key arrays are kept as given, without a wrapper. Callbacks run
 * outside the lock.
 */
@Suppress("TooManyFunctions") // the alias operations plus the unlocked helpers they share
internal class IndexAliases {
  private val lock = ReentrantReadWriteLock()
  private val donorByKey = Object2IntOpenCustomHashMap<ByteArray>(ByteArrays.HASH_STRATEGY).apply { defaultReturnValue(NONE) }
  // Reverse of donorByKey, so that forgetting a donor is not a scan of every key.
  private val keyOfDonor = Int2ObjectOpenHashMap<ByteArray>()
  private val signatureOfDonor = Int2LongOpenHashMap()
  private val donorOfAlias = Int2IntOpenHashMap().apply { defaultReturnValue(NONE) }
  // Copied on change, so a caller may iterate an array it got without the lock. Most donors have one or two aliases.
  private val aliasesOfDonor = Int2ObjectOpenHashMap<IntArray>()
  // Length and name of every donor, with the number of donors per signature: scanning reads and hashes a file only when
  // a donor could match it.
  private val signatures = Long2IntOpenHashMap()
  // The same donors keyed by their git blob id instead of a hash of their content (see GitBlobs): scanning a git checkout
  // finds a donor without reading the file.
  private val donorByBlobKey = Object2IntOpenCustomHashMap<ByteArray>(ByteArrays.HASH_STRATEGY).apply { defaultReturnValue(NONE) }
  private val blobKeyOfDonor = Int2ObjectOpenHashMap<ByteArray>()
  // Bumped whenever the set of aliases changes: aliasing changes query results without a platform index update, which is
  // what moves the index's own modification stamp.
  private val epoch = AtomicLong()

  /** Changes whenever an alias is added or removed; mixed into the index's modification stamp. */
  val modificationCount: Long get() = ready { epoch.get() }

  val hasDonors: Boolean get() = read { donorByKey.isNotEmpty() }

  val hasBlobDonors: Boolean get() = read { donorByBlobKey.isNotEmpty() }

  // Every public member waits for the saved list being read in the background (AliasRegistry.loadInBackground): answering
  // or changing aliases before it is in would answer nothing for restored aliases, or let it restore stale ones.
  private inline fun <T> ready(action: () -> T): T {
    AliasRegistry.awaitLoaded()
    return action()
  }

  private inline fun <T> read(action: () -> T): T = ready { lock.read(action) }

  private inline fun <T> write(action: () -> T): T = ready { lock.write(action) }

  /** [fileId], already recorded as a donor, has git blob key [blobKey]. */
  fun recordBlobDonor(blobKey: ByteArray, fileId: Int): Unit = write {
    val old = donorByBlobKey.put(blobKey, fileId)
    if (old != NONE && old != fileId) removeIfSame(blobKeyOfDonor, old, blobKey)
    blobKeyOfDonor.put(fileId, blobKey)
  }

  fun donorForBlob(blobKey: ByteArray): Int? = read { donorByBlobKey.getInt(blobKey).orNull() }

  /** The platform indexed [fileId] with content [key]: it can stand in for other files with the same key. */
  fun recordDonor(key: ByteArray, fileId: Int, length: Long, name: CharSequence): Unit = write {
    forgetDonorKey(fileId)
    val signature = signature(length, name)
    val old = donorByKey.put(key, fileId)
    if (old != NONE) {
      dropSignature(old)
      removeIfSame(keyOfDonor, old, key)
    }
    keyOfDonor.put(fileId, key)
    signatureOfDonor.put(fileId, signature)
    signatures.addTo(signature, 1)
  }

  /** Whether some donor has this length and name; cheap, no file access. */
  fun mayHaveDonor(length: Long, name: CharSequence): Boolean = read { signatures.containsKey(signature(length, name)) }

  fun donorFor(key: ByteArray): Int? = read { donorByKey.getInt(key).orNull() }

  private fun forgetDonorKey(fileId: Int) {
    blobKeyOfDonor.remove(fileId)?.let { removeIfSame(donorByBlobKey, it, fileId) }
    val key = keyOfDonor.remove(fileId) ?: return
    if (removeIfSame(donorByKey, key, fileId)) dropSignature(fileId)
  }

  private fun dropSignature(donor: Int) {
    if (!signatureOfDonor.containsKey(donor)) return
    val signature = signatureOfDonor.remove(donor)
    if (signatures.addTo(signature, -1) <= 1) signatures.remove(signature)
  }

  fun alias(fileId: Int, donor: Int) {
    write { link(fileId, donor) }
    epoch.incrementAndGet()
    AliasRegistry.changed()
  }

  // Restoring a saved alias goes through here only: it is no change against the saved list.
  private fun link(fileId: Int, donor: Int) {
    donorOfAlias.put(fileId, donor)
    val aliases = aliasesOfDonor.get(donor)
    if (aliases == null) aliasesOfDonor.put(donor, intArrayOf(fileId))
    else if (fileId !in aliases) aliasesOfDonor.put(donor, aliases + fileId)
  }

  fun donorOf(fileId: Int): Int? = read { donorOfAlias.get(fileId).orNull() }

  /** The aliases of [donor]; the array is not changed afterwards. */
  fun aliasesOf(donor: Int): IntArray = read { aliasesOfDonor.get(donor) ?: EMPTY }

  val hasAliases: Boolean get() = read { donorOfAlias.isNotEmpty() }

  /** Calls [action] with every donor that has aliases, and those aliases. */
  fun forEachAliased(action: (donor: Int, aliases: IntArray) -> Unit) {
    val snapshot = read { aliasesOfDonor.int2ObjectEntrySet().map { it.intKey to it.value } }
    for ((donor, aliases) in snapshot) if (aliases.isNotEmpty()) action(donor, aliases)
  }

  /** Changes identity whenever [donor] is recorded with other content, so data read from it can be cached against it. */
  fun donorToken(donor: Int): Any? = read { keyOfDonor.get(donor) }

  /** [fileId] is indexed on its own now (changed or removed): it neither borrows nor lends data any more. */
  fun forgetFile(fileId: Int) {
    val unlinked = write {
      val donor = donorOfAlias.remove(fileId)
      if (donor != NONE) unlink(donor, fileId)
      forgetDonorKey(fileId)
      donor != NONE
    }
    if (unlinked) {
      epoch.incrementAndGet()
      AliasRegistry.changed()
    }
  }

  private fun unlink(donor: Int, fileId: Int) {
    val aliases = aliasesOfDonor.get(donor) ?: return
    val i = aliases.indexOf(fileId)
    if (i >= 0) aliasesOfDonor.put(donor, aliases.copyOfRange(0, i) + aliases.copyOfRange(i + 1, aliases.size))
  }

  /** The donor's data changes: its aliases must be indexed themselves. Returns them. */
  fun dropDonor(donor: Int): Set<Int> {
    val aliases = write {
      forgetDonorKey(donor)
      val aliases = aliasesOfDonor.remove(donor) ?: EMPTY
      aliases.forEach { donorOfAlias.remove(it) }
      aliases
    }
    if (aliases.isNotEmpty()) {
      epoch.incrementAndGet()
      AliasRegistry.changed()
    }
    return aliases.toSet()
  }

  /**
   * Drops the donors with aliases that [isGone] names (the aliases would borrow nothing) and returns those aliases. A
   * donor without aliases is checked when an alias for it is created instead ([isLive]): most donors never get one.
   */
  fun dropDonorsWhere(isGone: (Int) -> Boolean): Set<Int> {
    val aliases = HashSet<Int>()
    for (donor in read { aliasesOfDonor.keys.toIntArray() }) if (isGone(donor)) aliases += dropDonor(donor)
    return aliases
  }

  /**
   * Whether [donor] still exists, checked before a file becomes its alias (one VFS lookup per new alias). A donor that is
   * gone, as one deleted in an earlier session can be, is dropped, and aliases it still had are indexed again.
   */
  fun isLive(donor: Int): Boolean {
    val liveness = AliasRegistry.liveness ?: return true
    if (liveness.exists(donor)) return true
    val orphans = dropDonor(donor)
    if (orphans.isNotEmpty()) liveness.reindex(orphans)
    return false
  }

  fun clear(): Unit = write {
    donorByKey.clear()
    keyOfDonor.clear()
    signatureOfDonor.clear()
    signatures.clear()
    donorByBlobKey.clear()
    blobKeyOfDonor.clear()
    donorOfAlias.clear()
    aliasesOfDonor.clear()
    epoch.incrementAndGet()
    Unit
  }

  /** Donors and aliases; scanning does not visit an aliased file again in a later session, so its alias must be kept. */
  fun writeDonors(out: DataOutputStream) {
    // Encoded under the lock and written without it, so that indexing does not wait for the disk.
    val bytes = read {
      val buffer = ByteArrayOutputStream((donorByKey.size + donorByBlobKey.size) * (KEY_SIZE + Long.SIZE_BYTES) + donorOfAlias.size * 8 + 12)
      DataOutputStream(buffer).use { encode(it) }
      buffer
    }
    bytes.writeTo(out)
  }

  private fun encode(out: DataOutputStream) {
    out.writeInt(donorByKey.size)
    for (entry in donorByKey.object2IntEntrySet()) {
      out.write(entry.key)
      out.writeInt(entry.intValue)
      out.writeLong(signatureOfDonor.get(entry.intValue))
    }
    out.writeInt(donorByBlobKey.size)
    for (entry in donorByBlobKey.object2IntEntrySet()) {
      out.write(entry.key)
      out.writeInt(entry.intValue)
    }
    out.writeInt(donorOfAlias.size)
    for (entry in donorOfAlias.int2IntEntrySet()) {
      out.writeInt(entry.intKey)
      out.writeInt(entry.intValue)
    }
  }

  /**
   * Reads what [writeDonors] wrote; runs while the other members wait (see [ready]). Returns the aliases whose donor is
   * not in the list: they are not restored, and their files must be indexed again, since the platform keeps them marked
   * indexed. Whether each donor still exists is checked later ([dropDonorsWhere]), off the index initialization.
   * [keys] hands out one array per distinct key, so the indexes of a file, which mostly have the same key, share it.
   */
  fun readDonors(input: DataInputStream, keys: KeyInterner): List<Int> = lock.write {
    repeat(input.readInt()) {
      val key = keys.read(input)
      val fileId = input.readInt()
      val signature = input.readLong()
      donorByKey.put(key, fileId)
      keyOfDonor.put(fileId, key)
      signatureOfDonor.put(fileId, signature)
      signatures.addTo(signature, 1)
    }
    repeat(input.readInt()) {
      val key = keys.read(input)
      val fileId = input.readInt()
      if (!keyOfDonor.containsKey(fileId)) return@repeat
      donorByBlobKey.put(key, fileId)
      blobKeyOfDonor.put(fileId, key)
    }
    val orphans = ArrayList<Int>()
    repeat(input.readInt()) {
      val alias = input.readInt()
      val donor = input.readInt()
      if (keyOfDonor.containsKey(donor)) link(alias, donor) else orphans += alias
    }
    epoch.incrementAndGet()
    orphans
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
        val keys = KeyInterner()
        repeat(input.readInt()) { orphans += of(input.readUTF()).readDonors(input, keys) }
      }
    }
    if (read.isSuccess) return Restored(orphans, trusted = true)
    LOG.warn("Phantom local shared index: cannot read the donor list, starting without it", read.exceptionOrNull())
    clearAll()
    changed()
    return Restored(emptySet(), trusted = false)
  }

  /** How [IndexAliases.isLive] checks a donor and reindexes files; set by the extension, unset in unit tests. */
  interface Liveness {
    fun exists(fileId: Int): Boolean

    fun reindex(fileIds: Set<Int>)
  }

  @Volatile
  var liveness: Liveness? = null

  /**
   * Drops donors with aliases that no longer exist ([exists] is false) from every index and returns their aliases.
   * Every index lists mostly the same files, so each is checked once.
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

// Not a file id: VFS ids are positive.
private const val NONE = Int.MIN_VALUE

private val EMPTY = IntArray(0)

private fun Int.orNull(): Int? = takeIf { it != NONE }

private fun <V> removeIfSame(map: Int2ObjectOpenHashMap<V>, fileId: Int, value: V) {
  if (map.get(fileId) == value) map.remove(fileId)
}

private fun removeIfSame(map: Object2IntOpenCustomHashMap<ByteArray>, key: ByteArray, fileId: Int): Boolean {
  if (map.getInt(key) != fileId) return false
  map.removeInt(key)
  return true
}

private fun signature(length: Long, name: CharSequence): Long = length * 1_000_003L + name.toString().hashCode()

/** While the saved list is read: one array per distinct key across all indexes. */
internal class KeyInterner {
  private val keys = ObjectOpenCustomHashSet<ByteArray>(ByteArrays.HASH_STRATEGY)

  fun read(input: DataInputStream): ByteArray = keys.addOrGet(ByteArray(KEY_SIZE).also(input::readFully))
}
