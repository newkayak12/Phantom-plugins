// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.localindex.store

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class LocalIndexStoreTest {
  @get:Rule val tmp = TemporaryFolder()

  private val header = StoreHeader(LocalIndexStore.FORMAT_VERSION, 1, 1, "layout", 7L)
  private val opened = mutableListOf<LocalIndexStore>()

  @After fun closeAll() = opened.forEach { it.close() }

  private fun root(): Path = tmp.root.toPath().resolve("store")

  private fun open(maxBytes: Long = 1L shl 30, segmentBytes: Long = LocalIndexStore.SEGMENT_BYTES, indexes: List<String> = listOf("a")): LocalIndexStore {
    val store = LocalIndexStore.open(root(), maxBytes, segmentBytes)!!
    opened += store
    indexes.forEach { store.ensureHeader(it, header) }
    store.awaitIdle()
    return store
  }

  private fun key(i: Int): ByteArray = ByteArray(20).also { it[0] = (i shr 24).toByte(); it[1] = (i shr 16).toByte(); it[2] = (i shr 8).toByte(); it[3] = i.toByte() }

  private fun payload(i: Int, size: Int = 100): ByteArray = ByteArray(size) { (i * 31 + it).toByte() }

  private fun segmentFiles(index: String): List<Path> =
    Files.newDirectoryStream(root().resolve(index), "seg-*.dat").use { it.sortedBy { p -> p.fileName.toString() } }

  @Test fun `put then get returns payload and mapMicros`() {
    val store = open()
    store.put("a", key(1), 1234, payload(1))
    store.awaitIdle()
    val entry = store.get("a", key(1))!!
    assertArrayEquals(payload(1), entry.payload)
    assertEquals(1234, entry.mapMicros)
  }

  @Test fun `get of unknown key returns null`() {
    val store = open()
    assertNull(store.get("a", key(1)))
    assertNull(store.get("unknown", key(1)))
  }

  @Test fun `reopen keeps entries`() {
    val store = open()
    store.put("a", key(1), 5, payload(1))
    store.close()
    val again = open()
    assertArrayEquals(payload(1), again.get("a", key(1))!!.payload)
  }

  @Test fun `corrupt payload byte makes get return null and removes the key`() {
    val store = open()
    store.put("a", key(1), 5, payload(1))
    store.awaitIdle()
    RandomAccessFile(segmentFiles("a").single().toFile(), "rw").use {
      it.seek(Segment.RECORD_HEADER_BYTES + 3L)
      val b = it.read()
      it.seek(Segment.RECORD_HEADER_BYTES + 3L)
      it.write(b xor 0xFF)
    }
    assertNull(store.get("a", key(1)))
    assertNull(store.get("a", key(1)))
  }

  @Test fun `truncated tail record is ignored`() {
    val store = open()
    for (i in 0..10) store.put("a", key(i), i, payload(i))
    store.close()
    val file = segmentFiles("a").single()
    val record = Segment.RECORD_HEADER_BYTES + 100
    RandomAccessFile(file.toFile(), "rw").use { it.setLength(it.length() - record / 2) }
    val again = open()
    for (i in 0..9) assertArrayEquals(payload(i), again.get("a", key(i))!!.payload)
    assertNull(again.get("a", key(10)))
    again.put("a", key(10), 10, payload(10))
    again.awaitIdle()
    assertArrayEquals(payload(10), again.get("a", key(10))!!.payload)
  }

  @Test fun `header change clears that index only`() {
    val store = open(indexes = listOf("a", "b"))
    store.put("a", key(1), 1, payload(1))
    store.put("b", key(1), 1, payload(1))
    store.awaitIdle()
    store.ensureHeader("a", header.copy(indexVersion = 2))
    store.awaitIdle()
    assertNull(store.get("a", key(1)))
    assertNotNull(store.get("b", key(1)))
    store.close()
    val again = open(indexes = emptyList())
    again.ensureHeader("a", header.copy(indexVersion = 2))
    again.ensureHeader("b", header)
    again.awaitIdle()
    assertNull(again.get("a", key(1)))
    assertNotNull(again.get("b", key(1)))
  }

  @Test fun `reopening many times keeps one segment per index`() {
    var store = open()
    for (session in 0 until 6) {
      store.put("a", key(session), session, payload(session))
      store.close()
      store = open()
    }
    assertEquals(1, segmentFiles("a").size)
    for (session in 0 until 6) assertArrayEquals(payload(session), store.get("a", key(session))!!.payload)
  }

  @Test fun `header change is seen at once by gets in flight`() {
    val store = open()
    store.put("a", key(1), 1, payload(1))
    store.awaitIdle()
    val changed = java.util.concurrent.atomic.AtomicBoolean()
    val stop = java.util.concurrent.atomic.AtomicBoolean()
    val stale = AtomicReference<String?>()
    val reader = thread {
      while (!stop.get()) {
        val afterChange = changed.get()
        if (store.get("a", key(1)) != null && afterChange) stale.set("old payload served after ensureHeader returned")
      }
    }
    store.ensureHeader("a", header.copy(hashVersion = 2))
    changed.set(true)
    store.awaitIdle()
    stop.set(true)
    reader.join()
    assertNull(stale.get())
    assertNull(store.get("a", key(1)))
  }

  @Test fun `an interrupted reader does not remove valid keys`() {
    val store = open()
    for (i in 0 until 5) store.put("a", key(i), i, payload(i))
    store.awaitIdle()
    val results = AtomicReference<List<Boolean>>()
    val flagKept = AtomicReference<Boolean>()
    thread {
      Thread.currentThread().interrupt()
      results.set((0 until 5).map { store.get("a", key(it)) != null })
      flagKept.set(Thread.currentThread().isInterrupted)
    }.join()
    assertEquals(List(5) { true }, results.get())
    assertEquals(true, flagKept.get())
    for (i in 0 until 5) assertArrayEquals(payload(i), store.get("a", key(i))!!.payload)
  }

  @Test fun `remove wins over a put queued before it`() {
    val store = open()
    store.put("a", key(1), 1, payload(1))
    store.remove("a", key(1))
    store.awaitIdle()
    assertNull(store.get("a", key(1)))
  }

  @Test fun `a reused segment is as new as the session that writes to it`() {
    var store = open(maxBytes = 16 shl 10, segmentBytes = 4 shl 10, indexes = listOf("a", "b"))
    store.put("a", key(0), 0, payload(0, 1024))
    for (i in 0 until 12) store.put("b", key(i), 0, payload(i, 1024))
    store.close()
    store = open(maxBytes = 16 shl 10, segmentBytes = 4 shl 10, indexes = listOf("a", "b"))
    for (i in 1..2) store.put("a", key(i), 0, payload(i, 1024))
    store.awaitIdle()
    assertNotNull(store.get("a", key(0)))
    for (i in 100 until 106) store.put("b", key(i), 0, payload(i, 1024))
    store.awaitIdle()
    for (i in 0..2) assertArrayEquals("a$i", payload(i, 1024), store.get("a", key(i))?.payload)
    assertNull(store.get("b", key(0)))
    assertTrue(store.totalBytes() <= 16 shl 10)
  }

  @Test fun `exceeding max bytes evicts oldest segment and keeps recently read entries`() {
    val store = open(maxBytes = 16 shl 10, segmentBytes = 4 shl 10)
    for (batch in 0 until 8) {
      for (i in 0 until 5) store.put("a", key(batch * 5 + i), 0, payload(batch * 5 + i, 1024))
      store.awaitIdle()
      assertNotNull("k0 after batch $batch", store.get("a", key(0)))
    }
    store.awaitIdle()
    assertTrue(store.totalBytes().toString(), store.totalBytes() <= 16 shl 10)
    assertArrayEquals(payload(0, 1024), store.get("a", key(0))!!.payload)
    assertNull(store.get("a", key(1)))
    assertNotNull(store.get("a", key(39)))
  }

  @Test fun `clearAll removes everything and store stays usable`() {
    val store = open()
    store.put("a", key(1), 1, payload(1))
    store.awaitIdle()
    store.clearAll()
    assertEquals(0, store.totalBytes())
    assertNull(store.get("a", key(1)))
    store.put("a", key(2), 2, payload(2))
    store.awaitIdle()
    assertArrayEquals(payload(2), store.get("a", key(2))!!.payload)
  }

  @Test fun `clear right after open wipes an index the scan has not registered yet`() {
    val store = open(indexes = listOf("Stubs"))
    store.put("Stubs", key(1), 1, payload(1))
    store.put("Stubs", key(2), 2, payload(2))
    store.close()
    val again = LocalIndexStore.open(root(), 1L shl 30)!!
    opened += again
    again.clear("Stubs") // before awaitIdle and without ensureHeader
    assertNull(again.get("Stubs", key(1)))
    again.ensureHeader("Stubs", header)
    again.awaitIdle()
    assertNull(again.get("Stubs", key(1)))
    assertNull(again.get("Stubs", key(2)))
    assertEquals(0, segmentFiles("Stubs").size)
    again.put("Stubs", key(3), 3, payload(3))
    again.awaitIdle()
    assertArrayEquals(payload(3), again.get("Stubs", key(3))!!.payload)
  }

  @Test fun `header check queued before a clear does not leave the index validated`() {
    val store = open()
    store.ensureHeader("a", header)
    store.clear("a")
    store.awaitIdle()
    store.put("a", key(1), 1, payload(1))
    store.awaitIdle()
    assertNull("no ensureHeader after the clear, so nothing is served", store.get("a", key(1)))
    store.ensureHeader("a", header)
    store.put("a", key(2), 2, payload(2))
    store.awaitIdle()
    assertArrayEquals(payload(2), store.get("a", key(2))!!.payload)
  }

  @Test fun `second open of the same root returns null while the first is open`() {
    val store = open()
    assertNull(LocalIndexStore.open(root(), 1L shl 20))
    store.close()
    val again = LocalIndexStore.open(root(), 1L shl 20)
    assertNotNull(again)
    again!!.close()
  }

  @Test fun `concurrent puts and gets from 8 threads`() {
    val store = open(segmentBytes = 64 shl 10)
    runThreads(store, perThread = 500)
    store.awaitIdle()
    for (t in 0 until 8) for (n in 0 until 500) {
      val id = t * 1000 + n
      assertArrayEquals("key $id", payload(id, 200), store.get("a", key(id))!!.payload)
    }
  }

  @Test fun `concurrent gets never return a wrong payload while eviction deletes segments`() {
    val store = open(maxBytes = 200 shl 10, segmentBytes = 16 shl 10)
    runThreads(store, perThread = 500)
    store.awaitIdle()
    assertTrue(store.totalBytes() <= 200 shl 10)
  }

  /** Each thread puts its own keys and reads random keys of every thread; any hit must carry that key's payload. */
  private fun runThreads(store: LocalIndexStore, perThread: Int) {
    val failure = AtomicReference<Throwable?>()
    val workers = (0 until 8).map { t ->
      thread {
        try {
          val random = java.util.Random(t.toLong())
          for (n in 0 until perThread) {
            val id = t * 1000 + n
            store.put("a", key(id), id, payload(id, 200))
            val other = random.nextInt(8) * 1000 + random.nextInt(perThread)
            val entry = store.get("a", key(other))
            if (entry != null) {
              assertArrayEquals("key $other", payload(other, 200), entry.payload)
              assertEquals(other, entry.mapMicros)
            }
          }
        }
        catch (e: Throwable) {
          failure.compareAndSet(null, e)
        }
      }
    }
    workers.forEach { it.join() }
    failure.get()?.let { throw it }
  }
}
