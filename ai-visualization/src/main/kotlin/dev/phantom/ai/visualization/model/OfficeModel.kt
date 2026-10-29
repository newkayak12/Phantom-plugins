// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.visualization.model

import dev.phantom.ai.api.AiSession
import dev.phantom.ai.api.AiWorktree
import java.io.File
import java.io.IOException

/** One open project as the API reports it: its worktrees and its live sessions. [key] identifies the project (its base path). */
data class ProjectData(val key: String, val name: String, val worktrees: List<AiWorktree>, val sessions: List<AiSession>)

/**
 * A running subagent of a session, drawn as a full-size subordinate seated near its parent (the lead). [seed] picks its look and
 * is stable per subagent id; [activity] is what its last tool call does (null: unknown).
 */
data class Helper(val id: String, val name: String, val activity: String? = null) {
  val seed: Int get() = id.hashCode() and Int.MAX_VALUE
}

/**
 * One agent session as a person. [seed] picks the look (hair, shirt) and is stable per tab. [helpers] are its running subagents
 * (its subordinates), oldest first and at most [OfficeModel.MAX_HELPERS]; [moreHelpers] counts the running ones beyond that ("+N").
 */
data class Person(val session: AiSession, val state: VizState, val seed: Int, val helpers: List<Helper> = emptyList(), val moreHelpers: Int = 0) {
  /** Every running subagent, drawn or not. */
  val helperCount: Int get() = helpers.size + moreHelpers
}

/** One worktree as a desk area; [people] are its live sessions, oldest first. */
data class DeskArea(val worktree: AiWorktree, val people: List<Person>)

/** One project as a floor. [number] counts from 1 (the first open project), [label] is "nF". */
class Floor(val number: Int, val key: String, val name: String, val areas: List<DeskArea>, val counts: Map<VizState, Int>) {
  val label: String get() = "${number}F"
  val sessionCount: Int get() = counts.values.sum()
  val worktreeCount: Int get() = areas.size
}

/** The whole building: floors in open-project order, 1F first. Built only from [dev.phantom.ai.api] types. */
class OfficeModel(val floors: List<Floor>) {
  /** As the floor picker lists them: the highest floor first. */
  val floorsTopDown: List<Floor> get() = floors.asReversed()

  fun floor(key: String?): Floor? = floors.firstOrNull { it.key == key }

  companion object {
    /** Helpers drawn per person; more running subagents show as "+N". */
    const val MAX_HELPERS = 4

    /** [s] as a person: its running subagents become helpers (stopped ones are not drawn), capped at [MAX_HELPERS]. */
    fun person(s: AiSession): Person {
      val running = s.subagents.filter { it.running }
      val shown = running.take(MAX_HELPERS).map { Helper(it.id, it.name, it.activity) }
      return Person(s, VizState.of(s.status), s.tabId.hashCode() and Int.MAX_VALUE, shown, running.size - shown.size)
    }

    fun build(projects: List<ProjectData>): OfficeModel = OfficeModel(
      projects.mapIndexed { i, p ->
        val areas = orderedWorktrees(p.worktrees).map { w ->
          val own = p.sessions.filter { PathKey.of(it.worktreePath) == PathKey.of(w.path) }
            .sortedWith(compareBy({ it.startedAt }, { it.tabId }))
          DeskArea(w, own.map { person(it) })
        }
        // every live session of the project counts, also one the worktree list does not (yet) show
        val counts = VizState.values().associateWith { st -> p.sessions.count { VizState.of(it.status) == st } }
        Floor(i + 1, p.key, p.name, areas, counts)
      },
    )

    /** The main worktree first, then by title: the same order on every refresh. */
    fun orderedWorktrees(ws: List<AiWorktree>): List<AiWorktree> =
      ws.sortedWith(compareBy<AiWorktree>({ !it.isMain }, { it.title.lowercase() }, { it.path }))
  }
}

/** Worktree paths compare equal when they differ only by a trailing slash, "." parts or symlinks (/var vs /private/var). */
object PathKey {
  fun of(path: String): String {
    val f = File(path)
    val c = try {
      f.canonicalPath
    } catch (_: IOException) {
      f.absoluteFile.normalize().path
    }
    return c.trimEnd('/').ifEmpty { "/" }
  }
}
