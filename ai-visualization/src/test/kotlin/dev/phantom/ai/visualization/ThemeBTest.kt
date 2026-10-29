// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.visualization

import dev.phantom.ai.visualization.model.ItemKind
import dev.phantom.ai.visualization.model.OfficeLayout
import dev.phantom.ai.visualization.model.Orientation
import dev.phantom.ai.visualization.model.Scene
import dev.phantom.ai.visualization.render.OfficeRenderer
import dev.phantom.ai.visualization.theme.Atlas
import dev.phantom.ai.visualization.theme.ThemeA
import dev.phantom.ai.visualization.theme.ThemeB
import dev.phantom.ai.visualization.theme.Themes
import java.awt.Color
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeBTest {
  private val atlas = Atlas.of(ThemeB)
  private val renderer = OfficeRenderer(ThemeB, atlas)
  private val layout = OfficeLayout(ThemeB, atlas)

  @Test
  fun everyRegisteredThemesAtlasHasEveryFrameItNames() {
    assertEquals(listOf("A", "B", "C", "F"), Themes.all.map { it.id })
    for (t in Themes.all) {
      val a = Atlas.load(t)
      assertEquals("missing in ${t.id}", emptyList<String>(), a.missing(t))
      for (f in a.frames.values) assertTrue("${t.id} ${f.name}", f.x >= 0 && f.y >= 0 && f.x + f.w <= a.image.width && f.y + f.h <= a.image.height)
    }
    assertEquals(ThemeB, Themes.byId("B"))
  }

  @Test
  fun themeBHasItsOwnSheetAndPartitionsThemeANone() {
    assertTrue(ThemeB.atlasImageResource.contains("/themes/b/"))
    assertEquals("glass_rw_b", ThemeB.partition(Orientation.RW))
    for (o in Orientation.values()) assertNull(ThemeA.partition(o))
  }

  @Test
  fun partitionsStayInTheRoomAndOffEveryRug() {
    assertTrue(OfficeLayout.partitions(1).isEmpty())
    for (n in 2..OfficeLayout.PER_PAGE) {
      val parts = OfficeLayout.partitions(n)
      assertTrue("$n areas", parts.isNotEmpty())
      val rugs = OfficeLayout.slots(n).map { it.rug }
      for (p in parts) {
        assertTrue("$p inside", p.footprint.inside(OfficeLayout.ROOM))
        assertTrue("$p off the rugs of $n", rugs.none { it.intersects(p.footprint) })
      }
    }
  }

  @Test
  fun themeALaysOutNoPartitionAndNoThemeBFrame() {
    val a = Atlas.of(ThemeA)
    for (n in 1..OfficeLayout.PER_PAGE) {
      val used = OfficeLayout(ThemeA, a).layout(Fixtures.areas(n)).items.mapNotNull { it.frame }
      assertTrue("$n: ${used.filter { it.endsWith("_b") }}", used.none { it.endsWith("_b") || it.startsWith("glass") })
    }
  }

  /** Theme A's native render of a fixed 3-area floor, pixel for pixel as before theme B existed. */
  @Test
  fun themeARenderIsUnchanged() {
    val a = Atlas.of(ThemeA)
    val img = OfficeRenderer(ThemeA, a).nativeImage(OfficeLayout(ThemeA, a).layout(Fixtures.areas(3)), 0, 3)
    val md = MessageDigest.getInstance("SHA-256")
    for (y in 0 until img.height) for (x in 0 until img.width) {
      val p = img.getRGB(x, y)
      md.update(byteArrayOf((p ushr 24).toByte(), (p ushr 16).toByte(), (p ushr 8).toByte(), p.toByte()))
    }
    assertEquals(THEME_A_3_AREAS_SHA256, md.digest().joinToString("") { "%02x".format(it) })
  }

  private fun opaque(scene: Scene, x: Int, y: Int, img: java.awt.image.BufferedImage) = img.getRGB(x, y) ushr 24 > 0

  @Test
  fun headlessRenderDrawsFloorWallsRugsPartitionsAndPeople() {
    val scene = layout.layout(Fixtures.areas(3))
    val img = renderer.nativeImage(scene, 0, 2)
    // floor: a free spot by the right edge; walls: the middle of the back walls, high up
    val cx = OfficeLayout.proj(94, 70)
    val cy = OfficeLayout.projY(94, 70)
    assertTrue("floor", opaque(scene, cx, cy, img))
    assertTrue("right wall", opaque(scene, OfficeLayout.proj(40, -1), OfficeLayout.projY(40, -1, 30), img))
    assertTrue("left wall", opaque(scene, OfficeLayout.proj(-1, 40), OfficeLayout.projY(-1, 40, 30), img))
    // light floor, white walls: no dark wood or brick
    val floor = Color(img.getRGB(cx, cy))
    assertTrue("light floor $floor", floor.red + floor.green + floor.blue > 3 * 170)
    // every rug and person sprite has its pixels on the image
    for (it in scene.items.filter { it.kind == ItemKind.RUG || it.kind == ItemKind.PERSON }) {
      val f = atlas[it.frame!!]
      val hits = (0 until f.h).sumOf { y -> (0 until f.w).count { x -> atlas.pixel(it.frame, x, y) ushr 24 == 255 && img.getRGB(it.x - f.anchorX + x, it.y - f.anchorY + y) ushr 24 > 0 } }
      assertTrue("${it.frame} drawn", hits > 0)
    }
    // the glass partitions are drawn: removing them changes the pixels inside their frames
    val glass = scene.items.filter { it.frame?.startsWith("glass") == true }
    assertTrue("partitions in a 3-area room", glass.size >= 5)
    val bare = renderer.nativeImage(Scene(scene.items - glass.toSet(), scene.areas, scene.desks, scene.people, 0, 1, scene.width, scene.height), 0, 2)
    for (g in glass) {
      val f = atlas[g.frame!!]
      var differs = 0
      for (y in 0 until f.h) for (x in 0 until f.w) {
        val sx = g.x - f.anchorX + x
        val sy = g.y - f.anchorY + y
        if (sx in 0 until img.width && sy in 0 until img.height && img.getRGB(sx, sy) != bare.getRGB(sx, sy)) differs++
      }
      assertTrue("glass at ${g.x},${g.y}", differs > 20)
    }
    assertEquals(4, scene.people.size)
  }

  @Test
  fun wholeCanvasIsLightWithReadableText() {
    val scene = layout.layout(Fixtures.areas(2))
    val img = renderer.render(scene, null, 2, 0)
    assertEquals(ThemeB.palette.canvasBackground.rgb, img.getRGB(1, img.height - 2))
    assertNotEquals(ThemeA.palette.canvasBackground.rgb, img.getRGB(1, img.height - 2))
    val p = ThemeB.palette
    for ((fg, bg) in listOf(p.text to p.surface, p.text to p.card, p.tagText to p.tagBackground, p.dim to p.surface, p.plateText to Color.WHITE)) {
      assertTrue("contrast $fg on $bg", contrast(fg, bg) >= 4.5)
    }
  }

  @Test
  fun writesTheSampleRenderWhenAskedTo() {
    val dir = System.getProperty("phantom.aiviz.renderOut") ?: return
    java.io.File(dir).mkdirs()
    for (n in listOf(3, 4, 6)) {
      val img = renderer.render(layout.layout(Fixtures.areas(n)), null, 3, 0)
      assertTrue(javax.imageio.ImageIO.write(img, "png", java.io.File(dir, "render-b-$n-zoom3.png")))
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

  companion object {
    const val THEME_A_3_AREAS_SHA256 = "6438b7f5164f0e549dbc0c85f9def0c77ab618995073d4be20489f3266dc25ff"
  }
}
