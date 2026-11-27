// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.visualization

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.ai.api.AiAgent
import dev.phantom.ai.api.AiChangeListener
import dev.phantom.ai.api.AiSession
import dev.phantom.ai.api.AiSessionStatus
import dev.phantom.ai.api.AiWorktree
import dev.phantom.ai.api.PhantomAiApi
import dev.phantom.ai.visualization.editor.OfficeLoader
import dev.phantom.ai.visualization.model.OfficeModel
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class OfficeLoaderTest : BasePlatformTestCase() {
  private inner class FakeApi : PhantomAiApi {
    var listener: AiChangeListener? = null
    val sessionReads = AtomicInteger()
    val worktreeReads = AtomicInteger()
    @Volatile var sessions: List<AiSession> = emptyList()
    @Volatile var extra: List<AiWorktree> = emptyList()

    override fun sessions(): List<AiSession> = sessions.also { sessionReads.incrementAndGet() }
    override fun sessions(project: Project): List<AiSession> = error("the loader filters the IDE-wide list")
    override fun worktrees(project: Project): List<AiWorktree> {
      worktreeReads.incrementAndGet()
      return listOf(AiWorktree(project.basePath!!, "main", "main", null, true)) + extra
    }
    override fun addChangeListener(parent: Disposable, listener: AiChangeListener) {
      this.listener = listener
    }
    override fun focusSession(project: Project, tabId: String) = false
    override fun startSession(project: Project, worktreePath: String, agent: AiAgent): String? = null
  }

  private fun session(id: String, path: String = project.basePath!!, status: AiSessionStatus = AiSessionStatus.WORKING) =
    AiSession(id, path, AiAgent.CLAUDE, status, "t$id", 0L)

  private fun waitUntil(what: String, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + 10_000
    while (!condition()) {
      assertTrue("timed out: $what", System.currentTimeMillis() < deadline)
      Thread.sleep(20)
      PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }
  }

  /** Fires a change event and waits until the loader has read the sessions for it and delivered the model. */
  private fun change(fake: FakeApi, models: List<OfficeModel>) {
    val reads = fake.sessionReads.get()
    val delivered = models.size
    fake.listener!!.changed()
    waitUntil("a load after the event") { fake.sessionReads.get() > reads && models.size > delivered }
  }

  fun testViewsShareOneLoadAndOneModel() {
    val fake = FakeApi()
    val loader = OfficeLoader({ fake }, testRootDisposable)
    val a = CopyOnWriteArrayList<OfficeModel>()
    val b = CopyOnWriteArrayList<OfficeModel>()
    loader.register(testRootDisposable, { true }) { a += it }
    loader.register(testRootDisposable, { true }) { b += it }
    waitUntil("both views get a model") { a.isNotEmpty() && b.isNotEmpty() }
    assertEquals("two views registered within the debounce read once", 1, fake.sessionReads.get())
    assertSame("both views get the same model, built once", a.last(), b.last())
  }

  fun testSessionEventsReuseTheWorktreeListAndWorktreeEventsReadItAgain() {
    val fake = FakeApi()
    val loader = OfficeLoader({ fake }, testRootDisposable)
    val models = CopyOnWriteArrayList<OfficeModel>()
    loader.register(testRootDisposable, { true }) { models += it }
    waitUntil("first model") { models.isNotEmpty() }
    val listed = fake.worktreeReads.get()
    assertTrue(listed > 0)

    fake.sessions = listOf(session("1"))
    change(fake, models)
    fake.sessions = listOf(session("1", status = AiSessionStatus.WAITING))
    change(fake, models)
    assertEquals("session events do not run git again", listed, fake.worktreeReads.get())
    assertEquals(1, models.last().floors.sumOf { f -> f.areas.sumOf { it.people.size } })

    // an event that leaves the sessions as they were is about worktrees: they are listed again
    change(fake, models)
    assertTrue("a worktree event lists the worktrees again", fake.worktreeReads.get() > listed)
  }

  fun testSessionInAnUnknownWorktreeReadsTheWorktreesAgain() {
    val fake = FakeApi()
    val loader = OfficeLoader({ fake }, testRootDisposable)
    val models = CopyOnWriteArrayList<OfficeModel>()
    loader.register(testRootDisposable, { true }) { models += it }
    waitUntil("first model") { models.isNotEmpty() }
    val listed = fake.worktreeReads.get()
    val wt = project.basePath!! + "-feature"
    fake.extra = listOf(AiWorktree(wt, "feature", "feature", null, false))
    fake.sessions = listOf(session("1", wt))
    change(fake, models)
    assertTrue(fake.worktreeReads.get() > listed)
    assertEquals(1, models.last().floors.sumOf { f -> f.areas.sumOf { it.people.size } })
  }

  fun testHiddenViewsIgnoreEventsAndADisposedViewGetsNothing() {
    val fake = FakeApi()
    val loader = OfficeLoader({ fake }, testRootDisposable)
    val models = CopyOnWriteArrayList<OfficeModel>()
    val view = Disposer.newDisposable(testRootDisposable)
    loader.register(view, { false }) { models += it }
    waitUntil("first model") { models.isNotEmpty() }
    repeat(3) { fake.listener!!.changed() }
    Thread.sleep(OfficeLoader.DEBOUNCE_MS * 4L)
    PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    assertEquals(1, fake.sessionReads.get())
    Disposer.dispose(view)
    loader.request()
    Thread.sleep(OfficeLoader.DEBOUNCE_MS * 4L)
    PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    assertEquals("no view: no load", 1, fake.sessionReads.get())
    assertEquals(1, models.size)
  }
}
