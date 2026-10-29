// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.visualization

import dev.phantom.ai.api.AiAgent
import dev.phantom.ai.api.AiSession
import dev.phantom.ai.api.AiSessionStatus
import dev.phantom.ai.api.AiWorktree
import dev.phantom.ai.visualization.model.DeskArea
import dev.phantom.ai.visualization.model.OfficeModel

object Fixtures {
  fun wt(i: Int, main: Boolean = false) = AiWorktree("/repo/wt$i", "wt$i", "b$i", null, main)

  fun session(tab: String, path: String, status: AiSessionStatus, at: Long = 1000L) =
    AiSession(tab, path, AiAgent.CLAUDE, status, "title $tab", at)

  /** [n] worktrees, the first with one session in each of the four states. */
  fun areas(n: Int): List<DeskArea> {
    val model = OfficeModel.build(
      listOf(
        dev.phantom.ai.visualization.model.ProjectData(
          "/repo", "repo", (0 until n).map { wt(it, it == 0) },
          AiSessionStatus.values().mapIndexed { i, st -> session("t$i", "/repo/wt0", st, 100L + i) },
        ),
      ),
    )
    return model.floors[0].areas
  }
}
