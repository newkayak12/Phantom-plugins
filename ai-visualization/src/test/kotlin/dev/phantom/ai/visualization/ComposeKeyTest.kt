// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.visualization

import dev.phantom.ai.api.AiSessionStatus
import dev.phantom.ai.visualization.model.OfficeLayout
import dev.phantom.ai.visualization.model.ProjectData
import dev.phantom.ai.visualization.render.ComposeKey
import dev.phantom.ai.visualization.theme.Atlas
import dev.phantom.ai.visualization.theme.ThemeA
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

/** The composed room is cached by what it draws, so a scene rebuilt from unchanged data composes nothing new. */
class ComposeKeyTest {
  private val layout = OfficeLayout(ThemeA, Atlas.of(ThemeA))

  private fun scene(status: AiSessionStatus, activity: String? = null) = layout.layout(
    Fixtures.byWorktree(
      listOf(
        ProjectData(
          "/repo", "repo", listOf(Fixtures.wt(0, true)),
          listOf(Fixtures.session("t0", "/repo/wt0", status).copy(activity = activity)),
        ),
      ),
    ).floors[0].areas,
  )

  @Test
  fun rebuiltSceneOfTheSameDataHasTheSameKey() {
    val a = scene(AiSessionStatus.WORKING, "Reading a.kt")
    val b = scene(AiSessionStatus.WORKING, "Editing b.kt")
    assertNotSame(a, b)
    assertEquals(ComposeKey.of(a, 2, 1), ComposeKey.of(b, 2, 1))
  }

  @Test
  fun aChangedStateZoomOrScaleChangesTheKey() {
    val a = scene(AiSessionStatus.WORKING)
    assertNotEquals(ComposeKey.of(a, 2, 1), ComposeKey.of(scene(AiSessionStatus.WAITING), 2, 1))
    assertNotEquals(ComposeKey.of(a, 2, 1), ComposeKey.of(a, 3, 1))
    assertNotEquals(ComposeKey.of(a, 2, 1), ComposeKey.of(a, 2, 2))
  }
}
