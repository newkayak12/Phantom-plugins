// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.visualization.theme

import dev.phantom.ai.visualization.model.Orientation
import dev.phantom.ai.visualization.model.VizState
import java.awt.Color

/**
 * Night Shift: theme A's office at night. Windows show a starry sky, the moon and a lit skyline; the room is dark, monitors cast
 * cool light and the floor lamp, the coffee machine and the backlit name plates warm pools. Dark chrome with an indigo accent.
 */
object ThemeC : Theme {
  override val id = "C"
  override val displayName = "Night Shift"
  override val atlasImageResource = "/dev/phantom/ai/visualization/themes/c/sprites.png"
  override val atlasIndexResource = "/dev/phantom/ai/visualization/themes/c/sprites.json"

  private fun c(hex: String, a: Int = 255): Color {
    val v = hex.removePrefix("#").toInt(16)
    return Color(v shr 16 and 255, v shr 8 and 255, v and 255, a)
  }

  override val palette = Palette(
    baseSide = Color(27, 16, 17),
    baseSideShade = Color(20, 12, 13),
    tagBackground = Color(14, 16, 32, 230),
    tagText = Color(230, 236, 255),
    plateText = Color(92, 54, 30),
    state = mapOf(
      VizState.WORKING to c("#4f8cff"),
      VizState.WAITING to c("#e5484d"),
      VizState.DONE to c("#2fb36b"),
      VizState.IDLE to c("#9aa3b5"),
    ),
    canvasBackground = Color(12, 15, 30),
    canvasDot = Color(22, 27, 52),
    surface = Color(15, 18, 34),
    card = Color(24, 29, 54),
    text = Color(226, 232, 255),
    dim = Color(136, 146, 186),
    accent = Color(92, 104, 236),
    accentText = Color(255, 255, 255),
    line = Color(36, 44, 76),
  )

  override val floorTiles = listOf("floor_wood_0_c", "floor_wood_1_c", "floor_wood_2_c")
  override val wallRight = "wall_rw_c"
  override val wallRightWindow = "wall_rw_window_c"
  override val wallLeft = "wall_lw_c"
  override val wallLeftWindow = "wall_lw_window_c"
  override val wallCorner = "wall_corner_c"
  override val windowSegmentsRight = setOf(1, 3, 5)
  override val windowSegmentsLeft = setOf(2, 4)

  override fun desk(o: Orientation) = if (o == Orientation.RW) "desk_rw_c" else "desk_lw_c"
  override fun chair(o: Orientation) =
    if (o == Orientation.RW) ChairFrames("chair_seat_rw_c", "chair_back_rw_c") else ChairFrames("chair_lw_c", null)

  override val rugFrames = listOf("rug_0_c", "rug_1_c", "rug_2_c")
  override val nameplate = "nameplate_c"
  override val nameplateSlice = NameplateSlice(leftInset = 12, rightInset = 12, boardHeight = 12)

  override val decor = listOf(
    Decor("whiteboard_lw_c", 0, 16, layer = 2, key = 0.0),
    Decor("whiteboard_rw_c", 66, 0, layer = 2, key = 1.0),
    Decor("clock_rw_c", 39, 0, z = 40, layer = 2, key = 2.0),
    Decor("poster_rw_c", 7, 0, z = 24, layer = 2, key = 3.0),
    Decor("bookshelf_lw_c", 0, 1, layer = 5, key = 11.0),
    Decor("bookshelf_lw_c", 0, 80, layer = 5, key = 90.0),
    Decor("coffee_lw_c", 0, 48, layer = 5, key = 60.0),
    Decor("bookcase_rw_c", 17, 0, layer = 5, key = 25.0),
    Decor("coffee_bar_rw_c", 31, 0, layer = 5, key = 40.0),
    Decor("armchair_lw_c", 1, 36, layer = 5, key = 45.0),
    Decor("plant_big_c", 90, 6, layer = 5, key = 96.0),
    Decor("plant_big_c", 7, 89, layer = 5, key = 96.0),
    Decor("plant_big_c", 5, 68, layer = 5, key = 73.0),
    Decor("lamp_c", 91, 90, layer = 5, key = 181.0),
  )

  private val monitor = Tint(0.30f, 0.44f, 0.63f)

  /** The sheet is shaded by the same ambient as art/sprites.py's NIGHT_AMBIENT; each light sits where its glow is in the frame. */
  override val lighting = Lighting(
    ambient = Tint(0.30f, 0.32f, 0.50f),
    sources = listOf(
      LightSource("desk_rw_c", 16, -12, radius = 72, color = monitor, strength = 1.3f),
      LightSource("desk_lw_c", -16, -12, radius = 72, color = monitor, strength = 1.3f),
      LightSource("lamp_c", 0, -31, radius = 85, color = Tint(0.55f, 0.40f, 0.23f), strength = 1.0f),
      LightSource("coffee_lw_c", 0, -12, radius = 34, color = Tint(0.55f, 0.25f, 0.22f), strength = 0.5f),
      LightSource("coffee_bar_rw_c", 10, -22, radius = 40, color = Tint(0.55f, 0.40f, 0.23f), strength = 0.6f),
      LightSource("desk_rw_c", 34, -6, radius = 44, color = Tint(0.60f, 0.44f, 0.22f), strength = 0.9f),
      LightSource("nameplate_c", 0, -8, radius = 30, color = Tint(0.55f, 0.47f, 0.33f), strength = 0.6f),
    ),
  )

  private val people = OfficePeople("_c")
  override val personVariants = OfficePeople.VARIANTS
  override val personDetail = OfficePeople.DETAIL
  override fun person(variant: Int, state: VizState) = people.person(variant, state)
  override fun personFrame(variant: Int, state: VizState, phase: Int) = people.frame(variant, state, phase, null)
  override fun personFrame(variant: Int, state: VizState, phase: Int, facing: Orientation?) = people.frame(variant, state, phase, facing)
  override fun bubbleAnchorDx(facing: Orientation?) = people.bubbleAnchorDx(facing, bubbleAnchorDx)

  /** The frames animate the people themselves. */
  override fun personOffset(state: VizState, phase: Int) = Offset.ZERO
  override fun bubble(state: VizState) = "bubble_${state.key}"

  /** The working pose reaches its right arm to a keyboard, wider than any free spot clear of the area's desks: a helper may stand in front of one. */
  override val helperMayCoverOwnDesks: Boolean get() = true
}
