// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.localsharedindex.store

import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import org.jetbrains.annotations.TestOnly
import java.io.Closeable
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Per-index key/value cache on disk, shared by the indexing threads of one process.
 *
 * Threading: `get` runs on the caller's thread against an in-memory key map and positional file reads; everything
 * that changes files (appends, rolling, eviction, header checks, the opening scan) runs on one writer thread, in
 * the order it was queued. `put` hands the record over to that thread; the payload array must not be modified
 * afterwards. A `put` is invisible to `get` until the writer has appended it.
 *
 * Eviction is segment-granular LRU: while the total size exceeds the cap, the store-wide oldest segment is deleted
 * (down to 90% of the cap). A `get` hit from a segment other than its index's current one queues the record again,
 * which moves it into the newest segment, so recently read entries outlive the oldest segment.
 *
 * Removing a key is queued like a put, so a put queued before it cannot bring the key back. The record stays on
 * disk until its segment is deleted, so a key removed in one session can reappear after a reopen.
 */
@Suppress("TooManyFunctions") // one writer-thread state machine; splitting it would only spread the locking rules
class LocalSharedIndexStore private constructor(
  private val root: Path,
  private val maxBytes: Long,
  private val segmentBytes: Long,
  private val lockChannel: FileChannel,
  private val lock: FileLock,
  encoderThreads: Int,
  private val maxPendingEncodes: Int,
) : Closeable {
  private class IndexState(val dirName: String, val dir: Path) {
    val map = ConcurrentHashMap<KeyBytes, Location>()

    /** Set when the writer has checked the index's header; unchecked data is never served. */
    @Volatile var validated = false

    /** [ensureHeader] calls whose check has not finished; while any is pending the index serves nothing. */
    val pendingChecks = AtomicInteger()

    val ready get() = validated && pendingChecks.get() == 0

    /**
     * Bumped by every change that must win over records still being encoded ([clear], [remove], [ensureHeader],
     * [clearAll]); a deferred put whose epoch is stale when it reaches the writer is dropped.
     */
    val epoch = AtomicInteger()

    /** The segment appends go to; written by the writer thread only. */
    @Volatile var current: Segment? = null
  }

  private val indexes = ConcurrentHashMap<String, IndexState>()
  private val segments = ConcurrentSkipListMap<Long, Segment>()
  private val total = AtomicLong()
  private val pendingBytes = AtomicLong()
  private val pendingEncodes = AtomicInteger()
  private val dropped = AtomicLong()
  private val droppedAtLastLog = AtomicLong()
  private val lastDropLog = AtomicLong(System.nanoTime() - DROP_LOG_INTERVAL_NANOS)
  private var nextGeneration = 1L // writer thread only

  @Volatile private var closed = false

  private val writer = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue()) { r ->
    Thread(r, "LocalSharedIndexStore writer").apply { isDaemon = true }
  }

  // Encoding must neither run on the indexing threads nor slow the single writer thread.
  private val encoder = ThreadPoolExecutor(encoderThreads, encoderThreads, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue()) { r ->
    Thread(r, "LocalSharedIndexStore encoder").apply { isDaemon = true }
  }

  private fun start() {
    submit { scan() }
  }

  fun totalBytes(): Long = total.get()

  /**
   * Makes [index] usable with [header]: an index directory written under any other (or an unreadable) header is
   * deleted first. Asynchronous; later calls on this store are queued behind it.
   */
  fun ensureHeader(index: String, header: StoreHeader) {
    val state = state(index)
    state.epoch.incrementAndGet()
    // From here on gets miss: entries of the old header must not be served while the writer works its way here.
    state.validated = false
    state.pendingChecks.incrementAndGet()
    submit {
      try {
        checkHeader(state, header)
      }
      finally {
        state.pendingChecks.decrementAndGet()
      }
    }
  }

  private fun checkHeader(state: IndexState, header: StoreHeader) {
    val file = state.dir.resolve(StoreHeaderFile.NAME)
    val stored = StoreHeaderFile.read(file)
    if (stored != header) {
      clearIndex(state)
      Files.deleteIfExists(file)
      Files.createDirectories(state.dir)
      StoreHeaderFile.write(file, header)
    }
    state.validated = true
  }

  fun get(index: String, key: ByteArray): StoredEntry? {
    if (closed || key.size != Segment.KEY_BYTES) return null
    val state = indexes[dirName(index)]?.takeIf { it.ready } ?: return null
    val keyBytes = KeyBytes(key)
    val location = state.map[keyBytes] ?: return null
    val segment = segments[location.generation]
    val entry = try {
      segment?.read(key, location.offset, location.length)
    }
    catch (_: IOException) {
      return null // the file could not be read, which says nothing about the record: keep the key
    }
    if (entry == null) {
      // evicted, torn or corrupt: a miss, and the key goes away (unless a newer record already replaced it)
      state.map.remove(keyBytes, location)
      return null
    }
    if (state.current?.generation != location.generation) {
      val copy = entry.payload.copyOf() // the caller owns the returned array
      queue(copy.size) { if (state.map[keyBytes] == location) append(state, keyBytes, entry.mapMicros, copy, location) }
    }
    return entry
  }

  /** Queues the record for writing; dropped when more than 64 MB of payload is already waiting. */
  fun put(index: String, key: ByteArray, mapMicros: Int, payload: ByteArray) {
    if (closed || key.size != Segment.KEY_BYTES) return
    val state = state(index)
    val keyBytes = KeyBytes(key)
    queue(payload.size) { append(state, keyBytes, mapMicros, payload, null) }
  }

  /**
   * Like [put], but [encode] runs on a background thread, not the caller's: it must only read what it captured, and
   * what it captured must not change afterwards. The record is dropped when too many encodes are waiting, when
   * [encode] throws, and when the index was cleared, a key of it removed or its header checked after [epoch] was read.
   */
  @Suppress("TooGenericExceptionCaught") // the externalizer may fail in any way on odd data; that costs the record only
  fun put(index: String, key: ByteArray, mapMicros: Int, epoch: Int = epochOf(index), encode: () -> ByteArray) {
    if (closed || key.size != Segment.KEY_BYTES) return
    if (pendingEncodes.incrementAndGet() > maxPendingEncodes) {
      pendingEncodes.decrementAndGet()
      noteDropped()
      return
    }
    val state = state(index)
    val keyBytes = KeyBytes(key)
    try {
      encoder.execute {
        try {
          if (state.epoch.get() != epoch) return@execute
          val payload = try {
            encode()
          }
          catch (e: Throwable) {
            // Any failure, Errors included, costs the record, never the worker. A cancellation means nothing on this thread.
            if (e !is ControlFlowException) LOG.debug("Cannot encode a record for ${state.dirName}, dropping it", e)
            return@execute
          }
          queue(payload.size) { if (state.epoch.get() == epoch) append(state, keyBytes, mapMicros, payload, null) }
        }
        finally {
          pendingEncodes.decrementAndGet()
        }
      }
    }
    catch (_: RejectedExecutionException) {
      pendingEncodes.decrementAndGet()
    }
  }

  /** The index's current epoch; a caller that maps a file first passes the value it read before mapping to [put]. */
  fun epochOf(index: String): Int = state(index).epoch.get()

  /** Counts a record lost to a full backlog or the pending-payload cap; logs the total at most once a minute. */
  private fun noteDropped() {
    val total = dropped.incrementAndGet()
    val now = System.nanoTime()
    val last = lastDropLog.get()
    if (now - last < DROP_LOG_INTERVAL_NANOS || !lastDropLog.compareAndSet(last, now)) return
    val since = total - droppedAtLastLog.getAndSet(total)
    LOG.info("Phantom local shared index: dropped $since record(s) that were not written because the encode or write backlog was full ($total in this session)")
  }

  /** Records dropped since the store was opened because a backlog was full. */
  @TestOnly
  fun droppedRecords(): Long = dropped.get()

  fun remove(index: String, key: ByteArray) {
    if (key.size != Segment.KEY_BYTES) return
    val state = indexes[dirName(index)] ?: return
    state.epoch.incrementAndGet() // records of this index still being encoded might hold the key
    val keyBytes = KeyBytes(key)
    state.map.remove(keyBytes)
    submit { state.map.remove(keyBytes) } // after any put queued before this call
  }

  /**
   * Forgets everything stored for [index], also right after opening the store, before its scan has registered the
   * index, and also without a prior [ensureHeader]. The index serves nothing from this call until its next
   * [ensureHeader]. The deletion runs on the writer thread, behind the opening scan and earlier writes.
   */
  fun clear(index: String) {
    val state = state(index)
    state.epoch.incrementAndGet()
    state.validated = false
    state.map.clear()
    // An ensureHeader queued before this call must not re-validate the old data before the wipe has run.
    state.pendingChecks.incrementAndGet()
    submit {
      try {
        wipe(state)
        state.validated = false // a check queued before this call may have validated the old header meanwhile
      }
      finally {
        state.pendingChecks.decrementAndGet()
      }
    }
  }

  /**
   * Deletes the data of every index, headers stay. The scan is queued first, so by the time the task runs every index
   * directory on disk has a state; waits for it, like the write queue, so that the store is empty when this returns.
   */
  fun clearAll() {
    // Records still being encoded are dropped when they reach the writer, behind the wipe below.
    indexes.values.forEach {
      it.epoch.incrementAndGet()
      it.map.clear()
    }
    submitAndWait { indexes.values.forEach { clearIndex(it) } }
  }

  private fun wipe(state: IndexState) {
    clearIndex(state)
    Files.deleteIfExists(state.dir.resolve(StoreHeaderFile.NAME))
  }

  /** Waits until everything queued so far, including the opening scan, has been done. */
  @TestOnly
  fun awaitIdle() {
    while (pendingEncodes.get() > 0 && !closed) Thread.sleep(1) // an encode hands its record to the writer before it finishes
    submitAndWait { }
  }

  override fun close() {
    if (closed) return
    closed = true
    encoder.shutdownNow()
    writer.shutdown()
    writer.awaitTermination(1, TimeUnit.MINUTES)
    segments.values.forEach { it.retire() }
    try {
      lock.release()
      lockChannel.close()
    }
    catch (_: IOException) {
    }
  }

  private fun state(index: String): IndexState {
    val name = dirName(index)
    return indexes.computeIfAbsent(name) { IndexState(name, root.resolve(name)) }
  }

  // ---- writer thread ----

  private fun submit(task: () -> Unit) {
    try {
      writer.execute { runGuarded(task) }
    }
    catch (_: RejectedExecutionException) {
    }
  }

  private fun submitAndWait(task: () -> Unit) {
    if (closed) return
    try {
      writer.submit { runGuarded(task) }.get()
    }
    catch (_: RejectedExecutionException) {
    }
  }

  private fun queue(payloadBytes: Int, task: () -> Unit) {
    if (pendingBytes.addAndGet(payloadBytes.toLong()) > MAX_PENDING_BYTES) {
      pendingBytes.addAndGet(-payloadBytes.toLong())
      noteDropped()
      return
    }
    try {
      writer.execute {
        try {
          runGuarded(task)
        }
        finally {
          pendingBytes.addAndGet(-payloadBytes.toLong())
        }
      }
    }
    catch (_: RejectedExecutionException) {
      pendingBytes.addAndGet(-payloadBytes.toLong())
    }
  }

  /** A failing disk or a damaged file costs cache entries, never the caller. */
  private fun runGuarded(task: () -> Unit) {
    try {
      task()
    }
    catch (_: IOException) {
    }
    catch (_: RuntimeException) {
    }
  }

  private fun scan() {
    val dirs = try {
      Files.newDirectoryStream(root).use { stream -> stream.filter { Files.isDirectory(it) } }
    }
    catch (_: IOException) {
      return
    }
    val reusable = mutableListOf<Pair<IndexState, Segment>>()
    for (dir in dirs) {
      val state = indexes.computeIfAbsent(dir.fileName.toString()) { IndexState(it, dir) }
      try {
        scanIndex(state)?.let { reusable += state to it }
      }
      catch (_: IOException) {
        // an unreadable index directory is skipped; the rest of the store stays usable
      }
    }
    // Keep appending to an index's last segment instead of starting one (and a file handle) per session. It is
    // renamed to a new generation first: a reused segment with its old generation would sit at the old end of the
    // store-wide eviction order and take the freshest entries with it.
    for ((state, segment) in reusable) {
      state.current = try {
        promoteToNewest(state, segment)
      }
      catch (_: IOException) {
        null
      }
    }
  }

  /** Loads the segments of one index; returns its last segment if it has room for more records. */
  private fun scanIndex(state: IndexState): Segment? {
    val found = Files.newDirectoryStream(state.dir, "seg-*.dat").use { stream ->
      stream.mapNotNull { path -> generationOf(path)?.let { it to path } }.sortedBy { it.first }
    }
    var lastSegment: Segment? = null
    for ((generation, path) in found) {
      var segment: Segment? = null
      try {
        segment = Segment(generation, path, state.dirName)
        val end = segment.scan { key, location -> state.map[key] = location }
        // drop a torn tail now, so appends to this segment follow valid data
        if (end < segment.size.get()) segment.truncate(end)
      }
      catch (_: IOException) {
        segment?.retire() // one bad segment must not stop the scan of the others
        nextGeneration = maxOf(nextGeneration, generation + 1)
        continue
      }
      segments[generation] = segment
      total.addAndGet(segment.size.get())
      nextGeneration = maxOf(nextGeneration, generation + 1)
      lastSegment = segment
    }
    return lastSegment?.takeIf { it.size.get() < segmentBytes }
  }

  private fun promoteToNewest(state: IndexState, old: Segment): Segment {
    val generation = nextGeneration++
    val path = state.dir.resolve("seg-$generation.dat")
    old.retire()
    Files.move(old.path, path)
    val renamed = Segment(generation, path, state.dirName)
    segments.remove(old.generation)
    segments[generation] = renamed
    state.map.replaceAll { _, location -> if (location.generation == old.generation) location.copy(generation = generation) else location }
    return renamed
  }

  private fun append(state: IndexState, key: KeyBytes, mapMicros: Int, payload: ByteArray, replacing: Location?) {
    val record = Segment.encode(key.bytes, mapMicros, payload)
    var segment = state.current
    if (segment == null || (segment.size.get() > 0 && segment.size.get() + record.remaining() > segmentBytes)) {
      Files.createDirectories(state.dir)
      val generation = nextGeneration++
      segment = Segment(generation, state.dir.resolve("seg-$generation.dat"), state.dirName)
      segments[generation] = segment
      state.current = segment
    }
    val length = record.remaining()
    val offset = segment.append(record)
    total.addAndGet(length.toLong())
    val location = Location(segment.generation, offset, length)
    if (replacing == null) state.map[key] = location else state.map.replace(key, replacing, location)
    evictIfNeeded()
  }

  private fun evictIfNeeded() {
    if (total.get() <= maxBytes) return
    val target = maxBytes / 10 * 9
    while (total.get() > target) {
      deleteSegment(segments.firstEntry()?.value ?: return)
    }
  }

  private fun deleteSegment(segment: Segment) {
    segments.remove(segment.generation)
    val state = indexes[segment.indexDir]
    if (state != null) {
      if (state.current === segment) state.current = null
      state.map.values.removeIf { it.generation == segment.generation }
    }
    segment.retire()
    total.addAndGet(-segment.size.get())
    try {
      Files.deleteIfExists(segment.path)
    }
    catch (_: IOException) {
    }
  }

  private fun clearIndex(state: IndexState) {
    segments.values.filter { it.indexDir == state.dirName }.forEach { deleteSegment(it) }
    state.map.clear()
    state.current = null
    if (!Files.isDirectory(state.dir)) return
    Files.newDirectoryStream(state.dir, "seg-*.dat").use { stream -> stream.forEach { Files.deleteIfExists(it) } }
  }

  companion object {
    const val FORMAT_VERSION = 1
    const val SEGMENT_BYTES = 64L shl 20

    private const val MAX_PENDING_BYTES = 64L shl 20
    private const val MAX_ENCODER_THREADS = 4
    // Each pending encode holds a whole map until encoded; 64 per thread keeps that to a few hundred maps, and an
    // encoder more than 64 records behind is not keeping up anyway.
    private const val PENDING_ENCODES_PER_THREAD = 64
    private val DROP_LOG_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1)
    private val LOG = logger<LocalSharedIndexStore>()
    private val SEGMENT_NAME = Regex("seg-(\\d+)\\.dat")
    private val UNSAFE = Regex("[^A-Za-z0-9._-]")

    // Looked up for every get and put: computed once per index name, not with a regex per file.
    private val dirNames = ConcurrentHashMap<String, String>()

    private fun dirName(index: String): String = dirNames.computeIfAbsent(index) {
      val name = it.replace(UNSAFE, "_")
      if (name.all { c -> c == '.' }) "_$name" else name
    }

    private fun generationOf(path: Path): Long? = SEGMENT_NAME.matchEntire(path.fileName.toString())?.groupValues?.get(1)?.toLongOrNull()

    /** Opens the store under [root] and starts its background scan; null when another process or store holds the root's lock. */
    fun open(
      root: Path,
      maxBytes: Long,
      segmentBytes: Long = SEGMENT_BYTES,
      // Half the cores, at most 4: enough to keep up with the indexing threads' misses without competing with them.
      encoderThreads: Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, MAX_ENCODER_THREADS),
      maxPendingEncodes: Int = encoderThreads * PENDING_ENCODES_PER_THREAD,
    ): LocalSharedIndexStore? {
      var channel: FileChannel? = null
      try {
        Files.createDirectories(root)
        channel = FileChannel.open(root.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        val lock = channel.tryLock()
        if (lock == null) {
          channel.close()
          return null
        }
        return LocalSharedIndexStore(root, maxBytes, segmentBytes, channel, lock, encoderThreads, maxPendingEncodes).also { it.start() }
      }
      catch (_: OverlappingFileLockException) {
        channel?.close()
        return null
      }
      catch (_: IOException) {
        channel?.close()
        return null
      }
    }
  }
}
