// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.visualization

import dev.phantom.ai.visualization.model.ItemKind
import dev.phantom.ai.visualization.model.OfficeLayout
import dev.phantom.ai.visualization.model.Orientation
import dev.phantom.ai.visualization.model.VizState
import dev.phantom.ai.visualization.render.OfficeRenderer
import dev.phantom.ai.visualization.theme.Atlas
import dev.phantom.ai.visualization.theme.Offset
import dev.phantom.ai.visualization.theme.ThemeA
import dev.phantom.ai.visualization.theme.ThemeF
import dev.phantom.ai.visualization.theme.Themes
import java.awt.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeFTest {
  private val atlas = Atlas.of(ThemeF)
  private val renderer = OfficeRenderer(ThemeF, atlas)
  private val layout = OfficeLayout(ThemeF, atlas)

  @Test
  fun themeFIsRegisteredWithItsOwnSheetAndEveryFrameItNames() {
    assertEquals(ThemeF, Themes.byId("F"))
    assertTrue(ThemeF.atlasImageResource.contains("/themes/f/"))
    val a = Atlas.load(ThemeF)
    assertEquals(emptyList<String>(), a.missing(ThemeF))
    for (f in a.frames.values) assertTrue(f.name, f.x >= 0 && f.y >= 0 && f.x + f.w <= a.image.width && f.y + f.h <= a.image.height)
    assertTrue(ThemeF.personVariants >= 8)
    assertEquals(2, ThemeF.personDetail)
    for (o in Orientation.values()) {
      assertNull(ThemeF.partition(o))
      assertNull(ThemeF.chair(o).back)
    }
    assertNull(ThemeF.lighting)
  }

  /** Every state but idle has four distinct frames, all of one size and anchor, so the layout's bounds hold in every phase. */
  @Test
  fun everyPoseIsAnimatedInFramesOfOneSize() {
    for (v in 0 until ThemeF.personVariants) for (s in VizState.values()) {
      val frames = (0 until ThemeF.animationPhases).map { ThemeF.personFrame(v, s, it) }
      assertEquals(ThemeF.person(v, s), frames[0])
      val first = atlas[frames[0]]
      for (n in frames) {
        val f = atlas[n]
        assertEquals("$n size", first.w to first.h, f.w to f.h)
        assertEquals("$n anchor", first.anchorX to first.anchorY, f.anchorX to f.anchorY)
      }
      val distinct = frames.toSet().size
      if (s == VizState.IDLE) assertEquals(1, distinct) else assertEquals("$v $s", 4, distinct)
      for (ph in 0 until 4) assertEquals(Offset.ZERO, ThemeF.personOffset(s, ph))
    }
    // the swing really moves: the pixels of phase 0 and 2 differ
    val w0 = ThemeF.personFrame(0, VizState.WORKING, 0)
    val w2 = ThemeF.personFrame(0, VizState.WORKING, 2)
    val f = atlas[w0]
    val diff = (0 until f.h).sumOf { y -> (0 until f.w).count { x -> atlas.pixel(w0, x, y) != atlas.pixel(w2, x, y) } }
    assertTrue("$diff pixels differ", diff > 30)
  }

  /** The poses tell the states apart by shape: the flag rises far above the head; the dozer turns round, legs down to the floor. */
  @Test
  fun posesDifferInSilhouette() {
    val d = ThemeF.personDetail
    for (v in 0 until ThemeF.personVariants) {
      val waiting = atlas[ThemeF.person(v, VizState.WAITING)]
      val done = atlas[ThemeF.person(v, VizState.DONE)]
      val idle = atlas[ThemeF.person(v, VizState.IDLE)]
      val working = atlas[ThemeF.person(v, VizState.WORKING)]
      assertTrue("waiting flag above the head", waiting.anchorY > done.anchorY + 8 * d)
      assertTrue("idle legs reach the floor", idle.h - idle.anchorY >= OfficeLayout.SEAT_Z * d)
      assertTrue("working shows the back only", working.h - working.anchorY <= 2 * d)
    }
  }

  @Test
  fun headlessRenderDrawsTheSiteAndEveryPerson() {
    val scene = layout.layout(Fixtures.areas(3))
    val img = renderer.nativeImage(scene, 0, 2)
    val cx = OfficeLayout.proj(94, 70)
    val cy = OfficeLayout.projY(94, 70)
    assertTrue("floor", img.getRGB(cx, cy) ushr 24 > 0)
    assertTrue("right wall", img.getRGB(OfficeLayout.proj(40, -1), OfficeLayout.projY(40, -1, 30)) ushr 24 > 0)
    assertTrue("left wall", img.getRGB(OfficeLayout.proj(-1, 40), OfficeLayout.projY(-1, 40, 30)) ushr 24 > 0)
    // brown dirt: red over blue
    val floor = Color(img.getRGB(cx, cy))
    assertTrue("dirt floor $floor", floor.red > floor.blue + 20)
    for (it in scene.items.filter { it.kind == ItemKind.RUG || it.kind == ItemKind.PERSON }) {
      val f = atlas[it.frame!!]
      val hits = (0 until f.h).sumOf { y -> (0 until f.w).count { x -> atlas.pixel(it.frame, x, y) ushr 24 == 255 && img.getRGB(it.x - f.anchorX + x, it.y - f.anchorY + y) ushr 24 > 0 } }
      assertTrue("${it.frame} drawn", hits > 0)
    }
    assertEquals(4, scene.people.size)
    assertTrue(scene.items.any { it.frame == "bench_rw_f" })
  }

  @Test
  fun canvasUsesTheSitePaletteWithReadableText() {
    val scene = layout.layout(Fixtures.areas(2))
    val img = renderer.render(scene, null, 2, 0)
    assertEquals(ThemeF.palette.canvasBackground.rgb, img.getRGB(1, img.height - 2))
    assertNotEquals(ThemeA.palette.canvasBackground.rgb, img.getRGB(1, img.height - 2))
    val p = ThemeF.palette
    val signYellow = Color(255, 201, 40)
    for ((fg, bg) in listOf(p.text to p.surface, p.text to p.card, p.tagText to p.tagBackground, p.dim to p.surface,
      p.plateText to signYellow, p.accentText to p.accent)) {
      assertTrue("contrast $fg on $bg", contrast(fg, bg) >= 4.5)
    }
  }

  /** Every frame is whole native pixels: even size and anchor (personDetail 2); a desk along v gets the mirrored working pose. */
  @Test
  fun crewFramesAreDetailAlignedAndTurnToTheirDesk() {
    val d = ThemeF.personDetail
    for (v in 0 until ThemeF.personVariants) for (s in VizState.values()) for (ph in 0 until 4) for (o in listOf(null, *Orientation.values())) {
      val f = atlas[ThemeF.personFrame(v, s, ph, o)]
      assertTrue(f.name, f.w % d == 0 && f.h % d == 0 && f.anchorX % d == 0 && f.anchorY % d == 0)
    }
    assertEquals("crew0_working_1_lw_f", ThemeF.personFrame(0, VizState.WORKING, 1, Orientation.LW))
    assertEquals("crew0_working_1_f", ThemeF.personFrame(0, VizState.WORKING, 1, Orientation.RW))
    assertEquals("crew0_waiting_1_f", ThemeF.personFrame(0, VizState.WAITING, 1, Orientation.LW))
  }

  @Test
  fun writesTheSampleRenderWhenAskedTo() {
    val dir = System.getProperty("phantom.aiviz.renderOut") ?: return
    java.io.File(dir).mkdirs()
    for (n in listOf(3, 4, 6)) for (ph in listOf(0, 2)) for (zoom in listOf(2, 3)) {
      val img = renderer.render(layout.layout(Fixtures.areas(n)), null, zoom, ph)
      assertTrue(javax.imageio.ImageIO.write(img, "png", java.io.File(dir, "render-f-$n-phase$ph-zoom$zoom.png")))
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
