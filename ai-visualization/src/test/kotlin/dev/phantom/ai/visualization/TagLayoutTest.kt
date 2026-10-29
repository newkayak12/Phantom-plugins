// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.visualization

import dev.phantom.ai.visualization.model.TagLayout
import dev.phantom.ai.visualization.model.TagRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TagLayoutTest {
  private fun dense() = (0 until 40).map { TagRequest("t$it", 100 + (it % 5) * 7, 50 + (it % 3) * 4, 90 + it % 4 * 10, 20) }

  @Test
  fun denseTagsNeverIntersect() {
    val out = TagLayout.layout(dense())
    assertEquals(40, out.size)
    for (i in out.indices) for (j in i + 1 until out.size) assertFalse("${out[i]} vs ${out[j]}", out[i].intersects(out[j]))
  }

  @Test
  fun resultIsDeterministicAndIndependentOfInputOrder() {
    val a = TagLayout.layout(dense())
    assertEquals(a, TagLayout.layout(dense()))
    assertEquals(a.sortedBy { it.id }, TagLayout.layout(dense().reversed()).sortedBy { it.id })
  }

  @Test
  fun separateTagsStayWhereTheyAre() {
    val r = listOf(TagRequest("a", 0, 0, 50, 20), TagRequest("b", 100, 0, 50, 20))
    assertEquals(listOf(0, 0), TagLayout.layout(r).map { it.y })
  }

  @Test
  fun overlappingTagMovesDownByItsHeightPlusGap() {
    val r = listOf(TagRequest("a", 0, 0, 50, 20), TagRequest("b", 10, 5, 50, 20))
    val out = TagLayout.layout(r, gap = 2).associateBy { it.id }
    assertEquals(0, out.getValue("a").y)
    assertEquals(27, out.getValue("b").y)
  }
}
