// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.visualization.theme

import dev.phantom.ai.visualization.model.Orientation
import dev.phantom.ai.visualization.model.VizState

/**
 * The office people of themes A, B and C (art/people_chibi.py), drawn at [DETAIL] x the scene's density. Frame names, [suffix] being
 * the theme's ("" or "_c"): seated working `person<v>_working_<0..3>[_lw]` (typing; seen from behind at a desk along u, or with
 * `_lw` facing us from behind a desk along v, the desk's near part drawn over them), asleep `person<v>_idle[_lw]`, standing `person<v>_waiting_<0|1>` (a hand raised) and
 * `person<v>_done_<0|1>` (a mug), the standing ones switching frame every second phase.
 */
internal class OfficePeople(private val suffix: String) {
  fun person(v: Int, s: VizState): String = frame(v, s, 0, null)

  fun frame(v: Int, s: VizState, phase: Int, facing: Orientation?): String {
    val lw = if (facing == Orientation.LW) "_lw" else ""
    return when (s) {
      VizState.WORKING -> "person${v}_working_${Math.floorMod(phase, 4)}$lw$suffix"
      VizState.IDLE -> "person${v}_idle$lw$suffix"
      else -> "person${v}_${s.key}_${Math.floorMod(phase, 4) / 2}$suffix"
    }
  }

  /** One facing us at a desk along v sits [FRONT_DX] scene pixels left of the chair point (behind the desk); the bubble follows. */
  fun bubbleAnchorDx(facing: Orientation?, default: Int): Int = if (facing == Orientation.LW) FRONT_DX + 1 else default

  companion object {
    const val FRONT_DX = -30
    const val VARIANTS = 10
    const val DETAIL = 2
  }
}
