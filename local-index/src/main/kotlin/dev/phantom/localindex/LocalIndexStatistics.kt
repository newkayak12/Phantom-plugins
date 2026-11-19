// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.localindex

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolderEx
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.ConcurrentHashMap
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

  // Kept in the project's user data, so a closed project is not kept alive by its counters and lookups take no lock.
  private val countersKey = Key.create<ConcurrentHashMap<String, Counter>>("phantom.localindex.counters")

  @Volatile private var trace: MutableList<TraceEvent>? = null

  fun hit(project: Project?, index: String, file: VirtualFile, savedMicros: Int) {
    counter(project, index)?.let {
      it.hits.incrementAndGet()
      it.savedMicros.addAndGet(savedMicros.toLong())
    }
    trace?.add(TraceEvent(index, file.path, true))
  }

  fun miss(project: Project?, index: String, file: VirtualFile) {
    counter(project, index)?.misses?.incrementAndGet()
    trace?.add(TraceEvent(index, file.path, false))
  }

  /** Returns what was counted for [project] since the last call and starts counting from zero. */
  fun takeCounts(project: Project): Map<String, Counts> {
    val counters = project.getUserData(countersKey) ?: return emptyMap()
    project.putUserData(countersKey, null)
    return counters.mapValues { (_, c) -> Counts(c.hits.toInt(), c.misses.toInt(), c.savedMicros.get()) }
  }

  @TestOnly
  fun startTrace(): List<TraceEvent> = CopyOnWriteArrayList<TraceEvent>().also { trace = it }

  @TestOnly
  fun stopTrace() {
    trace = null
  }

  private fun counter(project: Project?, index: String): Counter? {
    if (project == null || project.isDisposed) return null
    val counters = project.getUserData(countersKey) ?: newCounters(project)
    return counters[index] ?: counters.computeIfAbsent(index) { Counter() }
  }

  private fun newCounters(project: Project): ConcurrentHashMap<String, Counter> {
    val fresh = ConcurrentHashMap<String, Counter>()
    // Project is a UserDataHolderEx in the platform; the fallback only loses a counter in a race.
    return if (project is UserDataHolderEx) project.putUserDataIfAbsent(countersKey, fresh)
    else fresh.also { project.putUserData(countersKey, it) }
  }

  companion object {
    fun getInstance(): LocalIndexStatistics = ApplicationManager.getApplication().getService(LocalIndexStatistics::class.java)
  }
}
