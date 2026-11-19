// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.visualization.model

import dev.phantom.ai.api.AiSessionStatus

/** What a person looks like: the four session states, each with its own pose and bubble. [key] is the frame-name suffix. */
enum class VizState(val key: String) {
  WORKING("working"),
  WAITING("waiting"),
  DONE("done"),
  IDLE("idle"),
  ;

  companion object {
    fun of(status: AiSessionStatus): VizState = when (status) {
      AiSessionStatus.WORKING -> WORKING
      AiSessionStatus.WAITING -> WAITING
      AiSessionStatus.DONE -> DONE
      AiSessionStatus.NONE -> IDLE
    }
  }
}

/** Which wall a desk faces: [RW] runs along the right (back) wall's axis, [LW] along the left one. */
enum class Orientation { RW, LW }
