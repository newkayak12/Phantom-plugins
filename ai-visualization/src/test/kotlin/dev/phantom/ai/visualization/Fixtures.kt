// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.visualization

import dev.phantom.ai.api.AiAgent
import dev.phantom.ai.api.AiSession
import dev.phantom.ai.api.AiSessionStatus
import dev.phantom.ai.api.AiWorktree
import dev.phantom.ai.visualization.model.DeskArea
import dev.phantom.ai.visualization.model.Floor
import dev.phantom.ai.visualization.model.OfficeModel
import dev.phantom.ai.visualization.model.PathKey
import dev.phantom.ai.visualization.model.ProjectData
import dev.phantom.ai.visualization.model.VizState

object Fixtures {
  fun wt(i: Int, main: Boolean = false) = AiWorktree("/repo/wt$i", "wt$i", "b$i", null, main)

  fun session(tab: String, path: String, status: AiSessionStatus, at: Long = 1000L) =
    AiSession(tab, path, AiAgent.CLAUDE, status, "title $tab", at)

  /**
   * A floor per project with a desk area per worktree, its sessions oldest first: for tests of the room's layout and drawing,
   * which place any areas a floor may have (a floor of [OfficeModel.build] is one worktree's CEO, teams and open desks).
   */
  fun byWorktree(projects: List<ProjectData>): OfficeModel = OfficeModel(
    projects.mapIndexed { i, p ->
      val areas = OfficeModel.orderedWorktrees(p.worktrees).map { w ->
        val own = p.sessions.filter { PathKey.of(it.worktreePath) == PathKey.of(w.path) }.sortedWith(compareBy({ it.startedAt }, { it.tabId }))
        DeskArea(w, own.map { OfficeModel.person(it) })
      }
      Floor(i + 1, p.key, p.name, areas, VizState.values().associateWith { st -> p.sessions.count { VizState.of(it.status) == st } })
    },
  )

  /** [n] worktrees, the first with one session in each of the four states. */
  fun areas(n: Int): List<DeskArea> {
    val model = byWorktree(
      listOf(
        ProjectData(
          "/repo", "repo", (0 until n).map { wt(it, it == 0) },
          AiSessionStatus.values().mapIndexed { i, st -> session("t$i", "/repo/wt0", st, 100L + i) },
        ),
      ),
    )
    return model.floors[0].areas
  }
}
