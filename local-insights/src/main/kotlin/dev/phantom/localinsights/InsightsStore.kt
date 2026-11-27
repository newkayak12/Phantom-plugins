// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.localinsights

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import java.util.concurrent.ConcurrentHashMap

/** Time spent by one inspection, summed over every file it ran on. */
data class InspectionTiming(val shortName: String, val displayName: String, val runs: Long, val totalMillis: Long, val maxMillis: Long)

/** Time a plugin's file-based indexes spent computing values, summed over indexing sessions. */
data class IndexingTiming(val plugin: String, val files: Long, val totalNanos: Long)

/**
 * Timings kept in memory only, for the IDE session; nothing is persisted or sent, so the numbers are gone after a restart.
 */
@Service(Service.Level.APP)
class InsightsStore {
  private val inspections = ConcurrentHashMap<String, InspectionTiming>()
  private val indexing = ConcurrentHashMap<String, IndexingTiming>()

  fun recordInspection(shortName: String, displayName: String, millis: Long) {
    val duration = millis.coerceAtLeast(0)
    inspections.merge(shortName, InspectionTiming(shortName, displayName, 1, duration, duration)) { old, new ->
      old.copy(runs = old.runs + 1, totalMillis = old.totalMillis + new.totalMillis, maxMillis = maxOf(old.maxMillis, new.maxMillis))
    }
  }

  fun recordIndexing(plugin: String, files: Long, nanos: Long) {
    indexing.merge(plugin, IndexingTiming(plugin, files, nanos.coerceAtLeast(0))) { old, new ->
      old.copy(files = old.files + new.files, totalNanos = old.totalNanos + new.totalNanos)
    }
  }

  fun slowestInspections(): List<InspectionTiming> = inspections.values.sortedByDescending { it.totalMillis }

  fun indexingByPlugin(): List<IndexingTiming> = indexing.values.sortedByDescending { it.totalNanos }

  fun clear() {
    inspections.clear()
    indexing.clear()
  }

  companion object {
    fun getInstance(): InsightsStore = ApplicationManager.getApplication().getService(InsightsStore::class.java)
  }
}
