// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.visualization.theme

import dev.phantom.ai.visualization.model.Orientation
import dev.phantom.ai.visualization.model.VizState
import java.awt.Color

/** Modern Bright: light gray tiles, white plaster walls with teal trim, white desks, teal chairs, glass partitions between desk areas. */
object ThemeB : Theme {
  override val id = "B"
  override val displayName = "Modern Bright"
  override val atlasImageResource = "/dev/phantom/ai/visualization/themes/b/sprites.png"
  override val atlasIndexResource = "/dev/phantom/ai/visualization/themes/b/sprites.json"

  private fun c(hex: String, a: Int = 255): Color {
    val v = hex.removePrefix("#").toInt(16)
    return Color(v shr 16 and 255, v shr 8 and 255, v and 255, a)
  }

  override val palette = Palette(
    baseSide = c("#8c97a5"),
    baseSideShade = c("#6f7584"),
    tagBackground = Color(255, 255, 255, 240),
    tagText = Color(22, 33, 50),
    plateText = Color(14, 116, 108),
    state = mapOf(
      VizState.WORKING to c("#4f8cff"),
      VizState.WAITING to c("#e5484d"),
      VizState.DONE to c("#2fb36b"),
      VizState.IDLE to c("#9aa3b5"),
    ),
    canvasBackground = Color(233, 238, 242),
    canvasDot = Color(218, 225, 231),
    surface = Color(250, 251, 252),
    card = Color(230, 235, 240),
    text = Color(22, 33, 50),
    dim = Color(100, 114, 135),
    accent = Color(20, 184, 166),
    accentText = Color(255, 255, 255),
    line = Color(208, 216, 225),
  )

  override val floorTiles = listOf("floor_tile_0", "floor_tile_1")
  override val wallRight = "wall_rw_b"
  override val wallRightWindow = "wall_rw_window_b"
  override val wallLeft = "wall_lw_b"
  override val wallLeftWindow = "wall_lw_window_b"
  override val wallCorner = "wall_corner_b"
  override val windowSegmentsRight = setOf(1, 3, 5)
  override val windowSegmentsLeft = setOf(2, 4)

  override fun desk(o: Orientation) = if (o == Orientation.RW) "desk_rw_b" else "desk_lw_b"
  override fun chair(o: Orientation) =
    if (o == Orientation.RW) ChairFrames("chair_seat_rw_b", "chair_back_rw_b") else ChairFrames("chair_lw_b", null)
  override fun partition(o: Orientation) = if (o == Orientation.RW) "glass_rw_b" else "glass_lw_b"

  override val rugFrames = listOf("rug_0_b", "rug_2_b")
  override val nameplate = "nameplate_b"
  override val nameplateSlice = NameplateSlice(leftInset = 12, rightInset = 12, boardHeight = 12)

  override val decor = listOf(
    Decor("whiteboard_lw", 0, 16, layer = 2, key = 0.0),
    Decor("whiteboard_rw", 66, 0, layer = 2, key = 1.0),
    Decor("clock_rw", 39, 0, z = 40, layer = 2, key = 2.0),
    Decor("poster_rw", 7, 0, z = 24, layer = 2, key = 3.0),
    Decor("bookshelf_lw_b", 0, 1, layer = 5, key = 11.0),
    Decor("bookshelf_lw_b", 0, 80, layer = 5, key = 90.0),
    Decor("coffee_lw_b", 0, 48, layer = 5, key = 60.0),
    Decor("plant_big_b", 90, 6, layer = 5, key = 96.0),
    Decor("plant_big_b", 7, 89, layer = 5, key = 96.0),
    Decor("plant_big_b", 5, 68, layer = 5, key = 73.0),
    Decor("lamp", 91, 90, layer = 5, key = 181.0),
  )

  override val personVariants = 3
  override fun person(variant: Int, state: VizState) = "person${variant}_${state.key}"
  override fun bubble(state: VizState) = "bubble_${state.key}"
}
