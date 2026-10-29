// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.visualization

import dev.phantom.ai.visualization.model.ItemKind
import dev.phantom.ai.visualization.model.OfficeLayout
import dev.phantom.ai.visualization.model.Orientation
import dev.phantom.ai.visualization.model.VizState
import dev.phantom.ai.visualization.theme.Atlas
import dev.phantom.ai.visualization.theme.ChairFrames
import dev.phantom.ai.visualization.theme.Decor
import dev.phantom.ai.visualization.theme.Frame
import dev.phantom.ai.visualization.theme.NameplateSlice
import dev.phantom.ai.visualization.theme.Theme
import dev.phantom.ai.visualization.theme.ThemeA
import dev.phantom.ai.visualization.theme.Themes
import java.awt.image.BufferedImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A second theme with renamed frames, its own tiny atlas and its own palette works with the unchanged model and layout. */
class ThemeSeamTest {
  /** Fake theme "Z": every frame renamed, one distinct size and anchor per frame, sheet built in memory. */
  private object ThemeZ : Theme {
    override val id = "Z"
    override val displayName = "Zed"
    override val atlasImageResource = "/none.png"
    override val atlasIndexResource = "/none.json"
    override val palette = ThemeA.palette.copy(baseSide = java.awt.Color.MAGENTA)
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
  }

  private fun fakeAtlas(): Atlas {
    val names = ThemeZ.frameNames().sorted()
    val frames = names.mapIndexed { i, n -> n to Frame(n, i * 10, 0, 8, 8 + i % 3, 4, 7) }.toMap()
    return Atlas(BufferedImage(names.size * 10, 12, BufferedImage.TYPE_INT_ARGB), frames).validate(ThemeZ)
  }

  @Test
  fun fakeThemeLaysOutWithNoLayoutChange() {
    val atlas = fakeAtlas()
    val scene = OfficeLayout(ThemeZ, atlas).layout(Fixtures.areas(3))
    assertEquals(3, scene.areas.size)
    val used = scene.items.mapNotNull { it.frame }.toSet()
    assertTrue(used.isNotEmpty())
    assertTrue("frames of theme A leaked: ${used.filter { !it.startsWith("z_") }}", used.all { it.startsWith("z_") })
    assertTrue(scene.items.any { it.frame == "z_wrw" } && scene.items.none { it.frame == "z_wlw" })
    assertEquals(4, scene.people.size)
    for (p in scene.people) {
      val f = atlas[p.frame]
      assertEquals(f.w, p.bounds.w)
      assertEquals(p.y - f.anchorY, p.bounds.y)
      assertTrue(p.frame.startsWith("z_p") && p.frame.endsWith(p.state.key))
    }
    assertEquals(3, scene.items.count { it.kind == ItemKind.NAMEPLATE })
  }

  @Test
  fun anAtlasMissingAFrameOfTheFakeThemeIsRejected() {
    val atlas = fakeAtlas()
    val e = runCatching { Atlas(atlas.image, atlas.frames - "z_plate").validate(ThemeZ) }.exceptionOrNull()
    assertTrue(e is IllegalStateException && "z_plate" in e.message!!)
  }

  @Test
  fun registryStillHoldsOnlyThemeA() {
    assertEquals(listOf(ThemeA), Themes.all)
  }
}
