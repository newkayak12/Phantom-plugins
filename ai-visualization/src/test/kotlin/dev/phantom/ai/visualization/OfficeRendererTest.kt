// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.visualization

import dev.phantom.ai.api.AiSessionStatus
import dev.phantom.ai.visualization.model.OfficeLayout
import dev.phantom.ai.visualization.model.OfficeModel
import dev.phantom.ai.visualization.model.ProjectData
import dev.phantom.ai.visualization.model.VizState
import dev.phantom.ai.visualization.render.Fonts
import dev.phantom.ai.visualization.render.OfficeRenderer
import dev.phantom.ai.visualization.theme.Atlas
import dev.phantom.ai.visualization.theme.ThemeA
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import dev.phantom.ai.visualization.model.ItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class OfficeRendererTest {
  private val atlas = Atlas.of(ThemeA)
  private val renderer = OfficeRenderer(ThemeA, atlas)
  private val layout = OfficeLayout(ThemeA, atlas)

  private fun floorOf(status: AiSessionStatus) = OfficeModel.build(
    listOf(ProjectData("/repo", "repo", listOf(Fixtures.wt(0, true)), listOf(Fixtures.session("t0", "/repo/wt0", status)))),
  ).floors[0]

  /** An opaque pixel of [frame] near its middle: (x, y) inside the frame. */
  private fun opaqueInside(frame: String): Pair<Int, Int> {
    val f = atlas[frame]
    for (r in 0 until f.h / 2) for (dx in listOf(0, -1, 1, -2, 2)) {
      val x = f.w / 2 + dx
      for (y in listOf(f.h / 2 + r, f.h / 2 - r)) if (y in 0 until f.h && x in 0 until f.w && atlas.pixel(frame, x, y) ushr 24 == 255) return x to y
    }
    error("no opaque pixel in $frame")
  }

  @Test
  fun spritePixelsAreTheAtlasPixelsReplicatedByTheZoom() {
    val floor = floorOf(AiSessionStatus.WAITING)
    val scene = layout.layout(floor.areas)
    val p = scene.people.single()
    val frame = ThemeA.personFrame(p.person.seed % ThemeA.personVariants, p.state, 0)
    val (lx, ly) = opaqueInside(frame)
    val f = atlas[frame]
    val sx = p.x - f.anchorX + lx
    val sy = p.y - f.anchorY + ly
    val want = atlas.pixel(frame, lx, ly)
    for (zoom in listOf(1, 3)) {
      val img = renderer.render(scene, floor, zoom, 0)
      assertEquals(scene.width * zoom, img.width)
      for (i in 0 until zoom) for (j in 0 until zoom) assertEquals("zoom $zoom at +$i,$j", want, img.getRGB(sx * zoom + i, sy * zoom + j))
    }
  }

  @Test
  fun textIsDrawnAfterScalingAtScreenResolution() {
    val floor = floorOf(AiSessionStatus.WORKING)
    val scene = layout.layout(floor.areas)
    val zoom = 3
    val img = BufferedImage(scene.width * zoom, scene.height * zoom, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    val res = renderer.paintScene(g, scene, zoom, 0, 0, 0)
    g.dispose()
    val tag = res.tags.single().rect
    val scaled = renderer.nativeImage(scene, 0, zoom)
    // the tag pill is not in the scaled room: its pixels differ from the replicated native pixels, and its edge is anti-aliased
    var differ = 0
    var partial = 0
    for (y in tag.y until tag.y + tag.h) for (x in tag.x until tag.x + tag.w) {
      if (img.getRGB(x, y) != scaled.getRGB(x / zoom, y / zoom)) differ++
      val rgb = img.getRGB(x, y)
      val sameInRow = (0 until zoom).all { img.getRGB((x / zoom) * zoom + it, y) == rgb }
      if (!sameInRow) partial++
    }
    assertTrue("tag pixels differ from the scaled room", differ > tag.w * tag.h / 2)
    assertTrue("tag text/edge is finer than a zoomed pixel", partial > 20)
  }

  @Test
  fun hangulFontIsChosenByCanDisplayUpTo() {
    assumeTrue("no Hangul-capable font installed", Fonts.canShowHangul())
    val font = Fonts.font(13f)
    assertEquals(-1, font.canDisplayUpTo("작업 중"))
    assertEquals(-1, font.canDisplayUpTo("응답 필요 완료 대기"))
  }

  @Test
  fun allFourStatesRenderTheirPersonAndBubble() {
    val scene = layout.layout(Fixtures.areas(1))
    assertEquals(VizState.values().toSet(), scene.people.map { it.state }.toSet())
    val img = renderer.nativeImage(scene, 0, 1)
    for (p in scene.people) {
      assertTrue(p.frame.endsWith("_" + p.state.key))
      assertEquals("bubble_${p.state.key}", p.bubbleFrame)
      val (bx, by) = opaqueInside(p.bubbleFrame)
      assertEquals(
        "bubble of ${p.state}", atlas.pixel(p.bubbleFrame, bx, by),
        img.getRGB(p.bubbleBounds.x + bx, p.bubbleBounds.y + by),
      )
    }
  }

  @Test
  fun slabComesFromThePaletteAndRugsFromAtlasFrames() {
    val scene = layout.layout(Fixtures.areas(2))
    val img = renderer.nativeImage(scene, 0, 1)
    val slab = ThemeA.palette.baseSide.rgb
    val colors = HashSet<Int>()
    for (y in 0 until img.height) for (x in 0 until img.width) colors.add(img.getRGB(x, y))
    assertTrue("slab color", slab in colors)
    val rugs = scene.items.filter { it.kind == ItemKind.RUG }
    assertTrue(rugs.isNotEmpty() && rugs.all { it.frame in ThemeA.rugFrames })
    assertTrue("a rug pixel from the atlas", rugs.any { r ->
      val f = atlas[r.frame!!]
      img.getRGB(r.x - f.anchorX + f.w / 2, r.y - f.anchorY + f.h / 2) == atlas.pixel(r.frame, f.w / 2, f.h / 2)
    })
  }

  @Test
  fun writesTheSampleRenderWhenAskedTo() {
    val dir = System.getProperty("phantom.aiviz.renderOut") ?: return
    val model = OfficeModel.build(
      listOf(
        ProjectData(
          "/repo", "colo-backend", (0 until 3).map { Fixtures.wt(it, it == 0) },
          AiSessionStatus.values().mapIndexed { i, s -> Fixtures.session("t$i", "/repo/wt${i % 3}", s, 100L + i) },
        ),
      ),
    )
    val floor = model.floors[0]
    val scene = layout.layout(floor.areas)
    val img = renderer.render(scene, floor, 3, 0)
    File(dir).mkdirs()
    assertTrue(ImageIO.write(img, "png", File(dir, "render-zoom3.png")))
    assertNotEquals(0, File(dir, "render-zoom3.png").length())
  }
}
