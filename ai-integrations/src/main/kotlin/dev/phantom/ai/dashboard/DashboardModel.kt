// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.dashboard

import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.AgentStatus
import dev.phantom.ai.core.Subagent
import dev.phantom.ai.sidebar.RepoNode
import dev.phantom.ai.sidebar.SidebarModel

/** One agent session as a dashboard card. */
data class DashboardCard(
  val tabId: String,
  val worktree: String,
  val worktreeTitle: String,
  val project: String,
  val projectPath: String,
  val branch: String?,
  val prNumber: Int?,
  val kind: AgentKind,
  val title: String,
  val status: AgentStatus,
  val unread: Boolean,
  val lastPrompt: String?,
  val lastAnswer: String?,
  val subagents: List<Subagent>,
  val elapsed: String?,
  val startedAt: Long,
)

data class DashboardColumn(val status: AgentStatus, val cards: List<DashboardCard>) {
  val count: Int get() = cards.size

  /** "None" for an empty column, else null. */
  val emptyText: String? get() = if (cards.isEmpty()) EMPTY else null

  companion object {
    const val EMPTY = "None"
  }
}

/** Search text over worktree, project and agent; [agent] / [projectPath] narrow further (null = all). */
data class DashboardQuery(val search: String = "", val agent: AgentKind? = null, val projectPath: String? = null)

/**
 * The Agent Dashboard: every live session of every worktree as a card in Needs attention (waiting) / Working
 * (working, or launched without an event yet) / Done. Newest first. Pure; shares the sidebar's session rows.
 */
data class DashboardModel(val columns: List<DashboardColumn>, val projects: List<Pair<String, String>>) {
  /** Total cards across the columns (after the query). */
  val total: Int get() = columns.sumOf { it.count }

  fun column(status: AgentStatus): DashboardColumn = columns.first { it.status == status }

  companion object {
    val ORDER = listOf(AgentStatus.WAITING, AgentStatus.WORKING, AgentStatus.DONE)

    fun of(sidebar: SidebarModel, query: DashboardQuery = DashboardQuery()): DashboardModel {
      val repos = sidebar.groups.flatMap(::reposOf) + sidebar.ungrouped
      val all = repos.flatMap(::cardsOf).distinctBy { it.tabId }
      val needle = query.search.trim().lowercase()
      val shown = all.filter { c ->
        (query.agent == null || c.kind == query.agent) &&
          (query.projectPath == null || c.projectPath == query.projectPath) &&
          (needle.isEmpty() || listOf(c.worktreeTitle, c.worktree, c.branch.orEmpty(), c.project, c.kind.displayName, c.title)
            .any { it.lowercase().contains(needle) })
      }
      val columns = ORDER.map { st -> DashboardColumn(st, shown.filter { it.status == st }.sortedByDescending { it.startedAt }) }
      return DashboardModel(columns, repos.map { it.name to it.repo.path })
    }

    private fun reposOf(g: dev.phantom.ai.sidebar.GroupNode): List<RepoNode> = g.repos + g.groups.flatMap(::reposOf)

    private fun cardsOf(r: RepoNode): List<DashboardCard> = r.worktrees.flatMap { w ->
      w.sessions.filterNot { it.session.tabId.startsWith("saved:") }.map { row ->
        val s = row.session
        DashboardCard(
          s.tabId, w.path, w.title, r.name, r.repo.path, w.branch, w.prNumber, s.kind, row.title,
          s.status ?: AgentStatus.WORKING, s.unread, s.lastPrompt, s.lastAnswer, s.subagents, row.elapsed, s.startedAt,
        )
      }
    }
  }
}
