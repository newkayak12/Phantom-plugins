// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.api

import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.AgentSession
import dev.phantom.ai.core.AgentStatus
import dev.phantom.ai.sidebar.SessionRow
import dev.phantom.ai.sidebar.SidebarModel
import dev.phantom.ai.sidebar.WorktreeNode
import java.nio.file.Files
import java.nio.file.Path

/** Pure mapping from the internal models to the public API types. */
internal object ApiMapping {
  fun agent(kind: AgentKind): AiAgent = when (kind) {
    AgentKind.CLAUDE -> AiAgent.CLAUDE
    AgentKind.CODEX -> AiAgent.CODEX
  }

  fun status(status: AgentStatus?): AiSessionStatus = when (status) {
    AgentStatus.WORKING -> AiSessionStatus.WORKING
    AgentStatus.WAITING -> AiSessionStatus.WAITING
    AgentStatus.DONE -> AiSessionStatus.DONE
    null -> AiSessionStatus.NONE
  }

  fun session(s: AgentSession): AiSession =
    AiSession(
      s.tabId, s.worktree, agent(s.kind), status(s.status), SessionRow.title(s), s.startedAt,
      s.subagents.map { AiSubagent(it.id, it.name, it.running, it.activity) }, s.activity,
    )

  /** prNumber is always null: the model is built with no pull request lookup. */
  fun worktree(w: WorktreeNode): AiWorktree = AiWorktree(w.path, w.title, w.branch, w.prNumber, w.isMain)

  /** The worktrees of every repository of [model], once per path. */
  fun worktrees(model: SidebarModel): List<AiWorktree> =
    model.repos.flatMap { r -> r.worktrees.map(::worktree) }.distinctBy { normalize(it.path) }

  /** The sessions of [all] that sit in one of [worktrees], paths compared normalised. */
  fun sessionsIn(all: List<AgentSession>, worktrees: List<AiWorktree>): List<AiSession> {
    val paths = worktrees.mapTo(HashSet()) { normalize(it.path) }
    return all.filter { normalize(it.worktree) in paths }.map(::session)
  }

  /** Separators unified, trailing slash dropped and symlinks resolved when the path exists. */
  fun normalize(path: String): String {
    val plain = path.replace('\\', '/').trimEnd('/').ifEmpty { "/" }
    return try {
      val p = Path.of(plain)
      if (Files.exists(p)) p.toRealPath().toString().replace('\\', '/').trimEnd('/').ifEmpty { "/" } else plain
    } catch (_: Exception) {
      plain
    }
  }
}
