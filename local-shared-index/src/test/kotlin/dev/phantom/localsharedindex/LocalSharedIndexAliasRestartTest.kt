// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.localsharedindex

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileWithId
import com.intellij.openapi.vfs.newvfs.ManagingFS
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.impl.cache.impl.id.IdIndex
import com.intellij.psi.impl.cache.impl.id.IdIndexEntry
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.DisposableRule
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.RunsInEdt
import com.intellij.testFramework.rules.TempDirectory
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexImpl
import com.intellij.util.indexing.FileBasedIndexTumbler
import org.junit.After
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
import java.nio.file.attribute.FileTime

/** Aliases outlive a restart of the indexes, and what changed while the indexes were off is indexed again. */
@RunsInEdt
class LocalSharedIndexAliasRestartTest {
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

  private fun main(root: Path) = CheckoutFixture.virtualFile(root.resolve(CheckoutFixture.MAIN))

  private fun containing(project: Project, word: String, file: VirtualFile): Boolean =
    FileBasedIndex.getInstance().getContainingFiles(IdIndex.NAME, IdIndexEntry(word, true), GlobalSearchScope.fileScope(project, file)).contains(file)

  /**
   * The indexes shut down (the extension saves its state) and come back; the alias registry is emptied in between, as
   * a new IDE process starts with nothing in memory. [whileOff] changes files on disk only, as while the IDE is closed;
   * the VFS refresh afterwards reports those changes, as the refresh at startup does.
   */
  private fun restart(vararg projects: Project, whileOff: () -> Unit = {}) {
    val tumbler = FileBasedIndexTumbler("LocalSharedIndexAliasRestartTest")
    tumbler.turnOff()
    try {
      AliasRegistry.clearAll()
      whileOff()
    }
    finally {
      tumbler.turnOn()
    }
    (FileBasedIndex.getInstance() as FileBasedIndexImpl).waitUntilIndicesAreInitialized()
    for (root in roots) VfsUtil.markDirtyAndRefresh(false, true, true, CheckoutFixture.virtualFile(root))
    for (project in projects) {
      IndexingTestUtil.waitUntilIndexesAreReady(project)
      FileBasedIndex.getInstance().ensureUpToDate(IdIndex.NAME, project, null)
    }
  }

  private val roots = ArrayList<Path>()

