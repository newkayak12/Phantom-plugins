// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.workspaces

import dev.phantom.workspaces.core.GitCheckout
import dev.phantom.workspaces.core.OpenProject
import dev.phantom.workspaces.core.ProjectTabs
import org.junit.Assert.assertEquals
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

  private fun checkout(repo: String, branch: String?, isMain: Boolean = false) = GitCheckout("/$repo/.git", "/$repo/HEAD", branch, isMain)

  @Test
  fun `the checkouts of one repository are titled by their branch, the main checkout marked`() {
    val main = OpenProject("m", "colo-backend", "/Users/me/orca/colo-backend", checkout("colo", "main", isMain = true))
    val a = OpenProject("a", "colo-backend", "/Users/me/orca/workspaces/colo-backend/refactor-CL-556", checkout("colo", "refactor/CL-556"))
    val tabs = ProjectTabs.tabs(listOf(main, a, blog), current = "a", userHome = home)
    assertEquals(listOf("main", "refactor/CL-556", "blog"), tabs.map { it.title })
    assertEquals(listOf(true, false, false), tabs.map { it.isMainCheckout })
    assertEquals("~/orca/workspaces/colo-backend/refactor-CL-556", tabs[1].tooltip)
  }

  @Test
  fun `a checkout alone in its repository keeps its name`() {
    val shopGit = shop.copy(git = checkout("shop", "main", isMain = true))
    val blogGit = blog.copy(git = checkout("blog", "main", isMain = true))
    val tabs = ProjectTabs.tabs(listOf(shopGit, blogGit), current = "shop", userHome = home)
    assertEquals(listOf("shop", "blog"), tabs.map { it.title })
    assertEquals(listOf(false, false), tabs.map { it.isMainCheckout })
  }

  @Test
  fun `checkouts on the same commit, or without a branch, fall back to their locations`() {
    val a = OpenProject("a", "shop", "/Users/me/a/shop", checkout("shop", "3f2c1ab"))
    val b = OpenProject("b", "shop", "/Users/me/b/shop", checkout("shop", "3f2c1ab"))
    val c = OpenProject("c", "shop", "/Users/me/c/shop", checkout("shop", null))
    assertEquals(listOf("a/shop", "b/shop", "c/shop"), ProjectTabs.tabs(listOf(a, b, c), current = "a", userHome = home).map { it.title })
  }

  @Test
  fun `a project that opens goes right after the last tab of its repository`() {
    val repos = mapOf("m" to "colo", "w1" to "colo", "x" to "blog", "w2" to "colo", "y" to null)
    val order = listOf("m", "x", "w1", "y", "w2")
    assertEquals(listOf("m", "x", "w1", "w2", "y"), ProjectTabs.placed(order, "w2", repos::get))
    assertEquals(listOf("m", "w1", "x", "y"), ProjectTabs.placed(listOf("m", "x", "y", "w1"), "w1", repos::get))
  }

  @Test
  fun `a project with no repository, or alone in it, stays where it opened`() {
    val repos = mapOf("m" to "colo", "x" to "blog", "y" to null)
    assertEquals(listOf("m", "y", "x"), ProjectTabs.placed(listOf("m", "y", "x"), "y", repos::get))
    assertEquals(listOf("x", "m", "y"), ProjectTabs.placed(listOf("x", "m", "y"), "x", repos::get))
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
  fun `both projects side by side are marked in the tabs`() {
    val tabs = ProjectTabs.tabs(listOf(shop, blog, tools), current = "blog", userHome = home, sideBySide = listOf("blog", "tools"))
    assertEquals(listOf(false, true, true), tabs.map { it.isSideBySide })
    assertEquals(listOf(false, true, false), tabs.map { it.isCurrent })
  }

}
