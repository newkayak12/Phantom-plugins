// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.localsharedindex

import com.intellij.openapi.util.registry.Registry
import com.intellij.psi.impl.cache.impl.id.IdIndex
import com.intellij.psi.impl.cache.impl.id.IdIndexEntry
import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.DisposableRule
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.RunsInEdt
import com.intellij.testFramework.rules.TempDirectory
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.FileBasedIndexImpl
import com.intellij.util.indexing.FileBasedIndexTumbler
import com.intellij.util.indexing.FileContentImpl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@RunsInEdt
class LocalSharedIndexVerificationTest {
  @Rule @JvmField val tempDir = TempDirectory()

  @Rule @JvmField val disposable = DisposableRule()

  @Rule @JvmField val appRule = ApplicationRule()

  @Rule @JvmField val runInEdt = EdtRule()

  private val store get() = LocalSharedIndexExtension.getInstance().store!!

  @Before
  fun setUp() {
    (FileBasedIndex.getInstance() as FileBasedIndexImpl).waitUntilIndicesAreInitialized() // opens the store
    store.clearAll()
    Registry.get("phantom.localsharedindex.verify.one.in").setValue(1, disposable.disposable)
    Registry.get(LocalSharedIndexAllowlist.ALIAS_KEY).setValue(false, disposable.disposable) // these test the value cache
  }

  @After
  fun tearDown() {
    LocalSharedIndexStatistics.getInstance().stopTrace()
  }

  private fun open(name: String) = tempDir.newDirectory(name).toPath().let { root ->
    val files = CheckoutFixture.writeJavaCheckout(root)
    Triple(CheckoutFixture.openCheckout(root, disposable.disposable), root, files)
  }

  @Test
  fun `test verified hits log no error`() {
    open("a")
    store.awaitIdle()
    val trace = LocalSharedIndexStatistics.getInstance().startTrace()
    open("b") // a logged error fails the test
    val events = trace.filter { it.index == "Stubs" && "/src/" in it.path }
    assertEquals(events.toString(), 40, events.count { it.hit })
    assertEquals(20, events.map { it.path }.toSet().size)
    assertEquals(0, events.count { !it.hit })
  }

  @Test
  fun `test mismatching entry disables that index`() {
    val (a, rootA, _) = open("a")
    store.awaitIdle()
    val mainA = CheckoutFixture.virtualFile(rootA.resolve(CheckoutFixture.MAIN))
    val expected = FileBasedIndex.getInstance().getFileData(IdIndex.NAME, mainA, a).keys
    val extension = FileBasedIndexExtension.EXTENSION_POINT_NAME.extensionList.first { it.name == IdIndex.NAME }
    @Suppress("UNCHECKED_CAST")
    val codec = EntryCodec.of(extension as FileBasedIndexExtension<Any, Any>)
    val key = CacheKeys.keyFor(FileContentImpl.createByFile(mainA, a) as FileContentImpl, extension.indexer)
    store.put("IdIndex", key, 1, codec.encode(mapOf(IdIndexEntry("zzdifferent", true) to 1)))
    store.awaitIdle()
    val trace = LocalSharedIndexStatistics.getInstance().startTrace()
    try {
      val error = LoggedErrorProcessor.executeAndReturnLoggedError { open("b") }
      assertNotNull("a mismatch must be logged as an error", error)
      assertTrue(error.message!!, error.message!!.contains("IdIndex") && error.message!!.contains("Main.java"))
      val (b, rootB, _) = open("c")
      val mainB = CheckoutFixture.virtualFile(rootB.resolve(CheckoutFixture.MAIN))
      assertEquals(expected, FileBasedIndex.getInstance().getFileData(IdIndex.NAME, mainB, b).keys)
      // After the mismatch IdIndex is pass-through: the project opened later produces no IdIndex event at all.
      assertEquals(emptyList<TraceEvent>(), trace.filter { it.index == "IdIndex" && "/c/src/" in it.path })
      assertTrue(trace.any { it.index == "Stubs" && "/c/src/" in it.path })
    }
    finally {
      restart() // the disabled flag lives until the indexes are rebuilt
    }
  }

  @Test
  fun `test format line`() = assertEquals(
    "Phantom local shared index: demo: 190 of 200 index updates from cache (IdIndex 100/100, Stubs 90/100), about 2000 ms of indexing saved",
    formatLine("demo", mapOf("Stubs" to Counts(90, 10, 1_500_000), "IdIndex" to Counts(100, 0, 500_000))),
  )

  @Test
  fun `test platform diagnostics count files from cache`() {
    open("a")
    store.awaitIdle()
    open("b")
    val history = LocalSharedIndexHistoryListener.lastHistory!!
    assertTrue(history.totalStatsPerIndexer.keys.toString(), (history.totalStatsPerIndexer["Stubs"]!!.totalNumberOfFilesIndexedByExtensions) >= 20)
  }

  private fun restart() {
    FileBasedIndexTumbler("LocalSharedIndexVerificationTest").apply { turnOff(); turnOn() }
    (FileBasedIndex.getInstance() as FileBasedIndexImpl).waitUntilIndicesAreInitialized()
  }
}
