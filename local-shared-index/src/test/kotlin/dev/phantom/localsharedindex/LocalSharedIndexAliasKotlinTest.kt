// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.localsharedindex

import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFileWithId
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StubIndex
import com.intellij.psi.stubs.StubIndexKey
import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.DisposableRule
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.RunsInEdt
import com.intellij.testFramework.rules.TempDirectory
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexImpl
import com.intellij.util.indexing.ID
import com.intellij.util.indexing.IdFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections

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

  @Test
  fun `test second checkout borrows kotlin stubs but never script stubs`() {
    val errors = Collections.synchronizedList(ArrayList<String>())
    LoggedErrorProcessor.executeWith(object : LoggedErrorProcessor() {
      override fun processError(category: String, message: String, details: Array<String>, t: Throwable?): Set<Action> {
        errors += "$category: $message"
        return Action.NONE
      }
    }).let { token -> Disposer.register(disposable.disposable) { token.finish() } }
    val rootA = tempDir.newDirectory("a").toPath()
    writeKotlinCheckout(rootA)
    CheckoutFixture.openCheckout(rootA, disposable.disposable)
    val rootB = tempDir.newDirectory("b").toPath()
    writeKotlinCheckout(rootB)
    val b = CheckoutFixture.openCheckout(rootB, disposable.disposable)
    val stubs = AliasRegistry.of(LocalSharedIndexAllowlist.STUBS)
    for (i in 0..2) {
      val fileA = CheckoutFixture.virtualFile(rootA.resolve("src/k/K$i.kt")) as VirtualFileWithId
      assertEquals(fileA.id, stubs.donorOf((CheckoutFixture.virtualFile(rootB.resolve("src/k/K$i.kt")) as VirtualFileWithId).id))
    }
    assertNull(stubs.donorOf((CheckoutFixture.virtualFile(rootB.resolve("src/k/script.kts")) as VirtualFileWithId).id))

    @Suppress("UNCHECKED_CAST")
    val classNames = ID.findByName<String, PsiElement>(CLASS_SHORT_NAMES) as StubIndexKey<String, PsiElement>
    val k0 = CheckoutFixture.virtualFile(rootB.resolve("src/k/K0.kt"))
    val found = ArrayList<PsiElement>()
    StubIndex.getInstance().processElements(classNames, "K0", b, GlobalSearchScope.projectScope(b), PsiElement::class.java) { found.add(it) }
    assertEquals(listOf(k0), found.map { it.containingFile.virtualFile })
    val psi = found.single().containingFile as PsiFileImpl
    assertNotNull(psi.stub)
    assertFalse(psi.isContentsLoaded)
    assertTrue("K2" in StubIndex.getInstance().getAllKeys(classNames, b))
    assertEquals(emptyList<String>(), errors)
  }

  private fun writeKotlinCheckout(root: Path) {
    for (i in 0..2) {
      val file = root.resolve("src/k/K$i.kt")
      Files.createDirectories(file.parent)
      Files.writeString(file, "package k\n\nclass K$i {\n  fun m$i(): Int = $i\n}\n")
    }
    Files.writeString(root.resolve("src/k/script.kts"), "val x = 1\n")
  }

  private companion object {
    const val CLASS_SHORT_NAMES = "org.jetbrains.kotlin.idea.stubindex.KotlinClassShortNameIndex"

    const val PARTIAL_PACKAGE_NAMES = "org.jetbrains.kotlin.idea.vfilefinder.KotlinPartialPackageNamesIndex"
  }
}
