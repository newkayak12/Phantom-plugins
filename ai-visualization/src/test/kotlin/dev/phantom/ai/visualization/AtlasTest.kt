// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.visualization

import dev.phantom.ai.visualization.theme.Atlas
import dev.phantom.ai.visualization.theme.ThemeA
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AtlasTest {
  private val atlas = Atlas.of(ThemeA)

  @Test
  fun sheetHas185FramesAndTheRightSize() {
    // 45 frames of scenery and bubbles, 140 of people: 10 looks x 14 hi-res frames (art/people_hd.py)
    assertEquals(185, atlas.frames.size)
    assertEquals(512, atlas.image.width)
    assertEquals(1282, atlas.image.height)
  }

  @Test
  fun everyFrameThemeANamesResolvesAndNoneIsThemeB() {
    val names = ThemeA.frameNames()
    assertTrue(names.isNotEmpty())
    assertEquals(emptyList<String>(), atlas.missing(ThemeA))
    assertTrue("theme B frame used: ${names.filter { it.endsWith("_b") }}", names.none { it.endsWith("_b") })
  }

  @Test
  fun framesLieInsideTheSheet() {
    for (f in atlas.frames.values) {
      assertTrue(f.name, f.x >= 0 && f.y >= 0 && f.x + f.w <= atlas.image.width && f.y + f.h <= atlas.image.height)
      assertEquals(f.w, atlas.sub(f.name).width)
    }
  }

  @Test
  fun aMissingFrameIsReportedByName() {
    val bad = Atlas(atlas.image, atlas.frames - "desk_rw" - "bubble_done")
    val e = runCatching { bad.validate(ThemeA) }.exceptionOrNull()
    assertTrue(e?.message, e is IllegalStateException && "desk_rw" in e.message!! && "bubble_done" in e.message!!)
  }

  @Test
  fun bundledSheetIsTheGeneratedOne() {
    val bytes = ThemeA::class.java.getResourceAsStream(ThemeA.atlasImageResource)!!.use { it.readAllBytes() }
    assertTrue(bytes.size > 1000)
  }
}
