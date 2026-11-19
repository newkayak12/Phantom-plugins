// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.localindex

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.openapi.util.registry.Registry
import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.DisposableRule
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.RunsInEdt
import com.intellij.testFramework.rules.TempDirectory
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.FileContentImpl
import com.intellij.util.indexing.FileBasedIndexImpl
import com.intellij.psi.impl.cache.impl.id.IdIndex
import com.intellij.psi.impl.cache.impl.id.IdIndexEntry
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

@RunsInEdt
class LocalIndexReuseTest {
  @Rule @JvmField val tempDir = TempDirectory()

  @Rule @JvmField val disposable = DisposableRule()

  @Rule @JvmField val appRule = ApplicationRule()

  @Rule @JvmField val runInEdt = EdtRule()

  private val contentIndexes = listOf("IdIndex", "java.fun.expression", "java.null.method.argument", "java.binary.plus.expression")

  private val store get() = LocalIndexExtension.getInstance().store!!

  @Before
  fun setUp() {
    (FileBasedIndex.getInstance() as FileBasedIndexImpl).waitUntilIndicesAreInitialized() // opens the store
    store.clearAll()
    Registry.get("phantom.localindex.verify.one.in").setValue(1, disposable.disposable) // verify every hit
  }

  @After
  fun tearDown() {
    LocalIndexStatistics.getInstance().stopTrace()
  }

  private fun open(name: String): Triple<Project, java.nio.file.Path, List<String>> {
    val root = tempDir.newDirectory(name).toPath()
    val files = CheckoutFixture.writeJavaCheckout(root)
    return Triple(CheckoutFixture.openCheckout(root, disposable.disposable), root, files)
  }

  @Test
  fun `test second checkout reuses content index entries`() {
    val (a, rootA, files) = open("a")
    store.awaitIdle()
    val trace = LocalIndexStatistics.getInstance().startTrace()
    val (b, rootB, _) = open("b")
    for (id in contentIndexes) {
      val events = trace.filter { it.index == id && "/src/" in it.path }
      // With the cache neither serving nor storing, this platform maps every file of a second open project twice (40
      // misses per index in the same scenario), so 2 * 20 hits is the exact expectation; 3x or 4x would be a regression.
      assertEquals(id, 40, events.count { it.hit })
      assertEquals(id, 20, events.map { it.path }.toSet().size)
      assertEquals(id, 0, events.count { !it.hit })
    }
    val index = FileBasedIndex.getInstance()
    for (path in files) {
      val fileA = CheckoutFixture.virtualFile(rootA.resolve(path))
      val fileB = CheckoutFixture.virtualFile(rootB.resolve(path))
      assertEquals(path, index.getFileData(IdIndex.NAME, fileA, a), index.getFileData(IdIndex.NAME, fileB, b))
      assertTrue(path, index.getFileData(IdIndex.NAME, fileB, b).isNotEmpty())
    }
  }

  @Test
  fun `test changed file is the only miss`() {
    open("a")
    store.awaitIdle()
    val (b, rootB, _) = open("b")
    val trace = LocalIndexStatistics.getInstance().startTrace()
    val main = CheckoutFixture.virtualFile(rootB.resolve(CheckoutFixture.MAIN))
    WriteAction.runAndWait<Exception> { VfsUtil.saveText(main, "package p;\npublic class Main { int changed; }\n") }
    IndexingTestUtil.waitUntilIndexesAreReady(b)
    FileBasedIndex.getInstance().ensureUpToDate(IdIndex.NAME, b, null)
    val events = trace.filter { it.index == "IdIndex" }
    assertEquals(events.toString(), 1, events.size)
    assertTrue(events.single().path.endsWith("Main.java"))
    assertTrue(!events.single().hit)
  }

  @Test
  fun `test unsaved document update bypasses cache`() {
    open("a")
    store.awaitIdle()
    val (b, rootB, _) = open("b")
    store.awaitIdle()
    val main = CheckoutFixture.virtualFile(rootB.resolve(CheckoutFixture.MAIN))
    val bytes = store.totalBytes()
    val trace = LocalIndexStatistics.getInstance().startTrace()
    val document = FileDocumentManager.getInstance().getDocument(main)!!
    WriteCommandAction.runWriteCommandAction(b) { document.insertString(0, "// zzunsavedword\n") }
    PsiDocumentManager.getInstance(b).commitAllDocuments()
    FileBasedIndex.getInstance().ensureUpToDate(IdIndex.NAME, b, GlobalSearchScope.fileScope(b, main))
    assertEquals(emptyList<TraceEvent>(), trace.filter { it.path.endsWith("Main.java") })
    // The unsaved text did reach the index, only not through the cache.
    val scope = GlobalSearchScope.fileScope(b, main)
    assertTrue(FileBasedIndex.getInstance().getContainingFiles(IdIndex.NAME, IdIndexEntry("zzunsavedword", true), scope).contains(main))
    store.awaitIdle()
    assertEquals(bytes, store.totalBytes())
  }

  @Test
  fun `test deleted file passes through`() {
    open("a")
    store.awaitIdle()
    val (b, rootB, files) = open("b")
    val trace = LocalIndexStatistics.getInstance().startTrace()
    val deleted = CheckoutFixture.virtualFile(rootB.resolve("src/p/gen/C0.java"))
    WriteAction.runAndWait<Exception> { deleted.delete(this) }
    IndexingTestUtil.waitUntilIndexesAreReady(b)
    FileBasedIndex.getInstance().ensureUpToDate(IdIndex.NAME, b, null)
    assertEquals(emptyList<TraceEvent>(), trace.filter { it.path.endsWith("C0.java") })
    val other: VirtualFile = CheckoutFixture.virtualFile(rootB.resolve(files.first { it.endsWith("C1.java") }))
    assertTrue(FileBasedIndex.getInstance().getFileData(IdIndex.NAME, other, b).isNotEmpty())
  }

  @Test
  fun `test undecodable cache entry is a miss and is replaced`() {
    val (a, rootA, _) = open("a")
    store.awaitIdle()
    val mainA = CheckoutFixture.virtualFile(rootA.resolve(CheckoutFixture.MAIN))
    val extension = FileBasedIndexExtension.EXTENSION_POINT_NAME.extensionList.first { it.name == IdIndex.NAME }
    val key = CacheKeys.keyFor(FileContentImpl.createByFile(mainA, a) as FileContentImpl, extension.indexer)
    assertNotNull("the key must be the one the indexing run stored", store.get("IdIndex", key))
    // A varint count that is negative: the platform externalizer fails with a RuntimeException, not an IOException.
    val garbage = byteArrayOf(-1, -1, -1, -1, 0x0f, 0, 0, 0, 0, 0, 0, 0)
    store.put("IdIndex", key, 1, garbage)
    store.awaitIdle()
    assertTrue(garbage.contentEquals(store.get("IdIndex", key)?.payload))
    val trace = LocalIndexStatistics.getInstance().startTrace()
    val (b, rootB, _) = open("b")
    store.awaitIdle()
    val events = trace.filter { it.index == "IdIndex" && it.path.endsWith("Main.java") }
    assertTrue(events.toString(), events.isNotEmpty() && !events.first().hit)
    val mainB = CheckoutFixture.virtualFile(rootB.resolve(CheckoutFixture.MAIN))
    assertTrue(FileBasedIndex.getInstance().getFileData(IdIndex.NAME, mainB, b).isNotEmpty())
    // The garbage is gone: the miss stored the real entry under the same key.
    assertTrue(!garbage.contentEquals(store.get("IdIndex", key)?.payload))
  }
}
