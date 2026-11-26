// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.workspaces

import dev.phantom.workspaces.core.KnownProject
import dev.phantom.workspaces.core.WorkspaceList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceListTest {
  private val home = "/Users/me"
  private val repo = "/Users/me/src/shop/.git"
  private val shop = KnownProject("/Users/me/src/shop", "shop", repo, "main", isMain = true, key = "shop")
  private val login = KnownProject("/Users/me/wt/login", "login", repo, "feature/login")
  private val cart = KnownProject("/Users/me/wt/cart", "cart", repo, "cart", key = "cart")
  private val blog = KnownProject("/Users/me/blog", "blog")

  @Test
  fun `a repository's worktrees are indented under its main checkout, by name, open or not`() {
    val rows = WorkspaceList.rows(listOf(cart, blog, shop, login), current = "cart", userHome = home)
    assertEquals(listOf("shop", "cart", "login", "blog"), rows.map { it.name })
    assertEquals(listOf(false, true, true, false), rows.map { it.isIndented })
    assertEquals(listOf("~/src/shop", "~/wt/cart", "~/wt/login", "~/blog"), rows.map { it.location })
  }

  @Test
  fun `open projects are marked and the current one is selected`() {
    val rows = WorkspaceList.rows(listOf(shop, cart, login, blog), current = "cart", userHome = home)
    assertEquals(listOf(true, true, false, false), rows.map { it.isOpen })
    assertEquals(listOf("cart"), rows.filter { it.isCurrent }.map { it.name })
  }

  @Test
  fun `a folder known as recent and as open counts once, as open`() {
    val recent = blog.copy()
    val open = blog.copy(key = "blog")
    val rows = WorkspaceList.rows(listOf(recent, open), current = null, userHome = home)
    assertEquals(1, rows.size)
    assertTrue(rows.single().isOpen)
    assertFalse(rows.single().isCurrent)
  }

  @Test
  fun `worktrees of a repository without a main checkout are not indented`() {
    val rows = WorkspaceList.rows(listOf(login, cart), current = null, userHome = home)
    assertEquals(listOf("cart", "login"), rows.map { it.name })
    assertTrue(rows.none { it.isIndented })
  }

  @Test
  fun `search finds name, location and branch, and keeps a found worktree's main checkout`() {
    val rows = WorkspaceList.rows(listOf(shop, cart, login, blog), current = null, userHome = home)
    assertEquals(listOf("shop", "login"), WorkspaceList.filtered(rows, "FEATURE/").map { it.name })
    assertEquals(listOf("blog"), WorkspaceList.filtered(rows, "~/blog").map { it.name })
    assertEquals(listOf("shop", "cart"), WorkspaceList.filtered(rows, "cart").map { it.name })
    assertEquals(rows, WorkspaceList.filtered(rows, "  "))
    assertTrue(WorkspaceList.filtered(rows, "nothing").isEmpty())
  }
}
