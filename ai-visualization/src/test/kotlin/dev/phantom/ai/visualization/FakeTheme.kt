// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.visualization

import dev.phantom.ai.visualization.model.Orientation
import dev.phantom.ai.visualization.model.VizState
import dev.phantom.ai.visualization.theme.Atlas
import dev.phantom.ai.visualization.theme.ChairFrames
import dev.phantom.ai.visualization.theme.Decor
import dev.phantom.ai.visualization.theme.Frame
import dev.phantom.ai.visualization.theme.NameplateSlice
import dev.phantom.ai.visualization.theme.Offset
import dev.phantom.ai.visualization.theme.Theme
import dev.phantom.ai.visualization.theme.ThemeA
import java.awt.Color
import java.awt.image.BufferedImage

/** A second theme with every frame renamed, its own palette, animation shifts and a tiny in-memory atlas (one flat color per frame). */
object FakeTheme : Theme {
  override val id = "Z"
  override val displayName = "Zed"
  override val atlasImageResource = "/none.png"
  override val atlasIndexResource = "/none.json"
  override val palette = ThemeA.palette.copy(baseSide = Color(255, 0, 255), baseSideShade = Color(120, 0, 120))
  override val floorTiles = listOf("z_floor")
  override val wallRight = "z_wr"
  override val wallRightWindow = "z_wrw"
  override val wallLeft = "z_wl"
  override val wallLeftWindow = "z_wlw"
  override val wallCorner = "z_corner"
  override val windowSegmentsRight = setOf(2)
  override val windowSegmentsLeft = emptySet<Int>()
  override fun desk(o: Orientation) = "z_desk_${o.name}"
  override fun chair(o: Orientation) = ChairFrames("z_seat_${o.name}", null)
  override val rugFrames = listOf("z_rug")
  override val nameplate = "z_plate"
  override val nameplateSlice = NameplateSlice(4, 4, 6)
  override val decor = listOf(Decor("z_plant", 10, 10, layer = 5, key = 20.0))
  override val personVariants = 2
  override fun person(variant: Int, state: VizState) = "z_p$variant${state.key}"
  override fun bubble(state: VizState) = "z_b_${state.key}"
  override val animationPhases = 2
  override fun personFrame(variant: Int, state: VizState, phase: Int) = "z_p$variant${state.key}"
  override fun personOffset(state: VizState, phase: Int) = Offset(0, 0)

  /** The color of [name] in the fake atlas: distinct per frame and opaque. */
  fun colorOf(name: String): Int = (0xff shl 24) or (1 + names().indexOf(name)) * 0x0b0d17 and 0xffffff or (0xff shl 24)

  private fun names() = frameNames().sorted()

  fun atlas(): Atlas {
    val names = names()
    val frames = names.mapIndexed { i, n -> n to Frame(n, i * 10, 0, 8, 8 + i % 3, 4, 7) }.toMap()
    val img = BufferedImage(names.size * 10, 12, BufferedImage.TYPE_INT_ARGB)
    for (f in frames.values) for (y in 0 until f.h) for (x in 0 until f.w) img.setRGB(f.x + x, f.y + y, colorOf(f.name))
    return Atlas(img, frames).validate(this)
  }
}
