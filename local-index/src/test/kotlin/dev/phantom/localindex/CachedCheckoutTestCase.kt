// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.localindex

import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.util.io.FileUtil
import com.intellij.pom.java.LanguageLevel
import com.intellij.testFramework.IdeaTestUtil
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.JavaCodeInsightFixtureTestCase
import com.intellij.testFramework.builders.JavaModuleFixtureBuilder
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexImpl
import java.nio.file.Files
import java.nio.file.Path

/**
 * The fixture project is the second checkout: a first project (an extra open project in a temp dir) seeds the cache,
 * then the same files are written into the fixture's temp dir, so its index data comes from the cache.
 */
abstract class CachedCheckoutTestCase : JavaCodeInsightFixtureTestCase() {
  private lateinit var seedDir: Path
  protected lateinit var trace: List<TraceEvent>

  protected val store get() = LocalIndexExtension.getInstance().store!!

  /** Writes the checkout's files below [root] (the same files for both checkouts). */
  protected abstract fun writeCheckout(root: Path)

  /** The language level is part of the indexed-file hash of Java files, so both checkouts must use the same one. */
  private val level = LanguageLevel.JDK_17

  override fun tuneFixture(moduleBuilder: JavaModuleFixtureBuilder<*>) {
    moduleBuilder.setLanguageLevel(level)
    moduleBuilder.addJdk(System.getProperty("java.home"))
  }

  override fun setUp() {
    super.setUp()
    (FileBasedIndex.getInstance() as FileBasedIndexImpl).waitUntilIndicesAreInitialized() // opens the store
    store.clearAll()
    Registry.get("phantom.localindex.verify.one.in").setValue(1, testRootDisposable) // verify every hit
    seedDir = Files.createTempDirectory("local-index-seed")
    writeCheckout(seedDir)
    CheckoutFixture.openCheckout(seedDir, testRootDisposable, level)
    store.awaitIdle()
    val statistics = LocalIndexStatistics.getInstance()
    val events = statistics.startTrace()
    val root = Path.of(myFixture.tempDirPath)
    writeCheckout(root)
    PsiTestUtil.addSourceRoot(module, CheckoutFixture.virtualFile(root.resolve("src")))
    IndexingTestUtil.waitUntilIndexesAreReady(project)
    trace = events.filter { it.path.startsWith(myFixture.tempDirPath) }
  }

  override fun tearDown() {
    try {
      LocalIndexStatistics.getInstance().stopTrace()
    } finally {
      try {
        super.tearDown()
      } finally {
        if (::seedDir.isInitialized) FileUtil.delete(seedDir.toFile())
      }
    }
  }
}
