// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.remote

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class RemoteEdtCallsInIdeTest : BasePlatformTestCase() {
  // the EDT is busy (here: this test waits on it), as with a modal dialog open: the launch gives up and never runs late
  fun testActGivesUpWhenTheEdtDoesNotComeAndNeverRunsLate() {
    val ran = AtomicBoolean()
    val result = ApplicationManager.getApplication().executeOnPooledThread<String?> { EdtCalls.act(200) { ran.set(true); "tab" } }
      .get(10, TimeUnit.SECONDS)
    assertNull(result)
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    assertFalse(ran.get())
  }

  fun testActAndReadAnswerFromAPooledThread() {
    val app = ApplicationManager.getApplication()
    val acted = app.executeOnPooledThread<String?> { EdtCalls.act { if (app.isDispatchThread) "edt" else "pool" } }
    val read = app.executeOnPooledThread<Boolean> { EdtCalls.read { app.isDispatchThread } }
    PlatformTestUtil.waitForFuture(acted, 10_000)
    PlatformTestUtil.waitForFuture(read, 10_000)
    assertEquals("edt", acted.get())
    assertTrue(read.get())
  }
}
