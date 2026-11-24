// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.localsharedindex

import com.intellij.psi.stubs.StubTreeLoader
import com.intellij.psi.stubs.StubUpdatingIndex
import com.intellij.openapi.util.registry.Registry
import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.DisposableRule
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.RunsInEdt
import com.intellij.testFramework.rules.TempDirectory
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexImpl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs

/** Needs the Kotlin plugin; excluded in build.gradle.kts when the IDE does not bundle it. */
@RunsInEdt
class LocalSharedIndexStubsKotlinTest {
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
    Registry.get(LocalSharedIndexAllowlist.ALIAS_KEY).setValue(false, disposable.disposable) // this tests the value cache
  }

  @After
  fun tearDown() {
    LocalSharedIndexStatistics.getInstance().stopTrace()
  }

  private fun writeCheckout(root: Path): List<String> {
    val files = (0..4).associate { "src/k/K$it.kt" to "package k\n\nclass K$it {\n  fun m$it(): Int = $it\n}\n\nfun top$it() = K$it()\n" }
    for ((path, text) in files) {
      val file = root.resolve(path)
      Files.createDirectories(file.parent)
      Files.writeString(file, text)
    }
    // A script of the Kotlin file type: never served from the cache.
    Files.writeString(root.resolve("src/k/script.kts"), "val x = 1\n")
    return files.keys.toList()
  }

  @Test
  fun `test second checkout reuses kotlin stubs`() {
    val rootA = tempDir.newDirectory("a").toPath()
    val files = writeCheckout(rootA)
    val a = CheckoutFixture.openCheckout(rootA, disposable.disposable)
    store.awaitIdle()
    val trace = LocalSharedIndexStatistics.getInstance().startTrace()
    val rootB = tempDir.newDirectory("b").toPath()
    writeCheckout(rootB)
    val b = CheckoutFixture.openCheckout(rootB, disposable.disposable)
    val events = trace.filter { it.index == "Stubs" && it.path.endsWith(".kt") }
    // Unlike the Java files of LocalSharedIndexReuseTest (mapped twice in a second project), each Kotlin file is mapped once.
    assertEquals(events.toString(), 5, events.count { it.hit })
    assertEquals(5, events.map { it.path }.toSet().size)
    assertEquals(emptyList<TraceEvent>(), trace.filter { it.index == "Stubs" && it.path.endsWith(".kts") })
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
}
