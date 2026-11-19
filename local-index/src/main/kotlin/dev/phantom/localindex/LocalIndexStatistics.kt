// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.localindex

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import org.jetbrains.annotations.TestOnly
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

data class Counts(val hits: Int, val misses: Int, val savedMicros: Long)

data class TraceEvent(val index: String, val path: String, val hit: Boolean)

/** Counts, per project and index, the files served from the cache and the files that had to be indexed. */
@Service
class LocalIndexStatistics {
  private class Counter {
    val hits = AtomicLong()
    val misses = AtomicLong()
    val savedMicros = AtomicLong()
  }

  // Weak keys: a closed project must not stay alive because of its counters.
  private val perProject = Collections.synchronizedMap(WeakHashMap<Project, MutableMap<String, Counter>>())

  @Volatile private var trace: MutableList<TraceEvent>? = null

  fun hit(project: Project?, index: String, path: String, savedMicros: Int) {
    counter(project, index)?.let {
      it.hits.incrementAndGet()
      it.savedMicros.addAndGet(savedMicros.toLong())
    }
    trace?.add(TraceEvent(index, path, true))
  }

  fun miss(project: Project?, index: String, path: String) {
    counter(project, index)?.misses?.incrementAndGet()
    trace?.add(TraceEvent(index, path, false))
  }

  /** Returns what was counted for [project] since the last call and starts counting from zero. */
  fun takeCounts(project: Project): Map<String, Counts> {
    val counters = perProject.remove(project) ?: return emptyMap()
    return synchronized(counters) { counters.mapValues { (_, c) -> Counts(c.hits.toInt(), c.misses.toInt(), c.savedMicros.get()) } }
  }

  @TestOnly
  fun startTrace(): List<TraceEvent> = CopyOnWriteArrayList<TraceEvent>().also { trace = it }

  @TestOnly
  fun stopTrace() {
    trace = null
  }

  private fun counter(project: Project?, index: String): Counter? {
    if (project == null || project.isDisposed) return null
    val counters = synchronized(perProject) { perProject.getOrPut(project) { HashMap() } }
    return synchronized(counters) { counters.getOrPut(index) { Counter() } }
  }

  companion object {
    fun getInstance(): LocalIndexStatistics = ApplicationManager.getApplication().getService(LocalIndexStatistics::class.java)
  }
}
