// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.visualization.theme

import dev.phantom.ai.visualization.model.Orientation
import dev.phantom.ai.visualization.model.VizState
import java.awt.Color

/**
 * Restaurant Kitchen (주방): red quarry tiles, white subway-tile walls with pass windows to the dining room, steel cooking stations
 * on rubber mats, and cooks instead of office workers: ten looks in white jackets, aprons and toques, bandanas or caps, drawn at
 * twice the scene's density on the office people's heads and faces. Every state has four real animation frames (art/theme_e.py): working is a
 * cook at the station seen from behind tossing a wok over flames or chopping with a cleaver, waiting rings a hand bell held high
 * and calls out, done presents a plate under a cloche with steam rising, idle dozes on an upturned crate.
 */
object ThemeE : Theme {
  override val id = "E"
  override val displayName = "Restaurant Kitchen"
  override val atlasImageResource = "/dev/phantom/ai/visualization/themes/e/sprites.png"
  override val atlasIndexResource = "/dev/phantom/ai/visualization/themes/e/sprites.json"

  private fun c(hex: String, a: Int = 255): Color {
    val v = hex.removePrefix("#").toInt(16)
    return Color(v shr 16 and 255, v shr 8 and 255, v and 255, a)
  }

  override val palette = Palette(
    baseSide = c("#5a2a20"),
    baseSideShade = c("#43201a"),
    tagBackground = Color(30, 32, 36, 230),
    tagText = Color(244, 244, 240),
    plateText = Color(58, 38, 30),
    state = mapOf(
      VizState.WORKING to c("#4f8cff"),
      VizState.WAITING to c("#e5484d"),
      VizState.DONE to c("#2fb36b"),
      VizState.IDLE to c("#9aa3b5"),
    ),
    canvasBackground = Color(36, 38, 42),
    canvasDot = Color(46, 49, 54),
    surface = Color(30, 32, 36),
    card = Color(46, 49, 54),
    text = Color(240, 240, 236),
    dim = Color(170, 176, 186),
    accent = Color(232, 89, 12),
    accentText = Color(255, 255, 255),
    line = Color(66, 70, 78),
  )

  override val floorTiles = listOf("floor_quarry_0_e", "floor_quarry_1_e", "floor_quarry_2_e")
  override val wallRight = "wall_rw_e"
  override val wallRightWindow = "wall_rw_window_e"
  override val wallLeft = "wall_lw_e"
  override val wallLeftWindow = "wall_lw_window_e"
  override val wallCorner = "wall_corner_e"
  override val windowSegmentsRight = setOf(1, 3, 5)
  override val windowSegmentsLeft = setOf(2, 4)

  override fun desk(o: Orientation) = if (o == Orientation.RW) "desk_rw_e" else "desk_lw_e"

  /** No chair: a duckboard on the floor that the cook stands on; the idle cook brings their own crate (drawn in the frame). */
  override fun chair(o: Orientation) = ChairFrames(if (o == Orientation.RW) "duckboard_rw_e" else "duckboard_lw_e", null)

  override val rugFrames = listOf("mat_0_e", "mat_1_e")
  override val nameplate = "nameplate_e"
  override val nameplateSlice = NameplateSlice(leftInset = 12, rightInset = 12, boardHeight = 12)

  override val decor = listOf(
    Decor("orders_lw_e", 0, 16, layer = 2, key = 0.0),
    Decor("ladles_rw_e", 66, 0, layer = 2, key = 1.0),
    Decor("clock_rw", 39, 0, z = 40, layer = 2, key = 2.0),
    Decor("knives_rw_e", 7, 0, z = 26, layer = 2, key = 3.0),
    Decor("fridge_lw_e", 0, 1, layer = 5, key = 11.0),
    Decor("potshelf_lw_e", 0, 80, layer = 5, key = 90.0),
    Decor("rice_lw_e", 0, 48, layer = 5, key = 60.0),
    Decor("extinguisher_e", 90, 6, layer = 5, key = 96.0),
    Decor("bin_e", 7, 89, layer = 5, key = 96.0),
    Decor("crates_e", 5, 68, layer = 5, key = 73.0),
    Decor("crates_e", 91, 90, layer = 5, key = 181.0),
  )

  /** Helpers stand and cook like their parent; a stool would sit behind their legs. */
  override val helperStool: String? get() = null

  /** The counters fill each area and a cook works at one: a sous-chef may stand in front of the area's counters. */
  override val helperMayCoverOwnDesks: Boolean get() = true

  override val personVariants = 10

  /** The cooks are drawn at twice the scene's density, like A/B/C's office people (art/theme_e.py on art/people_hd.py). */
  override val personDetail = 2
  override fun person(variant: Int, state: VizState) = personFrame(variant, state, 0)
  override fun personFrame(variant: Int, state: VizState, phase: Int) =
    "person${variant}_e${Math.floorMod(phase, animationPhases)}_${state.key}"

  /** A cook working at a station along v is the working frame mirrored (`_lw`), so the wok or board is over that station. */
  override fun personFrame(variant: Int, state: VizState, phase: Int, facing: Orientation?) =
    personFrame(variant, state, phase) + if (state == VizState.WORKING && facing == Orientation.LW) "_lw" else ""

  /** The frames move by themselves (wok toss, bell, steam, breathing): no extra shift. */
  override fun personOffset(state: VizState, phase: Int) = Offset.ZERO
  override fun bubble(state: VizState) = "bubble_${state.key}"
}
