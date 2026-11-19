// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
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
  val role: Role get() = Role.of(name)
}

/** What a subordinate does, read from its agent name; subordinates of one lead sit together by role. [label] is shown on its tag. */
enum class Role(val label: String) {
  RESEARCH("\ub9ac\uc11c\uce58"),
  PLAN("\uae30\ud68d"),
  BUILD("\uac1c\ubc1c"),
  TEST("\ud14c\uc2a4\ud2b8"),
  REVIEW("\ub9ac\ubdf0"),
  ;

  companion object {
    fun of(name: String): Role {
      val n = name.lowercase()
      return when {
        listOf("explor", "research", "search", "scout", "investigat").any { it in n } -> RESEARCH
        listOf("plan", "architect", "design").any { it in n } -> PLAN
        listOf("review", "critic", "audit", "verif").any { it in n } -> REVIEW
        listOf("test", "qa").any { it in n } -> TEST
        else -> BUILD
      }
    }
  }
}

/**
 * One agent session as a person. [seed] picks the look (hair, shirt) and is stable per tab. [helpers] are its running subagents
 * (its subordinates), by role and then oldest first, at most [OfficeModel.MAX_HELPERS]; [moreHelpers] counts the running ones
 * beyond that ("+N"). [ceo]: the first session opened in its worktree, which runs the floor and signs off what the others ask.
 */
data class Person(
  val session: AiSession,
  val state: VizState,
  val seed: Int,
  val helpers: List<Helper> = emptyList(),
  val moreHelpers: Int = 0,
  val ceo: Boolean = false,
) {
  /** Every running subagent, drawn or not. */
  val helperCount: Int get() = helpers.size + moreHelpers
}

/** What a desk area of a floor is: the CEO's office, a team (a lead and its subordinates), or the open desks for everyone else. */
enum class AreaKind { CEO, TEAM, OPEN }

/** A desk area of [worktree]'s floor; [people] sit there, oldest first. [label] goes on its name plate. */
data class DeskArea(
  val worktree: AiWorktree,
  val people: List<Person>,
  val kind: AreaKind = AreaKind.OPEN,
  val label: String = worktree.title + (worktree.prNumber?.let { " #$it" } ?: ""),
)

/**
 * One worktree as a floor of its project's building. [number] counts from 1 (the main worktree), [label] is "nF"; [key] is the
 * worktree's path, [projectKey] the project's. [areas]: the CEO's office, a team per other session that leads subordinates, then
 * the open desks.
 */
class Floor(
  val number: Int,
  val key: String,
  val name: String,
  val areas: List<DeskArea>,
  val counts: Map<VizState, Int>,
  val projectKey: String = key,
  val projectName: String = name,
) {
  val label: String get() = "${number}F"
  val sessionCount: Int get() = counts.values.sum()
}

/** The whole building: a floor per worktree, projects in open order, each project's main worktree first. Built only from [dev.phantom.ai.api] types. */
class OfficeModel(val floors: List<Floor>) {
  /** As the floor picker lists them: the highest floor first. */
  val floorsTopDown: List<Floor> get() = floors.asReversed()

  fun floor(key: String?): Floor? = floors.firstOrNull { it.key == key }

  companion object {
    /** Helpers drawn per person; more running subagents show as "+N". */
    const val MAX_HELPERS = 4

    /** The label on the CEO's office plate. */
    const val CEO_LABEL = "CEO"

    /** The label on the open desks' plate. */
    const val OPEN_LABEL = "\uc624\ud508 \ub370\uc2a4\ud06c"

    /**
     * [s] as a person: its running subagents become helpers (stopped ones are not drawn), the first [MAX_HELPERS] of them by
     * start, then grouped by role.
     */
    fun person(s: AiSession, ceo: Boolean = false): Person {
      val running = s.subagents.filter { it.running }
      val shown = running.take(MAX_HELPERS).map { Helper(it.id, it.name, it.activity) }.sortedBy { it.role.ordinal }
      return Person(s, VizState.of(s.status), s.tabId.hashCode() and Int.MAX_VALUE, shown, running.size - shown.size, ceo)
    }

    /**
     * The areas of [w]'s floor for its live [sessions]: the first one opened is the CEO in an office of its own; every other one
     * that leads running subagents gets a team area; the rest share the open desks, which are there even when empty so a new
     * session can be started at one.
     */
    fun areas(w: AiWorktree, sessions: List<AiSession>): List<DeskArea> {
      val ordered = sessions.sortedWith(compareBy({ it.startedAt }, { it.tabId }))
      val ceo = ordered.firstOrNull() ?: return listOf(DeskArea(w, emptyList(), AreaKind.OPEN, OPEN_LABEL))
      val rest = ordered.drop(1).map { person(it) }
      val (leads, open) = rest.partition { it.helperCount > 0 }
      return listOf(DeskArea(w, listOf(person(ceo, ceo = true)), AreaKind.CEO, CEO_LABEL)) +
        leads.map { DeskArea(w, listOf(it), AreaKind.TEAM, "\ud300 \u00b7 " + it.session.title) } +
        DeskArea(w, open, AreaKind.OPEN, OPEN_LABEL)
    }

    fun build(projects: List<ProjectData>): OfficeModel = OfficeModel(
      projects.flatMap { p ->
        val worktrees = orderedWorktrees(p.worktrees)
        val keys = worktrees.map { PathKey.of(it.path) }
        val byWorktree = p.sessions.groupBy { PathKey.of(it.worktreePath) }
        // a session in a worktree the list does not (yet) show works on the main floor
        val stray = byWorktree.filterKeys { it !in keys }.values.flatten()
        worktrees.mapIndexed { i, w ->
          val own = byWorktree[keys[i]].orEmpty() + if (i == 0) stray else emptyList()
          val counts = VizState.values().associateWith { st -> own.count { VizState.of(it.status) == st } }
          val name = w.title + (w.prNumber?.let { " #$it" } ?: "")
          Floor(i + 1, keys[i], name, areas(w, own), counts, p.key, p.name)
        }
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
