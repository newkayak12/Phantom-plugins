// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.AgentSession
import dev.phantom.ai.core.AgentStatus
import dev.phantom.ai.memo.MemoChoices
import dev.phantom.ai.memo.MemoItem
import dev.phantom.ai.memo.MemoQueue
import dev.phantom.ai.memo.MemoTerminals
import dev.phantom.ai.memo.QueuedBatch
import junit.framework.TestCase

class MemoQueueTest : TestCase() {
  private class Fake : MemoTerminals {
    val statuses = mutableMapOf<String, AgentStatus?>()
    val alive = mutableSetOf("t")
    val sent = mutableListOf<Pair<String, String>>()
    override fun status(tabId: String) = statuses[tabId]
    override fun alive(tabId: String) = tabId in alive
    override fun submit(tabId: String, prompt: String): Boolean {
      sent += tabId to prompt
      return true
    }
  }

  private val item = MemoItem("A.kt", 1, 1, "kotlin", "x", "y")
  private val fake = Fake()
  private val returned = mutableListOf<QueuedBatch>()
  private val queue = MemoQueue(fake) { returned += it }

  fun testIdleSessionGetsItNow() {
    fake.statuses["t"] = AgentStatus.DONE
    assertTrue(queue.offer("t", "p", listOf(item)))
    assertEquals(1, fake.sent.size)
  }

  fun testBusySessionQueuesAndSendsOnDone() {
    fake.statuses["t"] = AgentStatus.WORKING
    assertFalse(queue.offer("t", "p", listOf(item)))
    assertEquals(0, fake.sent.size)
    fake.statuses["t"] = AgentStatus.DONE
    queue.sessionChanged("t")
    assertEquals(1, fake.sent.size)
    assertTrue(queue.pending().isEmpty())
  }

  fun testWaitingIsBusyToo() {
    fake.statuses["t"] = AgentStatus.WAITING
    assertFalse(queue.offer("t", "p", listOf(item)))
    assertEquals(1, queue.pending().size)
  }

  fun testIdleWaitingSessionGetsItNow() {
    fake.statuses["t"] = MemoChoices.effectiveStatus(AgentSession("t", "/w", AgentKind.CLAUDE, AgentStatus.WAITING, idleWaiting = true))
    assertTrue(queue.offer("t", "p", listOf(item)))
    assertEquals(1, fake.sent.size)
  }

  fun testPermissionWaitingSessionQueues() {
    fake.statuses["t"] = MemoChoices.effectiveStatus(AgentSession("t", "/w", AgentKind.CLAUDE, AgentStatus.WAITING))
    assertFalse(queue.offer("t", "p", listOf(item)))
    assertEquals(0, fake.sent.size)
  }

  fun testOneBatchPerDone() {
    fake.statuses["t"] = AgentStatus.WORKING
    queue.offer("t", "first", listOf(item))
    queue.offer("t", "second", listOf(item))
    fake.statuses["t"] = AgentStatus.DONE
    queue.sessionChanged("t")
    assertEquals(listOf("t" to "first"), fake.sent)
    fake.statuses["t"] = AgentStatus.WORKING
    queue.sessionChanged("t")
    assertEquals(1, fake.sent.size)
    fake.statuses["t"] = AgentStatus.DONE
    queue.sessionChanged("t")
    assertEquals(listOf("t" to "first", "t" to "second"), fake.sent)
  }

  fun testCancelReturnsBatch() {
    fake.statuses["t"] = AgentStatus.WORKING
    queue.offer("t", "p", listOf(item))
    val id = queue.pending().single().id
    assertEquals(id, queue.cancel(id)?.id)
    assertNull(queue.cancel(id))
    fake.statuses["t"] = AgentStatus.DONE
    queue.sessionChanged("t")
    assertEquals(0, fake.sent.size)
    assertTrue(returned.isEmpty())
  }

  fun testClosedSessionReturnsBatches() {
    fake.statuses["t"] = AgentStatus.WORKING
    queue.offer("t", "a", listOf(item))
    queue.offer("t", "b", listOf(item))
    fake.alive.clear()
    queue.sessionChanged("t")
    assertEquals(2, returned.size)
    assertTrue(queue.pending().isEmpty())
  }

  fun testQueueAheadKeepsOrder() {
    fake.statuses["t"] = AgentStatus.WORKING
    queue.offer("t", "first", listOf(item))
    fake.statuses["t"] = AgentStatus.DONE
    assertFalse(queue.offer("t", "second", listOf(item)))
    assertEquals(0, fake.sent.size)
    queue.sessionChanged("t")
    assertEquals(listOf("t" to "first"), fake.sent)
  }
}
