// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.visualization.theme

import dev.phantom.ai.visualization.model.Orientation
import dev.phantom.ai.visualization.model.VizState
import java.awt.Color

/**
 * Construction Site (공사장): packed dirt and gravel, poured concrete walls with rebar and chain-link fence sections over a sky
 * with a tower crane, sawhorse workbenches and brick walls going up, a crew in hard hats and hi-vis vests. Each state is an
 * action: hammering or laying bricks (working), waving a red flag (waiting), thumbs up with a checklist (done), dozing with the
 * hard hat over the face (idle). Drawn by art/theme_f.py.
 */
object ThemeF : Theme {
  override val id = "F"
  override val displayName = "Construction Site"
  override val atlasImageResource = "/dev/phantom/ai/visualization/themes/f/sprites.png"
  override val atlasIndexResource = "/dev/phantom/ai/visualization/themes/f/sprites.json"

  private fun c(hex: String, a: Int = 255): Color {
    val v = hex.removePrefix("#").toInt(16)
    return Color(v shr 16 and 255, v shr 8 and 255, v and 255, a)
  }

  override val palette = Palette(
    baseSide = c("#6b4a2e"),
    baseSideShade = c("#523822"),
    tagBackground = Color(30, 28, 25, 228),
    tagText = Color(250, 240, 214),
    plateText = Color(36, 33, 31),
    state = mapOf(
      VizState.WORKING to c("#4f8cff"),
      VizState.WAITING to c("#e5484d"),
      VizState.DONE to c("#2fb36b"),
      VizState.IDLE to c("#9aa3b5"),
    ),
    canvasBackground = Color(42, 39, 35),
    canvasDot = Color(54, 50, 44),
    surface = Color(34, 32, 29),
    card = Color(52, 48, 42),
    text = Color(246, 238, 220),
    dim = Color(178, 166, 146),
    accent = Color(255, 196, 30),
    accentText = Color(36, 30, 20),
    line = Color(78, 72, 62),
  )

  override val floorTiles = listOf("floor_dirt_0_f", "floor_dirt_1_f", "floor_dirt_2_f", "floor_dirt_3_f")
  override val wallRight = "wall_rw_f"
  override val wallRightWindow = "wall_rw_fence_f"
  override val wallLeft = "wall_lw_f"
  override val wallLeftWindow = "wall_lw_fence_f"
  override val wallCorner = "wall_corner_f"
  override val windowSegmentsRight = setOf(1, 3, 5)
  override val windowSegmentsLeft = setOf(2, 4)

  override fun desk(o: Orientation) = if (o == Orientation.RW) "bench_rw_f" else "brickwall_lw_f"
  override fun chair(o: Orientation) =
    if (o == Orientation.RW) ChairFrames("bucket_f", null) else ChairFrames("cinder_lw_f", null)

  /** The sawhorse benches reach far down their deck and the crew works at them: a helper may stand in front of one. */
  override val helperMayCoverOwnDesks: Boolean get() = true

  override val rugFrames = listOf("deck_0_f", "deck_1_f", "deck_2_f")
  override val nameplate = "sign_f"
  override val nameplateSlice = NameplateSlice(leftInset = 12, rightInset = 12, boardHeight = 12)

  override val decor = listOf(
    Decor("sitemap_lw_f", 0, 16, layer = 2, key = 0.0),
    Decor("sitemap_rw_f", 66, 0, layer = 2, key = 1.0),
    Decor("safety_rw_f", 39, 0, z = 40, layer = 2, key = 2.0),
    Decor("danger_rw_f", 7, 0, z = 24, layer = 2, key = 3.0),
    Decor("bricks_lw_f", 0, 1, layer = 5, key = 11.0),
    Decor("scaffold_lw_f", 0, 80, layer = 5, key = 90.0),
    Decor("mixer_lw_f", 0, 48, layer = 5, key = 60.0),
    Decor("cones_f", 90, 6, layer = 5, key = 96.0),
    Decor("cones_f", 7, 89, layer = 5, key = 96.0),
    Decor("barrow_f", 5, 68, layer = 5, key = 73.0),
    Decor("worklight_f", 91, 90, layer = 5, key = 181.0),
  )

  override val personVariants = 6

  /** The idle pose has one frame; the others four, all of one size and anchor per variant and state. */
  override fun person(variant: Int, state: VizState) = personFrame(variant, state, 0)
  override fun personFrame(variant: Int, state: VizState, phase: Int): String {
    val ph = if (state == VizState.IDLE) 0 else Math.floorMod(phase, 4)
    return "crew${Math.floorMod(variant, personVariants)}_${state.key}_${ph}_f"
  }

  /** The frames carry the motion (hammer swing, flag, thumb), so no extra jitter. */
  override fun personOffset(state: VizState, phase: Int) = Offset.ZERO
  override fun bubble(state: VizState) = "bubble_${state.key}"
}
