// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.localsharedindex

import com.intellij.openapi.vfs.VirtualFileWithId
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.DisposableRule
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.RunsInEdt
import com.intellij.testFramework.rules.TempDirectory
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexImpl
import com.intellij.util.indexing.ID
import com.intellij.util.indexing.IdFilter
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/** Needs the Kotlin plugin; excluded in build.gradle.kts when the IDE does not bundle it. */
@RunsInEdt
class LocalSharedIndexAliasKotlinTest {
  @Rule @JvmField val tempDir = TempDirectory()

  @Rule @JvmField val disposable = DisposableRule()

  @Rule @JvmField val appRule = ApplicationRule()

  @Rule @JvmField val runInEdt = EdtRule()

  @Before
  fun setUp() {
    (FileBasedIndex.getInstance() as FileBasedIndexImpl).waitUntilIndicesAreInitialized()
    LocalSharedIndexExtension.getInstance().store!!.clearAll()
    AliasRegistry.clearAll()
    GitBlobs.clear()
  }

  private fun writeCheckout(root: Path): Path {
    val file = root.resolve("src/zzk/sub/K.kt")
    Files.createDirectories(file.parent)
    Files.writeString(file, "package zzk.sub\n\nclass K\n")
    return file
  }

  @Test
  fun `test keys only the donor has are listed for the alias's project`() {
    val rootA = tempDir.newDirectory("a").toPath()
    writeCheckout(rootA)
    CheckoutFixture.openCheckout(rootA, disposable.disposable)
    val rootB = tempDir.newDirectory("b").toPath()
    val fileB = CheckoutFixture.virtualFile(writeCheckout(rootB))
    val b = CheckoutFixture.openCheckout(rootB, disposable.disposable)
    val index = ID.findByName<Any, Any>(PARTIAL_PACKAGE_NAMES)!!
    assertNotNull(AliasRegistry.of(PARTIAL_PACKAGE_NAMES).donorOf((fileB as VirtualFileWithId).id))
    // The index traces key hashes per file: with b's own filter, keys logged only for a's file were dropped before.
    val keys = HashSet<String>()
    FileBasedIndex.getInstance().processAllKeys(index, { keys.add(it.toString()) }, GlobalSearchScope.projectScope(b), IdFilter.getProjectIdFilter(b, false))
    assertTrue(keys.toString(), "zzk.sub" in keys)
  }

  private companion object {
    const val PARTIAL_PACKAGE_NAMES = "org.jetbrains.kotlin.idea.vfilefinder.KotlinPartialPackageNamesIndex"
  }
}
