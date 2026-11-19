// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.visualization.theme

import dev.phantom.ai.visualization.model.VizState
import java.awt.Color

/**
 * Everything a theme colors outside its sprites: the slab edge, tags, plate text, state colors and the chrome around the
 * canvas (floor picker, legend). A new theme supplies its own; no renderer code names a color.
 */
data class Palette(
  val baseSide: Color,
  val baseSideShade: Color,
  val tagBackground: Color,
  val tagText: Color,
  val plateText: Color,
  val state: Map<VizState, Color>,
  val canvasBackground: Color,
  val canvasDot: Color,
  val surface: Color,
  val card: Color,
  val text: Color,
  val dim: Color,
  val accent: Color,
  val accentText: Color,
  val line: Color,
) {
  fun stateColor(s: VizState): Color = state.getValue(s)
}
