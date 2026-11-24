// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.localsharedindex

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileWithId
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.impl.java.stubs.index.JavaStubIndexKeys
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StubIndex
import com.intellij.psi.stubs.StubTreeLoader
import com.intellij.psi.stubs.StubUpdatingIndex
import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.DisposableRule
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.RunsInEdt
import com.intellij.testFramework.rules.TempDirectory
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexImpl
import com.intellij.util.indexing.IndexedFileImpl
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.FileContentImpl
import com.intellij.psi.impl.cache.impl.id.IdIndex
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.nio.file.Path
import java.util.Collections

@RunsInEdt
class LocalSharedIndexStubsAliasTest {
  @Rule @JvmField val tempDir = TempDirectory()

  @Rule @JvmField val disposable = DisposableRule()

  @Rule @JvmField val appRule = ApplicationRule()

  @Rule @JvmField val runInEdt = EdtRule()

  // LOG.error from any thread, including indexing ("Outdated stub in index", "Stub ids not found").
  private val errors = Collections.synchronizedList(ArrayList<String>())

  @Before
  fun setUp() {
    (FileBasedIndex.getInstance() as FileBasedIndexImpl).waitUntilIndicesAreInitialized()
    LocalSharedIndexExtension.getInstance().store!!.clearAll()
    AliasRegistry.clearAll()
    GitBlobs.clear()
    LoggedErrorProcessor.executeWith(object : LoggedErrorProcessor() {
      override fun processError(category: String, message: String, details: Array<String>, t: Throwable?): Set<Action> {
        errors += "$category: $message"
        return Action.NONE
      }
    }).let { token -> Disposer.register(disposable.disposable) { token.finish() } }
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

  private fun greeter(root: Path) = CheckoutFixture.virtualFile(root.resolve("src/p/Greeter.java"))

  private fun classFile(project: Project, fqn: String): VirtualFile? =
    JavaPsiFacade.getInstance(project).findClass(fqn, GlobalSearchScope.projectScope(project))?.containingFile?.virtualFile

  @Test
  fun `test second checkout borrows java stubs and finds its own classes`() {
    val (_, rootA) = open("a")
    val trace = LocalSharedIndexStatistics.getInstance().startTrace()
    val (b, rootB) = open("b")
    val paths = CheckoutFixture.writeJavaCheckout(tempDir.newDirectory("paths").toPath())
    for (path in paths) {
      val fileB = CheckoutFixture.virtualFile(rootB.resolve(path))
      assertEquals(path, id(CheckoutFixture.virtualFile(rootA.resolve(path))), AliasRegistry.of("Stubs").donorOf(id(fileB)))
    }
    // Scanning skips the files without an event, indexing records a hit without mapping: a miss means the stub builder ran.
    val events = trace.filter { it.index == "Stubs" && "/b/" in it.path && "/src/" in it.path }
    assertTrue(events.toString(), events.all { it.hit })

    val greeterB = greeter(rootB)
    assertEquals(greeterB, classFile(b, "p.Greeter"))
    val psi = JavaPsiFacade.getInstance(b).findClass("p.Greeter", GlobalSearchScope.projectScope(b))!!.containingFile as PsiFileImpl
    assertNotNull(psi.stub)
    assertFalse(psi.isContentsLoaded)
    // JavaShortClassNameIndex traces key hashes: with b's filter only the widened one lets a's logged keys through.
    val names = StubIndex.getInstance().getAllKeys(JavaStubIndexKeys.CLASS_SHORT_NAMES, b)
    assertTrue(names.toString(), "Greeter" in names && "C17" in names)
    assertEquals(emptyList<String>(), errors)
  }

  @Test
  fun `test changed donor leaves the alias with its own stubs`() {
    val (a, rootA) = open("a")
    val (b, rootB) = open("b")
    WriteAction.runAndWait<Exception> { VfsUtil.saveText(greeter(rootA), "package p;\npublic class Greeter { void zzDonorOnly() {} }\nclass ZzDonorOnly {}\n") }
    IndexingTestUtil.waitUntilIndexesAreReady(a)
    IndexingTestUtil.waitUntilIndexesAreReady(b)
    assertEquals(greeter(rootA), classFile(a, "p.ZzDonorOnly"))
    assertNull(classFile(b, "p.ZzDonorOnly"))
    assertEquals(greeter(rootB), classFile(b, "p.Greeter"))
    val dataB = FileBasedIndex.getInstance().getFileData(StubUpdatingIndex.INDEX_ID, greeter(rootB), b)
    assertEquals(setOf(id(greeter(rootB))), dataB.keys)
    assertEquals(emptyList<String>(), errors)
  }

  @Test
  fun `test a file edited to the donor's content borrows stubs that match its length`() {
    val (_, rootA) = open("a")
    val (b, rootB) = open("b")
    val greeterB = greeter(rootB)
    val original = VfsUtil.loadText(greeterB)
    WriteAction.runAndWait<Exception> { VfsUtil.saveText(greeterB, "package p;\npublic class Greeter { void zzLongerThanTheDonor() {} }\n") }
    FileBasedIndex.getInstance().ensureUpToDate(StubUpdatingIndex.INDEX_ID, b, null)
    assertNull(AliasRegistry.of("Stubs").donorOf(id(greeterB)))
    WriteAction.runAndWait<Exception> { VfsUtil.saveText(greeterB, original) }
    FileBasedIndex.getInstance().ensureUpToDate(StubUpdatingIndex.INDEX_ID, b, null)
    assertEquals(id(greeter(rootA)), AliasRegistry.of("Stubs").donorOf(id(greeterB)))
    // With a document the loader compares the recorded text length; the one of the longer content would be an error.
    assertNotNull(FileDocumentManager.getInstance().getDocument(greeterB))
    assertNotNull(StubTreeLoader.getInstance().readFromVFile(b, greeterB))
    assertEquals(emptyList<String>(), errors)
  }

  @Test
  fun `test scanning does not alias stubs over a recorded length of other content`() {
    open("a")
    val (b, rootB) = open("b")
    val rootD = tempDir.newDirectory("d").toPath()
    CheckoutFixture.writeJavaCheckout(rootD)
    val other = "package p;\npublic class Greeter { int zzOtherDonor; }\n"
    java.nio.file.Files.writeString(rootD.resolve("src/p/Greeter.java"), other)
    CheckoutFixture.openCheckout(rootD, disposable.disposable)
    val greeterB = greeter(rootB)
    // An edit and its undo make b's Greeter borrow from a through indexing, which records the length of a's content.
    val original = VfsUtil.loadText(greeterB)
    for (text in listOf("package p;\npublic class Greeter { void zzLongerThanTheDonor() {} }\n", original)) {
      WriteAction.runAndWait<Exception> { VfsUtil.saveText(greeterB, text) }
      FileBasedIndex.getInstance().ensureUpToDate(StubUpdatingIndex.INDEX_ID, b, null)
    }
    assertNotNull(AliasRegistry.of("Stubs").donorOf(id(greeterB)))
    // Then it gets d's content, as after an edit while the IDE was closed: no data of its own, but a stale length.
    WriteAction.runAndWait<Exception> { VfsUtil.saveText(greeterB, other) }
    AliasRegistry.of("Stubs").forgetFile(id(greeterB))
    val processor = LocalSharedIndexStatusProcessor(b)
    assertFalse(processor.tryIndexFileWithoutContent(IndexedFileImpl(greeterB, b), id(greeterB), StubUpdatingIndex.INDEX_ID))
    assertTrue(processor.tryIndexFileWithoutContent(IndexedFileImpl(greeterB, b), id(greeterB), IdIndex.NAME))
  }

  @Test
  fun `test scanning does not alias the stubs of a class file`() {
    val (b, _) = open("b")
    val repo = tempDir.newDirectory("repo").toPath()
    val bytes = javaClass.getResourceAsStream("${javaClass.simpleName}.class")!!.readAllBytes()
    val path = GitBlobsTest.committed(repo, "out/Foo.class", "")
    java.nio.file.Files.write(path, bytes)
    java.nio.file.Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 10_000))
    GitBlobsTest.git(repo, "add", "out/Foo.class")
    GitBlobs.clear()
    val classFile = CheckoutFixture.virtualFile(path)
    assertNotNull(GitBlobs.blobOf(classFile))
    // A donor with the same content key and one by blob id: either would make scanning mark the class file indexed.
    val stubs = FileBasedIndexExtension.EXTENSION_POINT_NAME.extensionList.first { it.name == StubUpdatingIndex.INDEX_ID }
    val key = CacheKeys.keyFor(FileContentImpl.createByFile(classFile, b) as FileContentImpl, stubs.indexer)
    AliasRegistry.of("Stubs").recordDonor(key, Int.MAX_VALUE, classFile.length, classFile.name)
    AliasRegistry.of("Stubs").recordBlobDonor(ByteArray(20), Int.MAX_VALUE)
    // StubTreeBuilder.getStubBuilderType throws ClassCastException for a class file without content (IndexedFileImpl),
    // in the blob key and in the platform's setIndexedState of a file reported indexed.
    assertFalse(LocalSharedIndexStatusProcessor(b).tryIndexFileWithoutContent(IndexedFileImpl(classFile, b), id(classFile), StubUpdatingIndex.INDEX_ID))
  }

  @Test
  fun `test changed alias is indexed on its own`() {
    val (a, rootA) = open("a")
    val (b, rootB) = open("b")
    val trace = LocalSharedIndexStatistics.getInstance().startTrace()
    WriteAction.runAndWait<Exception> { VfsUtil.saveText(greeter(rootB), "package p;\npublic class Greeter {}\nclass ZzAliasOnly {}\n") }
    IndexingTestUtil.waitUntilIndexesAreReady(b)
    assertEquals(greeter(rootB), classFile(b, "p.ZzAliasOnly"))
    assertNull(classFile(a, "p.ZzAliasOnly"))
    assertEquals(greeter(rootA), classFile(a, "p.Greeter"))
    assertNull(AliasRegistry.of("Stubs").donorOf(id(greeter(rootB))))
    val stubEvents = trace.filter { it.index == "Stubs" }.map { it.path }.toSet()
    assertEquals(setOf(greeter(rootB).path), stubEvents)
    assertEquals(emptyList<String>(), errors)
  }
}
