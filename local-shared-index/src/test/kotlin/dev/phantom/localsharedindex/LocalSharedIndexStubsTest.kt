// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.localsharedindex

import com.intellij.openapi.project.Project
import com.intellij.pom.java.LanguageLevel
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StubTreeLoader
import com.intellij.psi.stubs.StubUpdatingIndex
import com.intellij.psi.impl.java.stubs.index.JavaStubIndexKeys
import com.intellij.openapi.util.registry.Registry
import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.DisposableRule
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.RunsInEdt
import com.intellij.testFramework.rules.TempDirectory
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.FileBasedIndexImpl
import com.intellij.util.indexing.FileContentImpl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.nio.file.Path
import kotlin.math.abs

@RunsInEdt
class LocalSharedIndexStubsTest {
  @Rule @JvmField val tempDir = TempDirectory()

  @Rule @JvmField val disposable = DisposableRule()

  @Rule @JvmField val appRule = ApplicationRule()

  @Rule @JvmField val runInEdt = EdtRule()

  private val store get() = LocalSharedIndexExtension.getInstance().store!!

  @Before
  fun setUp() {
    (FileBasedIndex.getInstance() as FileBasedIndexImpl).waitUntilIndicesAreInitialized() // opens the store
    store.clearAll()
    Registry.get("phantom.localsharedindex.verify.one.in").setValue(1, disposable.disposable) // verify every hit
    Registry.get(LocalSharedIndexAllowlist.ALIAS_KEY).setValue(false, disposable.disposable) // these test the value cache
  }

  @After
  fun tearDown() {
    LocalSharedIndexStatistics.getInstance().stopTrace()
  }

  private fun open(name: String, level: LanguageLevel? = null): Triple<Project, Path, List<String>> {
    val root = tempDir.newDirectory(name).toPath()
    val files = CheckoutFixture.writeJavaCheckout(root)
    return Triple(CheckoutFixture.openCheckout(root, disposable.disposable, level), root, files)
  }

  private fun stubEvents(trace: List<TraceEvent>) = trace.filter { it.index == "Stubs" && "/src/" in it.path }

  @Test
  fun `test second checkout reuses java stubs`() {
    val (a, rootA, files) = open("a")
    store.awaitIdle()
    val trace = LocalSharedIndexStatistics.getInstance().startTrace()
    val (b, rootB, _) = open("b")
    val events = stubEvents(trace)
    // The platform maps every file of a second open project twice (see LocalSharedIndexReuseTest), so 2 * 20 hits.
    assertEquals(events.toString(), 40, events.count { it.hit })
    assertEquals(20, events.map { it.path }.toSet().size)
    assertEquals(0, events.count { !it.hit })
    val index = FileBasedIndex.getInstance()
    for (path in files) {
      val fileA = CheckoutFixture.virtualFile(rootA.resolve(path))
      val fileB = CheckoutFixture.virtualFile(rootB.resolve(path))
      val dataA = index.getFileData(StubUpdatingIndex.INDEX_ID, fileA, a)
      val dataB = index.getFileData(StubUpdatingIndex.INDEX_ID, fileB, b)
      assertEquals(path, setOf(abs(FileBasedIndex.getFileId(fileB))), dataB.keys)
      assertEquals(path, dataA.values.single(), dataB.values.single())
      assertNotNull(path, StubTreeLoader.getInstance().readFromVFile(b, fileB))
    }
  }

  @Test
  fun `test class lookup in second checkout finds its own file`() {
    open("a")
    store.awaitIdle()
    val (b, rootB, _) = open("b")
    val cls = JavaPsiFacade.getInstance(b).findClass("p.Greeter", GlobalSearchScope.projectScope(b))!!
    assertTrue(cls.containingFile.virtualFile.path, cls.containingFile.virtualFile.path.startsWith(rootB.toString()))
  }

  @Test
  fun `test different language level is not served from cache`() {
    open("a")
    store.awaitIdle()
    val trace = LocalSharedIndexStatistics.getInstance().startTrace()
    open("b", LanguageLevel.JDK_1_8)
    val events = stubEvents(trace)
    // The first mapping of a file may run before the module gets its level (a hit on the default level is correct
    // then); what must never happen is that the mapping at JDK_1_8 is served from the default-level entry.
    val byPath = events.groupBy { it.path }
    assertEquals(20, byPath.size)
    for ((path, list) in byPath) assertTrue("$path: $list", !list.last().hit)
  }

  @Test
  fun `test stub index version change empties stub entries`() {
    val (a, rootA, _) = open("a")
    store.awaitIdle()
    val main = CheckoutFixture.virtualFile(rootA.resolve(CheckoutFixture.MAIN))
    val extension = FileBasedIndexExtension.EXTENSION_POINT_NAME.extensionList.first { it.name == StubUpdatingIndex.INDEX_ID }
    val key = CacheKeys.keyFor(FileContentImpl.createByFile(main, a) as FileContentImpl, extension.indexer)
    assertNotNull("the key must be the one the indexing run stored", store.get("Stubs", key))
    LocalSharedIndexExtension.getInstance().onStubIndexVersionChanged(JavaStubIndexKeys.CLASS_SHORT_NAMES)
    store.awaitIdle()
    assertNull(store.get("Stubs", key))
  }
}
