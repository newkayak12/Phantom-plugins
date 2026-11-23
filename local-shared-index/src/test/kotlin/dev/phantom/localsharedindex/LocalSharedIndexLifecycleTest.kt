// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.localsharedindex

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.options.advanced.AdvancedSettings
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.psi.impl.cache.impl.id.IdIndex
import com.intellij.psi.impl.java.stubs.index.JavaStubIndexKeys
import com.intellij.psi.stubs.StubUpdatingIndex
import com.intellij.openapi.util.registry.Registry
import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.DisposableRule
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.RunsInEdt
import com.intellij.testFramework.rules.TempDirectory
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.FileBasedIndexImpl
import com.intellij.util.indexing.FileBasedIndexTumbler
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.FileContentImpl
import com.intellij.util.indexing.ID
import com.intellij.util.indexing.UpdatableIndex
import com.intellij.util.indexing.FileBasedIndexInfrastructureExtension.InitializationResult
import dev.phantom.localsharedindex.store.LocalSharedIndexStore
import dev.phantom.localsharedindex.store.StoreHeaderFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path

@RunsInEdt
class LocalSharedIndexLifecycleTest {
  @Rule @JvmField val tempDir = TempDirectory()

  @Rule @JvmField val disposable = DisposableRule()

  @Rule @JvmField val appRule = ApplicationRule()

  @Rule @JvmField val runInEdt = EdtRule()

  private val ext get() = LocalSharedIndexExtension.getInstance()

  private val store get() = ext.store!!

  private val storeRoot: Path get() = PathManager.getIndexRoot().resolve(LocalSharedIndexExtension.ROOT)

  private val contentIndexes = listOf("IdIndex", "java.fun.expression", "java.null.method.argument", "java.binary.plus.expression")

  private var sizeSetting = 0

  @Before
  fun setUp() {
    sizeSetting = AdvancedSettings.getInt(LocalSharedIndexExtension.MAX_SIZE_SETTING)
    (FileBasedIndex.getInstance() as FileBasedIndexImpl).waitUntilIndicesAreInitialized() // opens the store
    store.clearAll()
    Registry.get("phantom.localsharedindex.verify.one.in").setValue(1, disposable.disposable) // verify every hit
    Registry.get(LocalSharedIndexAllowlist.ALIAS_KEY).setValue(false, disposable.disposable) // these test the value cache
  }

  @After
  fun tearDown() {
    LocalSharedIndexStatistics.getInstance().stopTrace()
    if (AdvancedSettings.getInt(LocalSharedIndexExtension.MAX_SIZE_SETTING) != sizeSetting) {
      AdvancedSettings.setInt(LocalSharedIndexExtension.MAX_SIZE_SETTING, sizeSetting)
      restart {}
    }
  }

  private fun open(name: String) = tempDir.newDirectory(name).toPath().let { root ->
    val files = CheckoutFixture.writeJavaCheckout(root)
    Triple(CheckoutFixture.openCheckout(root, disposable.disposable), root, files)
  }

  /** Shuts the extension down, runs [whileOff] with the store closed, then brings the extension and its indexes back. */
  private fun restart(whileOff: () -> Unit) {
    val tumbler = FileBasedIndexTumbler("LocalSharedIndexLifecycleTest")
    tumbler.turnOff()
    try {
      whileOff()
    }
    finally {
      tumbler.turnOn()
    }
    (FileBasedIndex.getInstance() as FileBasedIndexImpl).waitUntilIndicesAreInitialized()
  }

  private fun extensionOf(id: ID<*, *>) = FileBasedIndexExtension.EXTENSION_POINT_NAME.extensionList.first { it.name == id }

  private fun keyOf(id: ID<*, *>, root: Path, path: String): ByteArray {
    val project = com.intellij.openapi.project.ProjectManager.getInstance().openProjects.first()
    val file = CheckoutFixture.virtualFile(root.resolve(path))
    return CacheKeys.keyFor(FileContentImpl.createByFile(file, project) as FileContentImpl, extensionOf(id).indexer)
  }

  /**
   * The platform maps a file of a second open project twice, so the entry the first mapping stores is a hit for the
   * second one. What a wiped or unreadable cache must never do is serve the first mapping of a file.
   */
  private fun assertFirstMappingsMiss(events: List<TraceEvent>, expectedFiles: Int) {
    val first = events.groupBy { it.path }.mapValues { it.value.first() }
    assertEquals(events.toString(), expectedFiles, first.size)
    assertEquals(first.toString(), 0, first.values.count { it.hit })
  }

  @Test
  fun `test reset persistent state empties the store`() {
    open("a")
    store.awaitIdle()
    assertTrue(store.totalBytes() > 0)
    ext.resetPersistentState()
    assertEquals(0, store.totalBytes())
    val trace = LocalSharedIndexStatistics.getInstance().startTrace()
    open("b")
    for (id in contentIndexes) assertFirstMappingsMiss(trace.filter { it.index == id && "/b/src/" in it.path }, 20)
  }

