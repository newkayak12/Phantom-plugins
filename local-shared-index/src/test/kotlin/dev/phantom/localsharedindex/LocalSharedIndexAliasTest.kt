// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.localsharedindex

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileWithId
import com.intellij.psi.impl.cache.impl.id.IdIndex
import com.intellij.psi.impl.cache.impl.id.IdIndexEntry
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.DisposableRule
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.RunsInEdt
import com.intellij.testFramework.rules.TempDirectory
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexImpl
import com.intellij.util.indexing.IndexedFileImpl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

@RunsInEdt
class LocalSharedIndexAliasTest {
  @Rule @JvmField val tempDir = TempDirectory()

  @Rule @JvmField val disposable = DisposableRule()

  @Rule @JvmField val appRule = ApplicationRule()

  @Rule @JvmField val runInEdt = EdtRule()

  private val store get() = LocalSharedIndexExtension.getInstance().store!!

  @Before
  fun setUp() {
    (FileBasedIndex.getInstance() as FileBasedIndexImpl).waitUntilIndicesAreInitialized()
    store.clearAll()
    AliasRegistry.clearAll()
    GitBlobs.clear()
  }

  @After
  fun tearDown() {
    LocalSharedIndexStatistics.getInstance().stopTrace()
  }

  private fun open(name: String): Pair<Project, Path> {
    val root = tempDir.newDirectory(name).toPath()
    CheckoutFixture.writeJavaCheckout(root)
    return CheckoutFixture.openCheckout(root, disposable.disposable) to root
  }

  private fun id(file: VirtualFile) = (file as VirtualFileWithId).id

  private fun containing(project: Project, word: String, file: VirtualFile): Boolean =
    FileBasedIndex.getInstance().getContainingFiles(IdIndex.NAME, IdIndexEntry(word, true), GlobalSearchScope.fileScope(project, file)).contains(file)

  @Test
  fun `test second checkout is skipped and answered from the donor`() {
    val (a, rootA) = open("a")
    val trace = LocalSharedIndexStatistics.getInstance().startTrace()
    val (b, rootB) = open("b")
    // Every source file of b borrows from a; none was mapped (the test's files arrive through VFS events, not scanning).
    val bEvents = trace.filter { it.index == "IdIndex" && "/b/" in it.path && "/src/" in it.path }
    val paths = CheckoutFixture.writeJavaCheckout(tempDir.newDirectory("paths").toPath())
    val aliasedB = paths.count { p ->
      AliasRegistry.of("IdIndex").donorOf(id(CheckoutFixture.virtualFile(rootB.resolve(p)))) != null
    }
    assertEquals(20, aliasedB)
    assertTrue(bEvents.toString(), bEvents.all { it.hit })
    val mainA = CheckoutFixture.virtualFile(rootA.resolve(CheckoutFixture.MAIN))
    val mainB = CheckoutFixture.virtualFile(rootB.resolve(CheckoutFixture.MAIN))
    assertEquals(id(mainA), AliasRegistry.of("IdIndex").donorOf(id(mainB)))
    assertTrue(containing(b, "greet", mainB))
    assertTrue(containing(a, "greet", mainA))
    val index = FileBasedIndex.getInstance()
    assertEquals(index.getFileData(IdIndex.NAME, mainA, a), index.getFileData(IdIndex.NAME, mainB, b))
  }

  @Test
  fun `test a clean git checkout finds its donor by blob id`() {
    // A plain-text file: a Java file outside any project has no pushed language level, which is part of its key.
    val text = "zzblobword appears in both checkouts\n"
    val rootA = tempDir.newDirectory("a").toPath()
    CheckoutFixture.writeJavaCheckout(rootA)
    val notesA = GitBlobsTest.committed(rootA, "notes.txt", text)
    val a = CheckoutFixture.openCheckout(rootA, disposable.disposable)
    val rootB = tempDir.newDirectory("b").toPath()
    val notesB = CheckoutFixture.virtualFile(GitBlobsTest.committed(rootB, "notes.txt", text))
    val aliases = AliasRegistry.of("IdIndex")
    assertTrue(aliases.hasBlobDonors)
    val skipped = LocalSharedIndexStatusProcessor(a).tryIndexFileWithoutContent(IndexedFileImpl(notesB, a), id(notesB), IdIndex.NAME)
    assertTrue(skipped)
    assertEquals(id(CheckoutFixture.virtualFile(notesA)), aliases.donorOf(id(notesB)))
  }

  @Test
  fun `test changed donor leaves the alias with its own content`() {
    val (a, rootA) = open("a")
    val (b, rootB) = open("b")
    val mainA = CheckoutFixture.virtualFile(rootA.resolve(CheckoutFixture.MAIN))
    val mainB = CheckoutFixture.virtualFile(rootB.resolve(CheckoutFixture.MAIN))
    WriteAction.runAndWait<Exception> { VfsUtil.saveText(mainA, "package p;\npublic class Main { int zzdonoronly; }\n") }
    IndexingTestUtil.waitUntilIndexesAreReady(a)
    IndexingTestUtil.waitUntilIndexesAreReady(b)
    FileBasedIndex.getInstance().ensureUpToDate(IdIndex.NAME, b, null)
    assertTrue(containing(a, "zzdonoronly", mainA))
    assertFalse(containing(b, "zzdonoronly", mainB))
    assertTrue(containing(b, "greet", mainB))
  }

  @Test
  fun `test changed alias is indexed on its own`() {
    val (a, rootA) = open("a")
    val (b, rootB) = open("b")
    val mainA = CheckoutFixture.virtualFile(rootA.resolve(CheckoutFixture.MAIN))
    val mainB = CheckoutFixture.virtualFile(rootB.resolve(CheckoutFixture.MAIN))
    WriteAction.runAndWait<Exception> { VfsUtil.saveText(mainB, "package p;\npublic class Main { int zzaliasonly; }\n") }
    IndexingTestUtil.waitUntilIndexesAreReady(b)
    FileBasedIndex.getInstance().ensureUpToDate(IdIndex.NAME, b, null)
    assertTrue(containing(b, "zzaliasonly", mainB))
    assertFalse(containing(b, "greet", mainB))
    assertTrue(containing(a, "greet", mainA))
    assertEquals(null, AliasRegistry.of("IdIndex").donorOf(id(mainB)))
  }
}
