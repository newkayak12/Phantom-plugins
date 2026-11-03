// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.visualization.theme

import dev.phantom.ai.visualization.model.Orientation
import dev.phantom.ai.visualization.model.VizState
import java.awt.Color

/** Warm Wood: wooden floor, brick walls, a cosy office. Uses only the unsuffixed frames of the sheet (the *_b ones are theme B). */
object ThemeA : Theme {
  override val id = "A"
  override val displayName = "Warm Wood"
  override val atlasImageResource = "/dev/phantom/ai/visualization/themes/a/sprites.png"
  override val atlasIndexResource = "/dev/phantom/ai/visualization/themes/a/sprites.json"

  private fun c(hex: String, a: Int = 255): Color {
    val v = hex.removePrefix("#").toInt(16)
    return Color(v shr 16 and 255, v shr 8 and 255, v and 255, a)
  }

  override val palette = Palette(
    baseSide = c("#5b3523"),
    baseSideShade = c("#44281a"),
    tagBackground = Color(28, 22, 20, 225),
    tagText = Color(246, 238, 224),
    plateText = Color(92, 54, 30),
    state = mapOf(
      VizState.WORKING to c("#4f8cff"),
      VizState.WAITING to c("#e5484d"),
      VizState.DONE to c("#2fb36b"),
      VizState.IDLE to c("#9aa3b5"),
    ),
    canvasBackground = Color(43, 34, 31),
    canvasDot = Color(52, 42, 38),
    surface = Color(36, 29, 27),
    card = Color(52, 42, 38),
    text = Color(244, 234, 216),
    dim = Color(176, 158, 140),
    accent = Color(240, 164, 58),
    accentText = Color(40, 26, 12),
    line = Color(74, 60, 54),
  )

  override val floorTiles = listOf("floor_wood_0", "floor_wood_1", "floor_wood_2")
  override val wallRight = "wall_rw"
  override val wallRightWindow = "wall_rw_window"
  override val wallLeft = "wall_lw"
  override val wallLeftWindow = "wall_lw_window"
  override val wallCorner = "wall_corner"
  override val windowSegmentsRight = setOf(1, 3, 5)
  override val windowSegmentsLeft = setOf(2, 4)

  override fun desk(o: Orientation) = if (o == Orientation.RW) "desk_rw" else "desk_lw"
  override fun chair(o: Orientation) =
    if (o == Orientation.RW) ChairFrames("chair_seat_rw", "chair_back_rw") else ChairFrames("chair_lw", null)

  override val rugFrames = listOf("rug_0", "rug_1", "rug_2")
  override val nameplate = "nameplate"
  override val nameplateSlice = NameplateSlice(leftInset = 12, rightInset = 12, boardHeight = 12)

  override val decor = listOf(
    Decor("whiteboard_lw", 0, 16, layer = 2, key = 0.0),
    Decor("whiteboard_rw", 66, 0, layer = 2, key = 1.0),
    Decor("clock_rw", 39, 0, z = 40, layer = 2, key = 2.0),
    Decor("poster_rw", 7, 0, z = 24, layer = 2, key = 3.0),
    Decor("bookshelf_lw", 0, 1, layer = 5, key = 11.0),
    Decor("bookshelf_lw", 0, 80, layer = 5, key = 90.0),
    Decor("coffee_lw", 0, 48, layer = 5, key = 60.0),
    Decor("bookcase_rw", 17, 0, layer = 5, key = 25.0),
    Decor("coffee_bar_rw", 31, 0, layer = 5, key = 40.0),
    Decor("armchair_lw", 1, 36, layer = 5, key = 45.0),
    Decor("plant_big", 90, 6, layer = 5, key = 96.0),
    Decor("plant_big", 7, 89, layer = 5, key = 96.0),
    Decor("plant_big", 5, 68, layer = 5, key = 73.0),
    Decor("lamp", 91, 90, layer = 5, key = 181.0),
  )

  private val people = OfficePeople("")
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