  private fun writeOffline(file: Path, text: String) {
    Files.writeString(file, text)
    Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 10_000))
  }

  /**
   * A new process starts with what the extension saved and nothing else in memory, while the platform's per-file
   * indexing stamps say every file of b is indexed: scanning does not visit b's files again. (A restart of the indexes
   * in a test rescans them, which would hide the loss.)
   */
  @Test
  fun `test saved state alone keeps the second checkout answering`() {
    val (_, rootA) = open("a")
    val (b, rootB) = open("b")
    val greeterB = CheckoutFixture.virtualFile(rootB.resolve("src/p/Greeter.java"))
    val saved = tempDir.newDirectory("saved").toPath().resolve("donors.bin")
    val vfs = ManagingFS.getInstance().creationTimestamp
    AliasRegistry.save(saved, vfs)
    AliasRegistry.clearAll()
    assertEquals(emptySet<Int>(), AliasRegistry.load(saved, vfs).orphans)
    assertTrue(containing(b, "greet", main(rootB)))
    assertEquals(id(main(rootA)), AliasRegistry.of("IdIndex").donorOf(id(main(rootB))))
    assertEquals(greeterB, JavaPsiFacade.getInstance(b).findClass("p.Greeter", GlobalSearchScope.projectScope(b))?.containingFile?.virtualFile)
  }

  @Test
  fun `test second checkout still answers from its donor after a restart`() {
    val (a, rootA) = open("a")
    val (b, rootB) = open("b")
    roots += listOf(rootA, rootB)
    assertEquals(id(main(rootA)), AliasRegistry.of("IdIndex").donorOf(id(main(rootB))))
    restart(a, b)
    assertTrue(containing(b, "greet", main(rootB)))
    assertEquals(id(main(rootA)), AliasRegistry.of("IdIndex").donorOf(id(main(rootB))))
    val greeterB = CheckoutFixture.virtualFile(rootB.resolve("src/p/Greeter.java"))
    assertEquals(greeterB, JavaPsiFacade.getInstance(b).findClass("p.Greeter", GlobalSearchScope.projectScope(b))?.containingFile?.virtualFile)
  }

  @Test
  fun `test aliases of a donor that is gone are not restored`() {
    val (_, rootA) = open("a")
    open("b")
    val saved = tempDir.newDirectory("saved").toPath().resolve("donors.bin")
    val vfs = ManagingFS.getInstance().creationTimestamp
    AliasRegistry.save(saved, vfs)
    AliasRegistry.clearAll()
    val mainA = id(main(rootA))
    AliasRegistry.load(saved, vfs)
    val orphans = AliasRegistry.dropGoneDonors { it != mainA }
    assertTrue(orphans.toString(), orphans.isNotEmpty())
    assertTrue(AliasRegistry.of("IdIndex").aliasesOf(mainA).isEmpty())
    // Another VFS: nothing of the saved list applies.
    AliasRegistry.clearAll()
    assertEquals(emptySet<Int>(), AliasRegistry.load(saved, vfs + 1).orphans)
    assertFalse(AliasRegistry.of("IdIndex").hasDonors)
  }

  @Test
  fun `test donor edited while closed leaves the alias with its own data`() {
    val (a, rootA) = open("a")
    val (b, rootB) = open("b")
    roots += listOf(rootA, rootB)
    restart(a, b) { writeOffline(rootA.resolve(CheckoutFixture.MAIN), "package p;\npublic class Main { int zzdonoronly; }\n") }
    FileBasedIndex.getInstance().ensureUpToDate(IdIndex.NAME, b, null)
    assertTrue(containing(a, "zzdonoronly", main(rootA)))
    assertFalse(containing(b, "zzdonoronly", main(rootB)))
    assertTrue(containing(b, "greet", main(rootB)))
    assertNull(AliasRegistry.of("IdIndex").donorOf(id(main(rootB))))
  }

  @Test
  fun `test alias edited while closed is indexed on its own`() {
    val (a, rootA) = open("a")
    val (b, rootB) = open("b")
    roots += listOf(rootA, rootB)
    val trace = LocalSharedIndexStatistics.getInstance().startTrace()
    restart(a, b) { writeOffline(rootB.resolve(CheckoutFixture.MAIN), "package p;\npublic class Main { int zzaliasonly; }\n") }
    assertTrue(containing(b, "zzaliasonly", main(rootB)))
    assertFalse(containing(b, "greet", main(rootB)))
    assertTrue(containing(a, "greet", main(rootA)))
    assertNull(AliasRegistry.of("IdIndex").donorOf(id(main(rootB))))
    // Only the edited file is mapped again.
    assertEquals(setOf(main(rootB).path), trace.filter { it.index == "IdIndex" && !it.hit }.map { it.path }.toSet())
  }

  @Test
  fun `test donor deleted while closed leaves the alias with its own data`() {
    val (a, rootA) = open("a")
    val (b, rootB) = open("b")
    roots += listOf(rootA, rootB)
    val mainB = main(rootB)
    restart(a, b) { Files.delete(rootA.resolve(CheckoutFixture.MAIN)) }
    FileBasedIndex.getInstance().ensureUpToDate(IdIndex.NAME, b, null)
    assertTrue(containing(b, "greet", mainB))
    assertNull(AliasRegistry.of("IdIndex").donorOf(id(mainB)))
    val nullArgs = com.intellij.util.indexing.ID.findByName<Any, Any>("java.null.method.argument")!!
    assertTrue(FileBasedIndex.getInstance().getFileData(nullArgs, mainB, b).isNotEmpty())
  }

  private val ext get() = LocalSharedIndexExtension.getInstance()

  /** Waits for work the extension hands to a pooled thread and the indexing it requests. */
  private fun waitFor(what: String, project: Project, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + 60_000
    while (System.currentTimeMillis() < deadline) {
      PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
      IndexingTestUtil.waitUntilIndexesAreReady(project)
      FileBasedIndex.getInstance().ensureUpToDate(IdIndex.NAME, project, null) // a requested rebuild starts on use
      if (condition()) return
      Thread.sleep(100)
    }
    throw AssertionError("timed out waiting for $what")
  }

  @Test
  fun `test a file whose alias is not restored is indexed with its own data`() {
    val (_, rootA) = open("a")
    val (b, rootB) = open("b")
    val saved = tempDir.newDirectory("saved").toPath().resolve("donors.bin")
    val vfs = ManagingFS.getInstance().creationTimestamp
    AliasRegistry.save(saved, vfs)
    AliasRegistry.clearAll()
    val mainA = id(main(rootA))
    ext.restoreAliases(saved, vfs) { it != mainA }.join()
    waitFor("b's Main to be indexed", b) { containing(b, "greet", main(rootB)) }
    assertNull(AliasRegistry.of("IdIndex").donorOf(id(main(rootB))))
  }

  private fun assertRebuiltAndAnswering(saved: Path, vfs: Long, a: Project, rootA: Path, b: Project, rootB: Path) {
    val trace = LocalSharedIndexStatistics.getInstance().startTrace()
    ext.restoreAliases(saved, vfs).join()
    assertFalse(AliasRegistry.of("IdIndex").hasAliases)
    // The rebuild maps the files again, from the cache it kept. Both projects are scanned at once after the rebuild
    // (FileBasedIndexImpl.scheduleIndexRescanningForAllProjects): the checkout indexed first becomes the donor, and
    // scanning marks the other checkout's files its aliases without mapping them. Which one comes first is up to the
    // scheduler, so either checkout's files count.
    val checkouts = listOf(rootA, rootB).map { CheckoutFixture.virtualFile(it).path + "/" }
    fun rebuilt() = trace.filter { event -> event.index == "IdIndex" && checkouts.any { event.path.startsWith(it) } }
    waitFor("the rebuild of IdIndex", a) { rebuilt().isNotEmpty() }
    assertTrue(trace.filter { it.index == "IdIndex" }.toString(), rebuilt().all { it.hit })
    waitFor("b to answer", b) { containing(b, "greet", main(rootB)) }
    waitFor("a to answer", a) { containing(a, "greet", main(rootA)) }
    assertTrue(Files.exists(saved.resolveSibling(AliasRegistry.MARKER)))
  }

  @Test
  fun `test change marker left by a crash rebuilds the aliased indexes`() {
    val (a, rootA) = open("a")
    val (b, rootB) = open("b")
    val saved = tempDir.newDirectory("saved").toPath().resolve("donors.bin")
    val vfs = ManagingFS.getInstance().creationTimestamp
    AliasRegistry.save(saved, vfs)
    Files.createFile(saved.resolveSibling(AliasRegistry.MARKER))
    assertRebuiltAndAnswering(saved, vfs, a, rootA, b, rootB)
  }

  @Test
  fun `test unreadable donor list rebuilds the aliased indexes`() {
    val (a, rootA) = open("a")
    val (b, rootB) = open("b")
    val saved = tempDir.newDirectory("saved").toPath().resolve("donors.bin")
    Files.write(saved, byteArrayOf(0, 0, 0, 4, 1, 2, 3))
    assertRebuiltAndAnswering(saved, ManagingFS.getInstance().creationTimestamp, a, rootA, b, rootB)
  }

  @Test
  fun `test clean save leaves no marker and needs no rebuild`() {
    val (_, rootA) = open("a")
    val (b, rootB) = open("b")
    val saved = tempDir.newDirectory("saved").toPath().resolve("donors.bin")
    val vfs = ManagingFS.getInstance().creationTimestamp
    AliasRegistry.save(saved, vfs)
    AliasRegistry.load(saved, vfs) // the marker now lives next to saved
    AliasRegistry.of("IdIndex").alias(id(main(rootB)), id(main(rootA))) // a change after the save
    val marker = saved.resolveSibling(AliasRegistry.MARKER)
    assertTrue(Files.exists(marker))
    AliasRegistry.save(saved, vfs)
    assertFalse(Files.exists(marker))
    AliasRegistry.clearAll()
    val trace = LocalSharedIndexStatistics.getInstance().startTrace()
    ext.restoreAliases(saved, vfs).join()
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    Thread.sleep(500)
    IndexingTestUtil.waitUntilIndexesAreReady(b)
    assertTrue(containing(b, "greet", main(rootB)))
    assertEquals(emptyList<TraceEvent>(), trace.filter { it.index == "IdIndex" })
  }

  @Test
  fun `test a large donor list does not hold up index initialization`() {
    val aliases = AliasRegistry.of("IdIndex")
    val random = java.util.Random(42)
    for (i in 0 until 300_000) aliases.recordDonor(ByteArray(20).also(random::nextBytes), 10_000_000 + i, 100L + i % 1000, "F$i.java")
    for (i in 0 until 60_000) aliases.alias(20_000_000 + i, 10_000_000 + i * 5)
    val saved = tempDir.newDirectory("saved").toPath().resolve("donors.bin")
    val vfs = ManagingFS.getInstance().creationTimestamp
    AliasRegistry.save(saved, vfs)
    AliasRegistry.clearAll()
    // What LocalSharedIndexExtension.initialize spends on the list: handing it to a pooled thread.
    val started = System.nanoTime()
    val restored = ext.restoreAliases(saved, vfs) { true }
    val initMs = (System.nanoTime() - started) / 1_000_000
    restored.join()
    val totalMs = (System.nanoTime() - started) / 1_000_000
    println("initialization part: $initMs ms, list read and checked in the background: $totalMs ms")
    assertTrue("$initMs ms", initMs < 300)
    assertEquals(10_000_000 + 5 * 59_999, aliases.donorOf(20_000_000 + 59_999))
    AliasRegistry.clearAll()
  }

  @Test
  fun `test queries wait for the donor list being read`() {
    val (_, rootA) = open("a")
    val (b, rootB) = open("b")
    val saved = tempDir.newDirectory("saved").toPath().resolve("donors.bin")
    val vfs = ManagingFS.getInstance().creationTimestamp
    AliasRegistry.save(saved, vfs)
    AliasRegistry.clearAll()
    val release = java.util.concurrent.CountDownLatch(1)
    AliasRegistry.loadInBackground {
      release.await()
      AliasRegistry.load(saved, vfs)
    }
    val answer = java.util.concurrent.CompletableFuture.supplyAsync { AliasRegistry.of("IdIndex").donorOf(id(main(rootB))) }
    Thread.sleep(200)
    assertFalse("a query must not answer before the list is in", answer.isDone)
    release.countDown()
    assertEquals(id(main(rootA)), answer.get(10, java.util.concurrent.TimeUnit.SECONDS))
    assertTrue(containing(b, "greet", main(rootB)))
  }
}
