// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.memo

import dev.phantom.ai.core.AgentStatus

class QueuedBatch(val id: Long, val tabId: String, val prompt: String, val items: List<MemoItem>)

interface MemoTerminals {
  fun status(tabId: String): AgentStatus?
  fun alive(tabId: String): Boolean
  fun submit(tabId: String, prompt: String): Boolean
}

/** Never interrupts a busy session: batches wait per tab and go out one per DONE. */
class MemoQueue(
  private val terminals: MemoTerminals,
  private val onReturned: (QueuedBatch) -> Unit,
) {
  private val queues = LinkedHashMap<String, ArrayDeque<QueuedBatch>>()
  private val sentAwaitingBusy = HashSet<String>()
  private var nextId = 1L

  /** true if sent right away, false if queued (or returned because the session is gone). */
  @Synchronized
  fun offer(tabId: String, prompt: String, items: List<MemoItem>): Boolean {
    val batch = QueuedBatch(nextId++, tabId, prompt, items)
    val q = queues[tabId]
    if ((q == null || q.isEmpty()) && !isBusy(tabId) && send(batch)) return true
    if (!terminals.alive(tabId)) {
      onReturned(batch)
      return false
    }
    queues.getOrPut(tabId) { ArrayDeque() }.addLast(batch)
    return false
  }

  @Synchronized
  fun pending(): List<QueuedBatch> = queues.values.flatten()

  /** Removes and returns the batch; the caller restores its memos (onReturned is not called). */
  @Synchronized
  fun cancel(batchId: Long): QueuedBatch? {
    for ((tabId, q) in queues) {
      val found = q.firstOrNull { it.id == batchId } ?: continue
      q.remove(found)
      if (q.isEmpty()) queues.remove(tabId)
      return found
    }
    return null
  }

  @Synchronized
  fun sessionChanged(tabId: String) {
    if (!terminals.alive(tabId)) {
      sentAwaitingBusy.remove(tabId)
      queues.remove(tabId)?.forEach(onReturned)
      return
    }
    if (isBusy(tabId)) {
      sentAwaitingBusy.remove(tabId)
      return
    }
    val q = queues[tabId] ?: return
    if (tabId in sentAwaitingBusy) return
    val batch = q.removeFirst()
    if (!send(batch)) {
      onReturned(batch)
      sentAwaitingBusy.remove(tabId)
      queues.remove(tabId)?.forEach(onReturned)
    } else if (q.isEmpty()) {
      queues.remove(tabId)
    }
  }

  private fun isBusy(tabId: String): Boolean {
    val s = terminals.status(tabId)
    return s == AgentStatus.WORKING || s == AgentStatus.WAITING
  }

  private fun send(batch: QueuedBatch): Boolean {
    if (!terminals.submit(batch.tabId, batch.prompt)) return false
    sentAwaitingBusy.add(batch.tabId)
    return true
  }
}
