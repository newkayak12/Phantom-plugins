// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import dev.phantom.ai.core.Group
import dev.phantom.ai.core.GroupTree
import dev.phantom.ai.core.Repo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GroupTreeTest {
  private fun g(id: String, parent: String? = null, order: Int = 0) = Group(id, id, parent, order)

  @Test
  fun `missing parent becomes root`() {
    val t = GroupTree.normalize(listOf(g("a", "nope")), emptyList())
    assertNull(t.groups.single().parentId)
  }

  @Test
  fun `cycle is cut to root`() {
    val t = GroupTree.normalize(listOf(g("a", "b"), g("b", "a"), g("c", "c")), emptyList())
    assertEquals(3, t.groups.size)
    // acyclic after cutting: walking up from each group ends at root
    for (x in t.groups) {
      var p = x.parentId
      var steps = 0
      while (p != null) { p = t.groups.first { it.id == p }.parentId; check(++steps < 10) }
    }
  }

  @Test
  fun `orphan repo is ungrouped`() {
    val t = GroupTree.normalize(listOf(g("a")), listOf(Repo("/r", "gone", 0)))
    assertNull(t.repos.single().groupId)
  }

  @Test
  fun `order is stable and renumbered`() {
    val t = GroupTree.normalize(listOf(g("x", null, 5), g("y", null, 5), g("z", null, 1), g("k", "x", 9)), emptyList())
    assertEquals(listOf("z", "x", "y"), t.childrenOf(null).map { it.id })
    assertEquals(listOf(0, 1, 2), t.childrenOf(null).map { it.order })
    assertEquals(listOf("k"), t.childrenOf("x").map { it.id })
  }

  @Test
  fun `a repo is in one group only`() {
    val t = GroupTree.normalize(listOf(g("a"), g("b")), listOf(Repo("/r", "a", 0), Repo("/r", "b", 0), Repo("/s", "b", 3)))
    assertEquals(listOf("/r"), t.reposOf("a").map { it.path })
    assertEquals(listOf("/s"), t.reposOf("b").map { it.path })
    assertEquals(2, t.repos.size)
  }

  @Test
  fun `repeated group id keeps the first`() {
    val t = GroupTree.normalize(listOf(Group("a", "first", null, 0), Group("a", "second", null, 1)), emptyList())
    assertEquals("first", t.groups.single().name)
  }
}
