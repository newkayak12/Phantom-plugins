// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.visualization

import dev.phantom.ai.visualization.model.ItemKind
import dev.phantom.ai.visualization.model.OfficeLayout
import dev.phantom.ai.visualization.model.VizState
import dev.phantom.ai.visualization.render.OfficeRenderer
import dev.phantom.ai.visualization.theme.Atlas
import dev.phantom.ai.visualization.theme.Offset
import dev.phantom.ai.visualization.theme.ThemeA
import dev.phantom.ai.visualization.theme.ThemeE
import dev.phantom.ai.visualization.theme.Themes
import java.awt.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeETest {
  private val atlas = Atlas.of(ThemeE)
  private val renderer = OfficeRenderer(ThemeE, atlas)
  private val layout = OfficeLayout(ThemeE, atlas)

  @Test
  fun registeredWithItsOwnSheetAndEveryFrame() {
    assertTrue(ThemeE in Themes.all)
    assertEquals(ThemeE, Themes.byId("E"))
    assertTrue(ThemeE.atlasImageResource.contains("/themes/e/"))
    val a = Atlas.load(ThemeE)
    assertEquals(emptyList<String>(), a.missing(ThemeE))
    for (f in a.frames.values) assertTrue(f.name, f.x >= 0 && f.y >= 0 && f.x + f.w <= a.image.width && f.y + f.h <= a.image.height)
    assertNull(ThemeE.partition(dev.phantom.ai.visualization.model.Orientation.RW))
    assertTrue(ThemeE.personVariants >= 6)
  }

  /** Four real frames per state: every phase of a look is its own picture, of one size and anchor, so the person does not jump. */
  @Test
  fun everyStateAnimatesInPlace() {
    for (v in 0 until ThemeE.personVariants) for (s in VizState.values()) {
      val frames = (0 until ThemeE.animationPhases).map { ThemeE.personFrame(v, s, it) }
      assertEquals("$v $s", frames.size, frames.toSet().size)
      assertEquals(ThemeE.person(v, s), frames[0])
      assertTrue(frames.all { it.endsWith("_" + s.key) })
      val first = atlas[frames[0]]
      for (n in frames) {
        val f = atlas[n]
        assertEquals(n, listOf(first.w, first.h, first.anchorX, first.anchorY), listOf(f.w, f.h, f.anchorX, f.anchorY))
      }
      // the phases differ in their pixels
      val distinct = frames.map { n -> (0 until first.h).joinToString { y -> (0 until first.w).joinToString { x -> atlas.pixel(n, x, y).toString(16) } } }.toSet()
      assertEquals("$v $s", frames.size, distinct.size)
      for (ph in 0 until ThemeE.animationPhases) assertEquals(Offset.ZERO, ThemeE.personOffset(s, ph))
    }
  }

  @Test
  fun headlessRenderDrawsTheKitchen() {
    val scene = layout.layout(Fixtures.areas(3))
    val img = renderer.nativeImage(scene, 0, 2)
    val cx = OfficeLayout.proj(94, 70)
    val cy = OfficeLayout.projY(94, 70)
    // red quarry floor, white tiled walls
    val floor = Color(img.getRGB(cx, cy))
    assertTrue("red floor $floor", floor.red > floor.green + 40 && floor.red > floor.blue + 40)
    val wall = Color(img.getRGB(OfficeLayout.proj(3, -1), OfficeLayout.projY(3, -1, 46)))
    assertTrue("white wall $wall", wall.red + wall.green + wall.blue > 3 * 180)
    for (it in scene.items.filter { it.kind == ItemKind.RUG || it.kind == ItemKind.PERSON }) {
      val f = atlas[it.frame!!]
      val hits = (0 until f.h).sumOf { y -> (0 until f.w).count { x -> atlas.pixel(it.frame, x, y) ushr 24 == 255 && img.getRGB(it.x - f.anchorX + x, it.y - f.anchorY + y) ushr 24 > 0 } }
      assertTrue("${it.frame} drawn", hits > 0)
    }
    assertEquals(4, scene.people.size)
    assertTrue(scene.items.none { it.frame?.let { f -> f.startsWith("glass") || f.startsWith("desk_rw_b") } == true })
  }

  @Test
  fun canvasUsesItsPaletteWithReadableText() {
    val scene = layout.layout(Fixtures.areas(2))
    val img = renderer.render(scene, null, 2, 0)
    assertEquals(ThemeE.palette.canvasBackground.rgb, img.getRGB(1, img.height - 2))
    assertNotEquals(ThemeA.palette.canvasBackground.rgb, img.getRGB(1, img.height - 2))
    val p = ThemeE.palette
    for ((fg, bg) in listOf(p.text to p.surface, p.text to p.card, p.tagText to p.tagBackground, p.dim to p.surface, p.plateText to Color.WHITE)) {
      assertTrue("contrast $fg on $bg", contrast(fg, bg) >= 4.5)
    }
  }

  @Test
  fun writesTheSampleRenderWhenAskedTo() {
    val dir = System.getProperty("phantom.aiviz.renderOut") ?: return
    java.io.File(dir).mkdirs()
    for (n in listOf(3, 4, 6)) for (ph in 0 until ThemeE.animationPhases) {
      val img = renderer.render(layout.layout(Fixtures.areas(n)), null, 3, ph)
      assertTrue(javax.imageio.ImageIO.write(img, "png", java.io.File(dir, "render-e-$n-phase$ph-zoom3.png")))
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
