// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.localsharedindex

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.psi.impl.java.stubs.index.JavaStubIndexKeys
import com.intellij.psi.stubs.StubUpdatingIndex
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexImpl
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.FileContentImpl
import com.intellij.util.indexing.ID
import com.intellij.util.indexing.SingleEntryFileBasedIndexExtension
import com.intellij.util.indexing.SingleEntryIndexer
import com.intellij.util.indexing.UpdatableIndex
import com.intellij.util.indexing.impl.MapReduceIndexMappingException
import com.intellij.util.io.EnumeratorStringDescriptor
import dev.phantom.localsharedindex.store.StoreHeader
import dev.phantom.localsharedindex.store.LocalSharedIndexStore
import com.intellij.util.indexing.IndexedHashesSupport
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.IndexInfrastructure
import java.lang.reflect.Proxy
import java.nio.file.Files

/** The stub path of [CachingIndex] against a stand-in base index whose indexer fails on demand. */
class CachingIndexStubsTest : LightJavaCodeInsightFixtureTestCase() {
  private var failure: Throwable? = null
  private var baseCleared = false
  private val name = "TestStubs"

  private val store get() = LocalSharedIndexExtension.getInstance().store!!

  private fun cachingIndex(): CachingIndex<Int, String, Any?> {
    val extension = object : SingleEntryFileBasedIndexExtension<String>() {
      override fun getName(): ID<Int, String> = ID.create("dev.phantom.localsharedindex.test.stubs")
      override fun getIndexer() = object : SingleEntryIndexer<String>(false) {
        override fun computeValue(inputData: FileContent): String? = failure?.let { throw it } ?: "value"
      }
      override fun getValueExternalizer() = EnumeratorStringDescriptor.INSTANCE
      override fun getVersion() = 1
      override fun getInputFilter() = FileBasedIndex.InputFilter { true }
    }
    @Suppress("UNCHECKED_CAST")
    val base = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(UpdatableIndex::class.java)) { _, method, _ ->
      when (method.name) {
        "getExtension" -> extension
        "clear" -> baseCleared = true
        else -> throw UnsupportedOperationException(method.name)
      }
    } as UpdatableIndex<Int, String, FileContent, Any?>
    val header = StoreHeader(LocalSharedIndexStore.FORMAT_VERSION, 1, IndexedHashesSupport.getVersion(), "", 0)
    return CachingIndex(base, name, EntryCodec.of(extension), store, stubs = true, header)
  }

  private fun thrownBy(block: () -> Unit): Throwable? = runCatching(block).exceptionOrNull()

  private fun javaContent(): FileContentImpl =
    FileContentImpl.createByFile(myFixture.addFileToProject("A.java", "class A {}").virtualFile, project) as FileContentImpl

  override fun setUp() {
    super.setUp()
    (FileBasedIndex.getInstance() as FileBasedIndexImpl).waitUntilIndicesAreInitialized() // opens the store
    store.clear(name) // entries of earlier tests of this class would be served
    store.ensureHeader(name, StoreHeader(LocalSharedIndexStore.FORMAT_VERSION, 1, IndexedHashesSupport.getVersion(), "", 0))
    store.awaitIdle()
  }

  fun `test a failing stub builder is a per-file mapping failure`() {
    val index = cachingIndex()
    val content = javaContent()
    val cause = IllegalStateException("broken stub builder")
    failure = cause
    val e = thrownBy { index.mapInputAndPrepareUpdate(1, content) }
    assertTrue(e.toString(), e is MapReduceIndexMappingException)
    assertSame(cause, e?.cause)
    store.awaitIdle()
    assertNull(store.get(name, CacheKeys.keyFor(content, index.baseIndex.extension.indexer)))
  }

  fun `test cancellation and mapping exceptions pass through unchanged`() {
    val index = cachingIndex()
    val content = javaContent()
    failure = ProcessCanceledException()
    assertTrue(thrownBy { index.mapInputAndPrepareUpdate(1, content) } is ProcessCanceledException)
    val mapping = MapReduceIndexMappingException(IllegalStateException(), javaClass)
    failure = mapping
    val e = thrownBy { index.mapInputAndPrepareUpdate(1, content) }
    assertTrue(e.toString(), e is MapReduceIndexMappingException)
    assertSame(mapping, e)
  }

  fun `test clear of the wrapped index empties its store entries`() {
    val index = cachingIndex()
    val key = CacheKeys.keyFor(javaContent(), index.baseIndex.extension.indexer)
    store.put(name, key, 1, byteArrayOf(1, 0))
    store.awaitIdle()
    assertNotNull(store.get(name, key))
    index.clear()
    assertTrue(baseCleared)
    assertNull(store.get(name, key))
    store.awaitIdle()
    store.put(name, key, 1, byteArrayOf(1, 0)) // the index keeps using the cache after the clear
    store.awaitIdle()
    assertNotNull(store.get(name, key))
  }

  fun `test resetting the persistent state of the stub index empties its store entries`() {
    store.ensureHeader("Stubs", StoreHeader(LocalSharedIndexStore.FORMAT_VERSION, 1, IndexedHashesSupport.getVersion(), "", 0))
    store.awaitIdle()
    val key = ByteArray(20) { it.toByte() }
    store.put("Stubs", key, 1, byteArrayOf(1, 0))
    store.awaitIdle()
    assertNotNull(store.get("Stubs", key))
    try {
      LocalSharedIndexExtension.getInstance().resetPersistentState(StubUpdatingIndex.INDEX_ID)
      assertNull(store.get("Stubs", key))
    }
    finally {
      // The platform re-wraps the stub index after a reset, which writes its header again; nothing does here, and Stubs
      // would stay uncached for the tests that follow in this JVM. A stub index version change does the same.
      LocalSharedIndexExtension.getInstance().onStubIndexVersionChanged(JavaStubIndexKeys.CLASS_SHORT_NAMES)
      store.awaitIdle()
    }
  }

  fun `test a rewritten stubs version file changes the stub header and empties the Stubs entries`() {
    val stubs = FileBasedIndexExtension.EXTENSION_POINT_NAME.extensionList.first { it.name.name == "Stubs" }
    val versionFile = IndexInfrastructure.getVersionFile(StubUpdatingIndex.INDEX_ID)
    val original = if (Files.exists(versionFile)) Files.readAllBytes(versionFile) else null
    val key = ByteArray(20) { it.toByte() }
    try {
      val before = headerFor(stubs, null)
      store.ensureHeader("Stubs", before)
      store.awaitIdle()
      store.put("Stubs", key, 1, byteArrayOf(1, 0))
      store.awaitIdle()
      assertNotNull(store.get("Stubs", key))
      Files.write(versionFile, (original ?: ByteArray(0)) + byteArrayOf(42)) // what a Stubs rebuild does
      val after = headerFor(stubs, null)
      assertFalse(before == after)
      store.ensureHeader("Stubs", after)
      store.awaitIdle()
      assertNull(store.get("Stubs", key))
    }
    finally {
      if (original != null) Files.write(versionFile, original) else Files.deleteIfExists(versionFile)
      LocalSharedIndexExtension.getInstance().onStubIndexVersionChanged(JavaStubIndexKeys.CLASS_SHORT_NAMES)
      store.awaitIdle()
    }
  }
}
