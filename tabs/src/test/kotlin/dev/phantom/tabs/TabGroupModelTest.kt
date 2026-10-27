// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.tabs

import dev.phantom.tabs.core.TabGroupColor
import dev.phantom.tabs.core.TabGroupModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TabGroupModelTest {
  private val model = TabGroupModel()

  @Test
  fun `group tabs move to where the first one is`() {
    model.create(listOf("a", "c"), "g", TabGroupColor.BLUE)
    assertEquals(listOf("a", "c", "b", "x"), model.desiredOrder(listOf("a", "b", "x", "c")))
  }

  @Test
  fun `a tab opened inside a group lands after it`() {
    model.create(listOf("a", "b"), "g", TabGroupColor.BLUE)
    assertEquals(listOf("a", "b", "n"), model.desiredOrder(listOf("a", "n", "b")))
    assertNull(model.groupOf("n"))
  }

  @Test
  fun `no groups, no reordering`() {
    assertEquals(listOf("b", "a"), model.desiredOrder(listOf("b", "a")))
  }

  @Test
  fun `a member dragged next to its group stays`() {
    val g = model.create(listOf("a", "b", "c"), "g", TabGroupColor.BLUE)
    model.onMoved(listOf("a", "b", "c", "x"), listOf("a", "c", "b", "x"))
    assertSame(g, model.groupOf("b"))
    assertNull(model.groupOf("x"))
  }

  @Test
  fun `a tab dropped right after a group joins it, as in Chrome`() {
    val g = model.create(listOf("a", "b"), "g", TabGroupColor.BLUE)
    model.onMoved(listOf("a", "b", "x", "y"), listOf("a", "b", "y", "x"), dragged = "y")
    assertSame(g, model.groupOf("y"))
    assertNull(model.groupOf("x"))
  }

  @Test
  fun `a tab dragged next to a group of one tab joins it`() {
    val g = model.create(listOf("a"), "g", TabGroupColor.BLUE)
    model.onMoved(listOf("a", "x", "y"), listOf("a", "y", "x"), dragged = "y")
    assertSame(g, model.groupOf("y"))
    assertEquals(listOf("a", "y"), g.members)
  }

  @Test
  fun `a tab dropped right before a group's chip stays outside`() {
    model.create(listOf("a", "b"), "g", TabGroupColor.BLUE)
    model.onMoved(listOf("a", "b", "x"), listOf("x", "a", "b"), dragged = "x")
    assertNull(model.groupOf("x"))
  }

  @Test
  fun `a tab dropped between two members joins`() {
    val g = model.create(listOf("a", "b"), "g", TabGroupColor.BLUE)
    model.onMoved(listOf("a", "b", "x"), listOf("a", "x", "b"), dragged = "x")
    assertSame(g, model.groupOf("x"))
    assertSame(g, model.groupOf("b"))
  }

  @Test
  fun `a tab dropped right after a collapsed group stays out of it`() {
    model.create(listOf("a", "b"), "g", TabGroupColor.BLUE).collapsed = true
    // b is hidden: the tabs shown are a, y, x
    model.onMoved(listOf("a", "y", "x"), listOf("a", "x", "y"), dragged = "x")
    assertNull(model.groupOf("x"))
  }

  @Test
  fun `a member dragged away leaves, the group stays`() {
    val g = model.create(listOf("a", "b"), "g", TabGroupColor.BLUE)
    model.onMoved(listOf("a", "b", "x"), listOf("a", "x", "b"), dragged = "x")
    model.onMoved(listOf("a", "x", "b", "y", "z"), listOf("a", "x", "y", "z", "b"))
    assertNull(model.groupOf("b"))
    assertSame(g, model.groupOf("a"))
  }

  @Test
  fun `removing the last member deletes the group`() {
    model.create(listOf("a"), "g", TabGroupColor.BLUE)
    model.remove("a")
    assertTrue(model.groups.isEmpty())
  }

  @Test
  fun `create takes a tab from its old group`() {
    model.create(listOf("a", "b"), "old", TabGroupColor.BLUE)
    val g = model.create(listOf("b"), "new", TabGroupColor.RED)
    assertSame(g, model.groupOf("b"))
    assertEquals(listOf("a"), model.groups.first().members)
  }

  @Test
  fun `next color skips used ones`() {
    assertEquals(TabGroupColor.BLUE, model.nextColor())
    model.create(listOf("a"), "g", TabGroupColor.BLUE)
    assertEquals(TabGroupColor.RED, model.nextColor())
  }

  @Test
  fun `a group keeps its own order wherever its tabs are`() {
    model.create(listOf("a", "b", "c"), "g", TabGroupColor.BLUE)
    assertEquals(listOf("a", "b", "c", "x"), model.desiredOrder(listOf("c", "x", "b", "a")))
  }

  @Test
  fun `dragging a member inside its group changes the group order`() {
    val g = model.create(listOf("a", "b", "c"), "g", TabGroupColor.BLUE)
    model.onMoved(listOf("a", "b", "c", "x"), listOf("a", "c", "b", "x"), dragged = "c")
    assertEquals(listOf("a", "c", "b"), g.members)
  }

  @Test
  fun `a renamed file and the files of a renamed folder stay in their group`() {
    val g = model.create(listOf("file:///p/a.kt", "file:///p/dir/b.kt"), "g", TabGroupColor.BLUE)
    model.rename("file:///p/a.kt", "file:///p/a2.kt")
    model.rename("file:///p/dir", "file:///p/lib")
    assertEquals(listOf("file:///p/a2.kt", "file:///p/lib/b.kt"), g.members)
  }

  @Test
  fun `dragging the chip moves the whole group`() {
    val g = model.create(listOf("a", "b"), "g", TabGroupColor.BLUE)
    model.onMoved(listOf("a", "b", "x", "y"), listOf("b", "x", "y", "a"), dragged = "a", byChip = true)
    assertSame(g, model.groupOf("a"))
    assertSame(g, model.groupOf("b"))
    assertEquals(listOf("x", "y", "a", "b"), model.desiredOrder(listOf("b", "x", "y", "a")))
  }

  @Test
  fun `the only tab of a group keeps its group wherever it is dragged`() {
    val g = model.create(listOf("a"), "g", TabGroupColor.BLUE)
    model.onMoved(listOf("a", "x", "y"), listOf("x", "y", "a"), dragged = "a")
    assertSame(g, model.groupOf("a"))
  }

  @Test
  fun `a collapsed group moves whole when its tab is dragged`() {
    val g = model.create(listOf("a", "b"), "g", TabGroupColor.BLUE)
    g.collapsed = true
    model.onMoved(listOf("a", "b", "x"), listOf("b", "x", "a"), dragged = "a")
    assertSame(g, model.groupOf("b"))
    assertEquals(listOf("x", "a", "b"), model.desiredOrder(listOf("b", "x", "a")))
  }

  @Test
  fun `the first tab dragged out by its body still leaves`() {
    model.create(listOf("a", "b"), "g", TabGroupColor.BLUE)
    model.onMoved(listOf("a", "b", "x", "y"), listOf("b", "x", "y", "a"), dragged = "a")
    assertNull(model.groupOf("a"))
  }
}
