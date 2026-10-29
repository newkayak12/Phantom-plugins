// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.visualization.theme

import dev.phantom.ai.visualization.model.Orientation
import dev.phantom.ai.visualization.model.VizState
import java.awt.Color

/**
 * Restroom Cleaning (화장실 청소): white and teal bathroom tiles, tiled walls with mirrors, a toilet and a sink at every desk and a
 * mop bucket for a chair; the people are eight janitors in coveralls, rubber gloves and boots, caps or bandanas, drawn like the
 * office people at twice the scene's density ([personDetail]). Working mops the floor (four frames, the mop sweeps across the body
 * with suds and a wet streak), waiting raises an open hand and holds a plunger, done gives a thumbs-up among sparkles, idle dozes
 * standing, hands and cheek on the mop handle, a snot bubble swelling. Everyone stands with the boots on the anchor; the working
 * and idle ones, which the layout lifts to seat height, so stand a little further back, at the toilet, and the session tag under
 * them stays clear of the mop.
 */
object ThemeD : Theme {
  override val id = "D"
  override val displayName = "Restroom Cleaning"
  override val atlasImageResource = "/dev/phantom/ai/visualization/themes/d/sprites.png"
  override val atlasIndexResource = "/dev/phantom/ai/visualization/themes/d/sprites.json"

  private fun c(hex: String, a: Int = 255): Color {
    val v = hex.removePrefix("#").toInt(16)
    return Color(v shr 16 and 255, v shr 8 and 255, v and 255, a)
  }

  override val palette = Palette(
    baseSide = c("#7d9aa0"),
    baseSideShade = c("#5f7b82"),
    tagBackground = Color(255, 255, 255, 240),
    tagText = Color(18, 40, 44),
    plateText = Color(43, 34, 0),
    state = mapOf(
      VizState.WORKING to c("#4f8cff"),
      VizState.WAITING to c("#e5484d"),
      VizState.DONE to c("#2fb36b"),
      VizState.IDLE to c("#9aa3b5"),
    ),
    canvasBackground = Color(227, 238, 239),
    canvasDot = Color(211, 227, 229),
    surface = Color(248, 251, 251),
    card = Color(224, 236, 237),
    text = Color(18, 40, 44),
    dim = Color(78, 102, 108),
    accent = Color(14, 116, 108),
    accentText = Color(255, 255, 255),
    line = Color(198, 217, 219),
  )

  override val floorTiles = listOf("floor_tile_0_d", "floor_tile_1_d")
  override val wallRight = "wall_rw_d"
  override val wallRightWindow = "wall_rw_window_d"
  override val wallLeft = "wall_lw_d"
  override val wallLeftWindow = "wall_lw_window_d"
  override val wallCorner = "wall_corner_d"

  /** The "windows" are mirrors: two on the right wall, one on the left above the sink counter. */
  override val windowSegmentsRight = setOf(1, 3)
  override val windowSegmentsLeft = setOf(3)

  override fun desk(o: Orientation) = if (o == Orientation.RW) "desk_rw_d" else "desk_lw_d"

  /** The bucket stands beside the spot, away from the toilet; nothing is drawn over the janitor. */
  override fun chair(o: Orientation) = ChairFrames(if (o == Orientation.RW) "chair_rw_d" else "chair_lw_d", null)

  /** Subordinates stand and mop beside their lead like everyone here: no bucket of their own, so two fit beside a lead. */
  override val helperStool: String? get() = null

  /** The janitors are as tall as the toilet stalls around them: a helper may stand in front of its own area's fixtures. */
  override val helperMayCoverOwnDesks: Boolean get() = true

  override val rugFrames = listOf("rug_0_d", "rug_1_d", "rug_2_d")
  override val nameplate = "nameplate_d"
  override val nameplateSlice = NameplateSlice(leftInset = 12, rightInset = 12, boardHeight = 12)

  override val decor = listOf(
    Decor("urinals_lw_d", 0, 16, layer = 2, key = 0.0),
    Decor("urinals_rw_d", 66, 0, layer = 2, key = 1.0),
    Decor("dryer_rw_d", 39, 0, z = 24, layer = 2, key = 2.0),
    Decor("towel_rw_d", 7, 0, z = 22, layer = 2, key = 3.0),
    Decor("dryer_rw_d", 88, 0, z = 24, layer = 2, key = 4.0),
    Decor("supply_lw_d", 0, 1, layer = 5, key = 11.0),
    Decor("supply_lw_d", 0, 80, layer = 5, key = 90.0),
    Decor("vanity_lw_d", 0, 48, layer = 5, key = 60.0),
    Decor("wetsign_d", 90, 6, layer = 5, key = 96.0),
    Decor("plant_big_d", 7, 89, layer = 5, key = 96.0),
    Decor("bin_d", 5, 68, layer = 5, key = 73.0),
    Decor("cart_d", 91, 90, layer = 5, key = 181.0),
    // a corgi with a sponge by the wet-floor sign, an orange tabby beside the cleaning cart
    Decor("dog_d", 92, 18, layer = 5, key = 110.0),
    Decor("cat_d", 80, 93, layer = 5, key = 173.0),
  )

  override val personVariants = 8

  /** The janitors are drawn at twice the scene's pixel density, in the style of the office people (art/theme_d.py). */
  override val personDetail = 2
  override fun person(variant: Int, state: VizState) = personFrame(variant, state, 0)
  override fun bubble(state: VizState) = "bubble_${state.key}"

  /** Distinct frames per state: the mop swing has four, the plunger wave, the sparkles and the dozing two. */
  fun framesOf(state: VizState): Int = if (state == VizState.WORKING) 4 else 2

  override fun personFrame(variant: Int, state: VizState, phase: Int) =
    "person${variant}_${state.key}_${Math.floorMod(phase, framesOf(state))}_d"

  /** The frames animate themselves; no jitter on top. */
  override fun personOffset(state: VizState, phase: Int) = Offset.ZERO
}
