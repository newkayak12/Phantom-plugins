// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.workspaces

import dev.phantom.workspaces.core.OpenProject
import dev.phantom.workspaces.core.ProjectTabs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectTabsTest {
  private val home = "/Users/me"
  private val shop = OpenProject("shop", "shop", "/Users/me/work/shop")
  private val blog = OpenProject("blog", "blog", "/Users/me/blog")
  private val tools = OpenProject("tools", "tools", "/opt/tools")

  @Test
  fun `one tab per open project, in the order they opened`() {
    val tabs = ProjectTabs.tabs(listOf(shop, blog, tools), current = "blog", userHome = home)
    assertEquals(listOf("shop", "blog", "tools"), tabs.map { it.title })
    assertEquals(listOf<Any>("shop", "blog", "tools"), tabs.map { it.key })
  }

  @Test
  fun `only the project of this window is the current tab`() {
    val tabs = ProjectTabs.tabs(listOf(shop, blog, tools), current = "blog", userHome = home)
    assertEquals(listOf(false, true, false), tabs.map { it.isCurrent })
  }

  @Test
  fun `the tooltip is where the project lives, home shortened to a tilde`() {
    val tabs = ProjectTabs.tabs(listOf(shop, tools, OpenProject("x", "x", null)), current = "shop", userHome = home)
    assertEquals(listOf("~/work/shop", "/opt/tools", null), tabs.map { it.tooltip })
  }

  @Test
  fun `two projects with the same name are told apart by their location`() {
    val otherShop = OpenProject("shop2", "shop", "/Users/me/old/shop")
    val tabs = ProjectTabs.tabs(listOf(shop, blog, otherShop), current = "blog", userHome = home)
    assertEquals(listOf("work/shop", "blog", "old/shop"), tabs.map { it.title })
  }

  @Test
  fun `the worktrees of one repository are titled by their folders`() {
    val main = OpenProject("m", "colo-backend", "/Users/me/orca/colo-backend")
    val a = OpenProject("a", "colo-backend", "/Users/me/orca/workspaces/colo-backend/refactor-CL-556")
    val b = OpenProject("b", "colo-backend", "/Users/me/orca/workspaces/colo-backend/refactor-CL-552")
    val tabs = ProjectTabs.tabs(listOf(main, a, b, blog), current = "a", userHome = home)
    assertEquals(listOf("colo-backend", "refactor-CL-556", "refactor-CL-552", "blog"), tabs.map { it.title })
    assertEquals("~/orca/workspaces/colo-backend/refactor-CL-556", tabs[1].tooltip)
  }

  @Test
  fun `a tab dropped at a gap moves there`() {
    val order = listOf("a", "b", "c", "d")
    assertEquals(listOf("b", "c", "a", "d"), ProjectTabs.moved(order, from = 0, gap = 3))
    assertEquals(listOf("b", "c", "d", "a"), ProjectTabs.moved(order, from = 0, gap = 4))
    assertEquals(listOf("d", "a", "b", "c"), ProjectTabs.moved(order, from = 3, gap = 0))
    assertEquals(listOf("a", "c", "b", "d"), ProjectTabs.moved(order, from = 2, gap = 1))
  }

  @Test
  fun `a tab dropped next to itself or out of range stays`() {
    val order = listOf("a", "b", "c")
    assertEquals(order, ProjectTabs.moved(order, from = 1, gap = 1))
    assertEquals(order, ProjectTabs.moved(order, from = 1, gap = 2))
    assertEquals(order, ProjectTabs.moved(order, from = 3, gap = 0))
    assertEquals(order, ProjectTabs.moved(order, from = 0, gap = 4))
  }

  @Test
  fun `the strip shows only when it is on and there is another project to switch to`() {
    assertTrue(ProjectTabs.isStripVisible(enabled = true, tabCount = 2))
    assertFalse(ProjectTabs.isStripVisible(enabled = true, tabCount = 1))
    assertFalse(ProjectTabs.isStripVisible(enabled = true, tabCount = 0))
    assertFalse(ProjectTabs.isStripVisible(enabled = false, tabCount = 3))
  }

  @Test
  fun `a project attached back comes in as the last tab`() {
    assertEquals(listOf("shop", "tools", "blog"), ProjectTabs.attached(listOf("shop", "blog", "tools"), "blog"))
    assertEquals(listOf("shop", "tools", "blog"), ProjectTabs.attached(listOf("shop", "tools"), "blog"))
  }

  @Test
  fun `a tab detached from the front gives its place to the tab after it, or before it when last`() {
    val tabs = listOf("shop", "blog", "tools")
    assertEquals("tools", ProjectTabs.neighbor(tabs, "blog"))
    assertEquals("blog", ProjectTabs.neighbor(tabs, "shop"))
    assertEquals("blog", ProjectTabs.neighbor(tabs, "tools"))
    assertEquals(null, ProjectTabs.neighbor(listOf("shop"), "shop"))
    assertEquals(null, ProjectTabs.neighbor(tabs, "other"))
  }

  @Test
  fun `a tab is torn out only when dragged well beyond the strip's height, up or down`() {
    assertFalse(ProjectTabs.isDetachDrag(dy = 30, stripHeight = 28, threshold = 5))
    assertFalse(ProjectTabs.isDetachDrag(dy = 33, stripHeight = 28, threshold = 5))
    assertTrue(ProjectTabs.isDetachDrag(dy = 34, stripHeight = 28, threshold = 5))
    assertTrue(ProjectTabs.isDetachDrag(dy = -34, stripHeight = 28, threshold = 5))
  }

  @Test
  fun `both projects side by side are marked in the tabs`() {
    val tabs = ProjectTabs.tabs(listOf(shop, blog, tools), current = "blog", userHome = home, sideBySide = listOf("blog", "tools"))
    assertEquals(listOf(false, true, true), tabs.map { it.isSideBySide })
    assertEquals(listOf(false, true, false), tabs.map { it.isCurrent })
  }

  @Test
  fun `tabs that fit keep their width`() {
    assertEquals(listOf(100, 200), ProjectTabs.fitted(listOf(100, 200), available = 400, minimum = 50))
  }

  @Test
  fun `tabs that do not fit have the widest cut down to one width`() {
    // 100 + 2 x 150 = 400
    assertEquals(listOf(100, 150, 150), ProjectTabs.fitted(listOf(100, 300, 500), available = 400, minimum = 50))
  }

  @Test
  fun `tabs never get narrower than the minimum, the rest scrolls`() {
    val widths = ProjectTabs.fitted(listOf(300, 300, 300, 300), available = 200, minimum = 96)
    assertEquals(listOf(96, 96, 96, 96), widths)
    assertEquals(184, ProjectTabs.scrollOffset(500, total = widths.sum(), available = 200))
    assertEquals(0, ProjectTabs.scrollOffset(-20, total = widths.sum(), available = 200))
    assertEquals(0, ProjectTabs.scrollOffset(30, total = 150, available = 200))
  }

  @Test
  fun `the current tab is scrolled into view, moving as little as needed`() {
    assertEquals(300, ProjectTabs.reveal(0, start = 400, end = 500, available = 200))
    assertEquals(100, ProjectTabs.reveal(300, start = 100, end = 200, available = 200))
    assertEquals(50, ProjectTabs.reveal(50, start = 100, end = 200, available = 200))
  }
}