  @Test
  fun `test index version change drops only that index`() {
    val (_, root, _) = open("a")
    store.awaitIdle()
    val idKey = keyOf(IdIndex.NAME, root, CheckoutFixture.MAIN)
    val stubKey = keyOf(StubUpdatingIndex.INDEX_ID, root, CheckoutFixture.MAIN)
    assertNotNull(store.get("IdIndex", idKey))
    assertNotNull(store.get("Stubs", stubKey))
    try {
      ext.onFileBasedIndexVersionChanged(IdIndex.NAME)
      store.awaitIdle()
      assertNull(store.get("IdIndex", idKey))
      assertNotNull(store.get("Stubs", stubKey))
    }
    finally {
      restart {} // the platform re-wraps the index after a version change; do the same for the next test
    }
  }

  @Test
  fun `test stub version change keeps stubs cacheable`() {
    val (_, root, _) = open("a")
    store.awaitIdle()
    val stubKey = keyOf(StubUpdatingIndex.INDEX_ID, root, CheckoutFixture.MAIN)
    assertNotNull(store.get("Stubs", stubKey))
    ext.onStubIndexVersionChanged(JavaStubIndexKeys.CLASS_SHORT_NAMES)
    store.awaitIdle()
    assertNull(store.get("Stubs", stubKey))
    store.put("Stubs", stubKey, 1, byteArrayOf(1, 0))
    store.awaitIdle()
    assertNotNull("Stubs must cache again after the clear", store.get("Stubs", stubKey))
  }

  @Test
  fun `test corrupt store recovers after indexes restart`() {
    val (a, rootA, files) = open("a")
    store.awaitIdle()
    val expected = files.map { FileBasedIndex.getInstance().getFileData(IdIndex.NAME, CheckoutFixture.virtualFile(rootA.resolve(it)), a) }
    ProjectManagerEx.getInstanceEx().forceCloseProject(a)
    restart {
      val segments = Files.newDirectoryStream(storeRoot.resolve("IdIndex"), "seg-*.dat").use { it.toList() }
      assertTrue(segments.isNotEmpty())
      for (segment in segments) Files.write(segment, Files.readAllBytes(segment).map { (it.toInt() xor 0xFF).toByte() }.toByteArray())
    }
    val trace = LocalSharedIndexStatistics.getInstance().startTrace()
    val (b, rootB, _) = open("b")
    val events = trace.filter { it.index == "IdIndex" && "/b/src/" in it.path }
    assertFirstMappingsMiss(events, 20)
    val actual = files.map { FileBasedIndex.getInstance().getFileData(IdIndex.NAME, CheckoutFixture.virtualFile(rootB.resolve(it)), b) }
    assertEquals(expected, actual)
  }

  @Test
  fun `test format mismatch clears store and needs no rebuild`() {
    val (a, _, _) = open("a")
    store.awaitIdle()
    assertTrue(store.totalBytes() > 0)
    ProjectManagerEx.getInstanceEx().forceCloseProject(a)
    var result: InitializationResult? = null
    val tumbler = FileBasedIndexTumbler("LocalSharedIndexLifecycleTest")
    tumbler.turnOff()
    try {
      Files.newDirectoryStream(storeRoot).use { dirs ->
        for (dir in dirs.filter { Files.isRegularFile(it.resolve(StoreHeaderFile.NAME)) }) {
          val file = dir.resolve(StoreHeaderFile.NAME)
          StoreHeaderFile.write(file, StoreHeaderFile.read(file)!!.let { it.copy(formatVersion = LocalSharedIndexStore.FORMAT_VERSION + 1) })
        }
      }
      result = ext.initialize(null)
      ext.shutdown()
    }
    finally {
      tumbler.turnOn()
    }
    assertEquals(InitializationResult.SUCCESSFULLY, result)
    (FileBasedIndex.getInstance() as FileBasedIndexImpl).waitUntilIndicesAreInitialized()
    store.awaitIdle()
    assertEquals(0, store.totalBytes())
  }

  @Test
  fun `test locked store disables the cache`() {
    var holder: LocalSharedIndexStore? = null
    try {
      restart {
        holder = LocalSharedIndexStore.open(storeRoot, 1L shl 20)
        assertNotNull(holder)
      }
      assertNull(ext.store)
      @Suppress("UNCHECKED_CAST")
      val base = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(UpdatableIndex::class.java)) { _, _, _ -> null } as UpdatableIndex<Any, Any, FileContent, *>
      @Suppress("UNCHECKED_CAST")
      val idIndex = extensionOf(IdIndex.NAME) as FileBasedIndexExtension<Any, Any>
      assertNull(ext.combineIndex(idIndex, base))
    }
    finally {
      holder?.close()
      restart {}
    }
    assertNotNull(ext.store)
  }

  @Test
  fun `test size setting is applied`() {
    AdvancedSettings.setInt(LocalSharedIndexExtension.MAX_SIZE_SETTING, 1)
    restart {}
    store.ensureHeader("IdIndex", dev.phantom.localsharedindex.store.StoreHeader(LocalSharedIndexStore.FORMAT_VERSION, 1, 1, "", 0))
    val key = ByteArray(20)
    for (i in 0 until 40) {
      key[0] = i.toByte()
      store.put("IdIndex", key, 1, ByteArray(64 shl 10) { (it + i).toByte() })
    }
    store.awaitIdle()
    assertTrue(store.totalBytes().toString(), store.totalBytes() <= 1L shl 20)
  }
}
