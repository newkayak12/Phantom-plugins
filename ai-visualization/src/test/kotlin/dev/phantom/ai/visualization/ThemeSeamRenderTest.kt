// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.visualization

import dev.phantom.ai.api.AiSessionStatus
import dev.phantom.ai.visualization.model.OfficeLayout
import dev.phantom.ai.visualization.model.OfficeModel
import dev.phantom.ai.visualization.model.ProjectData
import dev.phantom.ai.visualization.render.OfficeRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A fake second theme (own atlas, palette, frame names) goes through the unchanged OfficeRenderer. */
class ThemeSeamRenderTest {
  private val atlas = FakeTheme.atlas()
  private val renderer = OfficeRenderer(FakeTheme, atlas)

  @Test
  fun fakeAtlasPixelAppearsAtAPersonAnchor() {
    val floor = Fixtures.byWorktree(
      listOf(ProjectData("/repo", "repo", listOf(Fixtures.wt(0, true)), listOf(Fixtures.session("t0", "/repo/wt0", AiSessionStatus.WAITING)))),
    ).floors[0]
    val scene = OfficeLayout(FakeTheme, atlas).layout(floor.areas)
    val p = scene.people.single()
    assertTrue(p.frame.startsWith("z_p"))
    for (zoom in listOf(1, 2)) {
      val img = renderer.render(scene, floor, zoom, 0)
      // the feet pixel of the person (its anchor) is the fake frame's color
      assertEquals("zoom $zoom", FakeTheme.colorOf(p.frame), img.getRGB(p.x * zoom, p.y * zoom))
    }
  }

  @Test
  fun slabUsesTheFakePaletteAndRugsTheFakeFrame() {
    val scene = OfficeLayout(FakeTheme, atlas).layout(Fixtures.areas(1))
    val img = renderer.nativeImage(scene, 0, 1)
    val colors = HashSet<Int>()
    for (y in 0 until img.height) for (x in 0 until img.width) colors.add(img.getRGB(x, y))
    assertTrue(FakeTheme.palette.baseSide.rgb in colors)
    assertTrue(FakeTheme.colorOf("z_rug") in colors)
    assertTrue("only the fake frames are drawn", scene.items.mapNotNull { it.frame }.all { it.startsWith("z_") })
  }
}
