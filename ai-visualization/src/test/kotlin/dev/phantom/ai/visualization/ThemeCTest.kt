// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.visualization

import dev.phantom.ai.visualization.model.ItemKind
import dev.phantom.ai.visualization.model.OfficeLayout
import dev.phantom.ai.visualization.model.Rect
import dev.phantom.ai.visualization.render.LightOverlay
import dev.phantom.ai.visualization.render.OfficeRenderer
import dev.phantom.ai.visualization.theme.Atlas
import dev.phantom.ai.visualization.theme.Theme
import dev.phantom.ai.visualization.theme.ThemeA
import dev.phantom.ai.visualization.theme.ThemeB
import dev.phantom.ai.visualization.theme.ThemeC
import dev.phantom.ai.visualization.theme.Themes
import java.awt.Color
import java.awt.image.BufferedImage
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeCTest {
  private val atlas = Atlas.of(ThemeC)
  private val renderer = OfficeRenderer(ThemeC, atlas)
  private val layout = OfficeLayout(ThemeC, atlas)

  /** Theme C with its lighting switched off: the night sheet as it is. */
  private object Unlit : Theme by ThemeC {
    override val id = "C-unlit"
    override val lighting = null
  }

  private fun lum(rgb: Int) = Color(rgb).let { 0.2126 * it.red + 0.7152 * it.green + 0.0722 * it.blue }

  /** Mean luminance of the opaque pixels of [img] inside [r] (the whole image by default). */
  private fun mean(img: BufferedImage, r: Rect = Rect(0, 0, img.width, img.height)): Double {
    var sum = 0.0
    var n = 0
    for (y in maxOf(0, r.y) until minOf(img.height, r.y + r.h)) for (x in maxOf(0, r.x) until minOf(img.width, r.x + r.w)) {
      val p = img.getRGB(x, y)
      if (p ushr 24 == 0) continue
      sum += lum(p)
      n++
    }
    return if (n == 0) 0.0 else sum / n
  }

  private fun sha(img: BufferedImage): String {
    val md = MessageDigest.getInstance("SHA-256")
    for (y in 0 until img.height) for (x in 0 until img.width) {
      val p = img.getRGB(x, y)
      md.update(byteArrayOf((p ushr 24).toByte(), (p ushr 16).toByte(), (p ushr 8).toByte(), p.toByte()))
    }
    return md.digest().joinToString("") { "%02x".format(it) }
  }

  @Test
  fun everyThemesAtlasValidates() {
    assertEquals(listOf("A", "B", "C", "D", "E", "F"), Themes.all.map { it.id })
    for (t in Themes.all) {
      val a = Atlas.load(t)
      assertEquals("missing in ${t.id}", emptyList<String>(), a.missing(t))
      for (f in a.frames.values) assertTrue("${t.id} ${f.name}", f.x >= 0 && f.y >= 0 && f.x + f.w <= a.image.width && f.y + f.h <= a.image.height)
    }
    assertEquals(ThemeC, Themes.byId("C"))
    assertTrue(ThemeC.atlasImageResource.contains("/themes/c/"))
  }

  @Test
  fun onlyThemeCIsLitAndEveryLightNamesAFrameOfItsSheet() {
    assertNull(ThemeA.lighting)
    assertNull(ThemeB.lighting)
    val l = ThemeC.lighting!!
    for (s in l.sources) assertTrue(s.frame, atlas.has(s.frame))
    val scene = layout.layout(Fixtures.areas(3))
    val lights = LightOverlay.lights(scene, l)
    // a light per desk, one for the lamp, one per name plate
    val desks = scene.items.count { it.frame?.startsWith("desk_") == true }
    assertTrue("$desks desks, ${lights.size} lights", lights.size >= desks + 1 + 3)
  }

  @Test
  fun nightRenderIsDarkerThanAYetMonitorsAndTheLampStayBright() {
    val a = Atlas.of(ThemeA)
    val day = OfficeRenderer(ThemeA, a).nativeImage(OfficeLayout(ThemeA, a).layout(Fixtures.areas(3)), 0, 3)
    val scene = layout.layout(Fixtures.areas(3))
    val night = renderer.nativeImage(scene, 0, 3)
    val unlit = OfficeRenderer(Unlit, atlas).nativeImage(scene, 0, 3)
    assertTrue("night ${mean(night)} < day ${mean(day)}", mean(night) < mean(day) * 0.6)
    for (l in LightOverlay.lights(scene, ThemeC.lighting!!).filter { it.source.frame.startsWith("desk_") || it.source.frame.startsWith("lamp") }) {
      val pool = Rect(l.x - 12, l.y - 6, 24, 24)
      assertTrue("pool at ${l.x},${l.y} brighter than the bare night: ${mean(night, pool)} vs ${mean(unlit, pool)}", mean(night, pool) > mean(unlit, pool) + 8)
      // the glowing pixels themselves (screen, lamp shade) are bright
      var brightest = 0.0
      for (y in pool.y until pool.y + pool.h) for (x in pool.x until pool.x + pool.w) brightest = maxOf(brightest, lum(night.getRGB(x, y)))
      assertTrue("glow at ${l.x},${l.y}: $brightest", brightest > 150)
    }
  }

  @Test
  fun bubblesAreDrawnUndimmed() {
    val scene = layout.layout(Fixtures.areas(3))
    val img = renderer.nativeImage(scene, 0, 3)
    for (p in scene.people) {
      val name = ThemeC.bubbleFrame(p.state, 0)
      val f = atlas[name]
      val off = ThemeC.bubbleOffset(p.state, 0)
      for (y in 0 until f.h) for (x in 0 until f.w) {
        val src = atlas.pixel(name, x, y)
        if (src ushr 24 == 255) assertEquals("$name $x,$y", src, img.getRGB(p.bubbleBounds.x + off.dx + x, p.bubbleBounds.y + off.dy + y))
      }
      // the same pixels as theme A's bubbles
      val a = Atlas.of(ThemeA)
      assertEquals(a[name].w, f.w)
      for (y in 0 until f.h) for (x in 0 until f.w) assertEquals(a.pixel(name, x, y), atlas.pixel(name, x, y))
    }
    assertTrue(scene.items.filter { it.kind == ItemKind.PERSON }.all { it.frame!!.endsWith("_c") })
  }

  @Test
  fun darkChromeWithReadableText() {
    val p = ThemeC.palette
    assertTrue(lum(p.canvasBackground.rgb) < 40 && lum(p.surface.rgb) < 40)
    for ((fg, bg) in listOf(p.text to p.surface, p.text to p.card, p.tagText to Color(p.tagBackground.rgb), p.dim to p.surface, p.accentText to p.accent)) {
      assertTrue("contrast $fg on $bg", contrast(fg, bg) >= 4.5)
    }
  }

  /** Theme B's native render of the fixed 3-area floor, pixel for pixel as before theme C existed. */
  @Test
  fun themeBRenderIsUnchanged() {
    val b = Atlas.of(ThemeB)
    assertEquals(THEME_B_3_AREAS_SHA256, sha(OfficeRenderer(ThemeB, b).nativeImage(OfficeLayout(ThemeB, b).layout(Fixtures.areas(3)), 0, 3)))
  }

  @Test
  fun writesTheSampleRenderWhenAskedTo() {
    val dir = System.getProperty("phantom.aiviz.renderOut") ?: return
    java.io.File(dir).mkdirs()
    for (n in listOf(3, 4, 6)) {
      val img = renderer.render(layout.layout(Fixtures.areas(n)), null, 3, 0)
      assertTrue(javax.imageio.ImageIO.write(img, "png", java.io.File(dir, "render-c-$n-zoom3.png")))
    }
  }

  private fun rel(c: Color): Double {
    fun ch(v: Int) = (v / 255.0).let { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
    return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
  }

  private fun contrast(a: Color, b: Color): Double {
    val (x, y) = rel(a) to rel(b)
    return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
  }

  companion object {
    const val THEME_B_3_AREAS_SHA256 = "4feb392be6335d90cf05c37b79aaa24d81a1b0b1f315848d23649f746aabee3b"
  }
}
