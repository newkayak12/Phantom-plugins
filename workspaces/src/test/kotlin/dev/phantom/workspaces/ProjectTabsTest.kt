// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
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
    assertEquals(listOf("~/work/shop", "blog", "~/old/shop"), tabs.map { it.title })
  }

  @Test
  fun `the strip shows only when it is on and there is another project to switch to`() {
    assertTrue(ProjectTabs.isStripVisible(enabled = true, tabCount = 2))
    assertFalse(ProjectTabs.isStripVisible(enabled = true, tabCount = 1))
    assertFalse(ProjectTabs.isStripVisible(enabled = true, tabCount = 0))
    assertFalse(ProjectTabs.isStripVisible(enabled = false, tabCount = 3))
  }
}
