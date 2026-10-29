// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.visualization

import dev.phantom.ai.visualization.model.ItemKind
import dev.phantom.ai.visualization.model.OfficeLayout
import dev.phantom.ai.visualization.model.Orientation
import dev.phantom.ai.visualization.model.VizState
import dev.phantom.ai.visualization.render.OfficeRenderer
import dev.phantom.ai.visualization.theme.Atlas
import dev.phantom.ai.visualization.theme.Offset
import dev.phantom.ai.visualization.theme.ThemeD
import dev.phantom.ai.visualization.theme.Themes
import java.awt.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeDTest {
  private val atlas = Atlas.of(ThemeD)
  private val renderer = OfficeRenderer(ThemeD, atlas)
  private val layout = OfficeLayout(ThemeD, atlas)

  @Test
  fun everyRegisteredThemesAtlasHasEveryFrameItNames() {
    assertEquals(listOf("A", "B", "C", "D", "E", "F"), Themes.all.map { it.id })
    for (t in Themes.all) {
      val a = Atlas.load(t)
      assertEquals("missing in ${t.id}", emptyList<String>(), a.missing(t))
      for (f in a.frames.values) assertTrue("${t.id} ${f.name}", f.x >= 0 && f.y >= 0 && f.x + f.w <= a.image.width && f.y + f.h <= a.image.height)
    }
    assertEquals(ThemeD, Themes.byId("D"))
  }

  @Test
  fun themeDHasItsOwnSheetSixJanitorsAndNoPartitions() {
    assertTrue(ThemeD.atlasImageResource.contains("/themes/d/"))
    assertTrue(ThemeD.personVariants >= 6)
    for (o in Orientation.values()) {
      assertNull(ThemeD.partition(o))
      assertNull("the bucket draws nothing over the janitor", ThemeD.chair(o).back)
    }
    assertNull(ThemeD.lighting)
  }

  /** Every phase of a look and state is the same size with the same anchor, so the layout's bounds hold for each. */
  @Test
  fun animationPhasesShareSizeAndAnchorAndDiffer() {
    for (v in 0 until ThemeD.personVariants) for (s in VizState.values()) {
      val base = atlas[ThemeD.person(v, s)]
      val names = (0 until ThemeD.animationPhases).map { ThemeD.personFrame(v, s, it) }.distinct()
      assertEquals("$v $s", ThemeD.framesOf(s), names.size)
      for (n in names) {
        val f = atlas[n]
        assertEquals(n, listOf(base.w, base.h, base.anchorX, base.anchorY), listOf(f.w, f.h, f.anchorX, f.anchorY))
      }
      // each frame differs from the next one: real animation, not a jitter
      for (i in names.indices) {
        val a = names[i]
        val b = names[(i + 1) % names.size]
        val diff = (0 until base.h).sumOf { y -> (0 until base.w).count { x -> atlas.pixel(a, x, y) != atlas.pixel(b, x, y) } }
        assertTrue("$a vs $b: $diff", diff >= 6)
      }
      for (ph in 0 until ThemeD.animationPhases) assertEquals(Offset.ZERO, ThemeD.personOffset(s, ph))
    }
  }

  /**
   * Every janitor stands with the boots on the anchor: the working and idle ones, which the layout lifts to seat height, stand at
   * the toilet, and only the mop, its suds and the wet streak reach a little below the anchor, clear of the session tag under it.
   */
  @Test
  fun everyJanitorStandsOnItsAnchor() {
    for (v in 0 until ThemeD.personVariants) {
      for (s in listOf(VizState.WORKING, VizState.IDLE)) {
        val f = atlas[ThemeD.person(v, s)]
        assertTrue("$v $s reaches ${f.h - f.anchorY} below its anchor", f.h - f.anchorY in 1..3)
      }
      for (s in listOf(VizState.WAITING, VizState.DONE)) {
        val f = atlas[ThemeD.person(v, s)]
        assertEquals("$v $s", 1, f.h - f.anchorY)
      }
    }
  }

  @Test
  fun headlessRenderDrawsLightTilesWallsRugsAndEveryJanitor() {
    val scene = layout.layout(Fixtures.areas(3))
    for (phase in 0 until ThemeD.animationPhases) {
      val img = renderer.nativeImage(scene, phase, 2)
      val cx = OfficeLayout.proj(94, 70)
      val cy = OfficeLayout.projY(94, 70)
      val floor = Color(img.getRGB(cx, cy))
      assertTrue("light floor $floor", floor.red + floor.green + floor.blue > 3 * 170)
      assertTrue("right wall", img.getRGB(OfficeLayout.proj(40, -1), OfficeLayout.projY(40, -1, 30)) ushr 24 > 0)
      assertTrue("left wall", img.getRGB(OfficeLayout.proj(-1, 40), OfficeLayout.projY(-1, 40, 30)) ushr 24 > 0)
      for (it in scene.items.filter { it.kind == ItemKind.RUG || it.kind == ItemKind.PERSON }) {
        val f = atlas[it.frame!!]
        val hits = (0 until f.h).sumOf { y -> (0 until f.w).count { x -> atlas.pixel(it.frame, x, y) ushr 24 == 255 && img.getRGB(it.x - f.anchorX + x, it.y - f.anchorY + y) ushr 24 > 0 } }
        assertTrue("${it.frame} drawn", hits > 0)
      }
    }
    assertEquals(4, scene.people.size)
    assertEquals(VizState.values().toSet(), scene.people.map { it.state }.toSet())
  }

  @Test
  fun wholeCanvasIsLightWithReadableText() {
    val scene = layout.layout(Fixtures.areas(2))
    val img = renderer.render(scene, null, 2, 0)
    assertEquals(ThemeD.palette.canvasBackground.rgb, img.getRGB(1, img.height - 2))
    val p = ThemeD.palette
    val board = Color(0xff, 0xd2, 0x3a) // the sign board of nameplate_d
    for ((fg, bg) in listOf(p.text to p.surface, p.text to p.card, p.tagText to p.tagBackground, p.dim to p.surface, p.plateText to board, p.accentText to p.accent)) {
      assertTrue("contrast $fg on $bg", contrast(fg, bg) >= 4.5)
    }
    val plate = atlas[ThemeD.nameplate]
    val mid = Color(atlas.pixel(ThemeD.nameplate, plate.w / 2, 5))
    assertEquals(board, mid)
    assertNotEquals(p.canvasBackground, p.surface)
  }

  @Test
  fun writesTheSampleRenderWhenAskedTo() {
    val dir = System.getProperty("phantom.aiviz.renderOut") ?: return
    java.io.File(dir).mkdirs()
    for (n in listOf(3, 4, 6)) for (phase in listOf(0, 2)) {
      val img = renderer.render(layout.layout(Fixtures.areas(n)), null, 3, phase)
      assertTrue(javax.imageio.ImageIO.write(img, "png", java.io.File(dir, "render-d-$n-phase$phase-zoom3.png")))
    }
  }

  private fun lum(c: Color): Double {
    fun ch(v: Int) = (v / 255.0).let { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
    return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
  }

  private fun contrast(a: Color, b: Color): Double {
    val (x, y) = lum(a) to lum(b)
    return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
  }
}
