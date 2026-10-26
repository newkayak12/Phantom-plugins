// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.tabs

import dev.phantom.tabs.core.GroupState
import dev.phantom.tabs.core.PaneState
import dev.phantom.tabs.core.TabGroupColor
import dev.phantom.tabs.core.TabGroupModel
import dev.phantom.tabs.core.restore
import dev.phantom.tabs.core.toPaneState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TabGroupsStateTest {
  @Test
  fun `a pane comes back as it was saved`() {
    val model = TabGroupModel()
    model.create(listOf("b", "a"), "API", TabGroupColor.PURPLE).collapsed = true
    model.create(listOf("c"), "", TabGroupColor.GREEN)

    val restored = TabGroupModel()
    restored.restore(model.toPaneState(), openUrls = setOf("a", "b", "c"))

    assertEquals(2, restored.groups.size)
    val api = restored.groups[0]
    assertEquals("API", api.name)
    assertEquals(TabGroupColor.PURPLE, api.color)
    assertTrue(api.collapsed)
    assertEquals(listOf("b", "a"), api.members)
    assertEquals(listOf("c"), restored.groups[1].members)
  }

  @Test
  fun `closed files are dropped, and so are groups left empty`() {
    val model = TabGroupModel()
    model.create(listOf("a", "gone"), "g", TabGroupColor.BLUE)
    model.create(listOf("gone2"), "h", TabGroupColor.RED)

    val restored = TabGroupModel()
    restored.restore(model.toPaneState(), openUrls = setOf("a"))

    assertEquals(1, restored.groups.size)
    assertEquals(listOf("a"), restored.groups[0].members)
  }

  @Test
  fun `an unknown color becomes grey`() {
    val pane = PaneState()
    pane.groups.add(GroupState().apply { name = "g"; color = "MAGENTA"; urls.add("a") })

    val restored = TabGroupModel()
    restored.restore(pane, openUrls = setOf("a"))

    assertEquals(TabGroupColor.GREY, restored.groups.single().color)
  }
}
