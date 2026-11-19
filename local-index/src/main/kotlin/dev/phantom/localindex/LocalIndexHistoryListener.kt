// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.localindex

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.indexing.diagnostic.ProjectDumbIndexingHistory
import com.intellij.util.indexing.diagnostic.ProjectIndexingActivityHistoryListener
import org.jetbrains.annotations.TestOnly

/** Writes one log line per project indexing that says how much of it came from the cache. */
class LocalIndexHistoryListener : ProjectIndexingActivityHistoryListener {
  override fun onFinishedDumbIndexing(history: ProjectDumbIndexingHistory) {
    if (ApplicationManager.getApplication().isUnitTestMode) lastHistory = history
    val counts = LocalIndexStatistics.getInstance().takeCounts(history.project)
    if (counts.values.any { it.hits != 0 || it.misses != 0 }) LOG.info(formatLine(history.project.name, counts))
  }

  companion object {
    @TestOnly @Volatile var lastHistory: ProjectDumbIndexingHistory? = null
  }
}

internal fun formatLine(project: String, counts: Map<String, Counts>): String {
  val hits = counts.values.sumOf { it.hits }
  val total = counts.values.sumOf { it.hits + it.misses }
  val perIndex = counts.entries.sortedBy { it.key }.joinToString(", ") { (id, c) -> "$id ${c.hits}/${c.hits + c.misses}" }
  val savedMs = counts.values.sumOf { it.savedMicros } / 1000
  return "Phantom local index: $project: $hits of $total index updates from cache ($perIndex), about $savedMs ms of indexing saved"
}

private val LOG = logger<LocalIndexHistoryListener>()
