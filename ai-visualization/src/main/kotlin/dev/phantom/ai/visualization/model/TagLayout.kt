// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.visualization.model

/**
 * A tag that wants the rectangle at ([x], [y]) of [w] x [h] pixels; [id] breaks ties so the result is deterministic. An [up] tag
 * (one above a head) moves up instead of down when it would overlap.
 */
data class TagRequest(val id: String, val x: Int, val y: Int, val w: Int, val h: Int, val up: Boolean = false)

data class TagRect(val id: String, val x: Int, val y: Int, val w: Int, val h: Int) {
  fun intersects(o: TagRect) = x < o.x + o.w && o.x < x + w && y < o.y + o.h && o.y < y + h
}

/**
 * Offset stacking: tags are placed top to bottom, left to right; one that would overlap a placed tag or an obstacle (a name plate,
 * a state bubble) moves down by whole tag heights, an [TagRequest.up] one up, and that one also keeps clear of [upObstacles].
 */
object TagLayout {
  fun layout(requests: List<TagRequest>, gap: Int = 2, obstacles: List<TagRect> = emptyList(), upObstacles: List<TagRect> = emptyList()): List<TagRect> {
    val placed = ArrayList<TagRect>(requests.size)
    for (r in requests.sortedWith(compareBy({ it.y }, { it.x }, { it.id }))) {
      var k = 0
      var rect: TagRect
      val step = if (r.up) -(r.h + gap) else r.h + gap
      do {
        rect = TagRect(r.id, r.x, r.y + k * step, r.w, r.h)
        k++
      } while (placed.any { rect.intersects(it) } || obstacles.any { rect.intersects(it) } || (r.up && upObstacles.any { rect.intersects(it) }))
      placed += rect
    }
    return placed
  }
}
