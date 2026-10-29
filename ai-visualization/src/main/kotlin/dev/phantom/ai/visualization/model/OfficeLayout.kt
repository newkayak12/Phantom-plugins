// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.visualization.model

import dev.phantom.ai.visualization.theme.Atlas
import dev.phantom.ai.visualization.theme.Theme
import kotlin.math.ceil
import kotlin.math.roundToInt

/** An axis-aligned rectangle in scene pixels (native resolution, before zoom). */
data class Rect(val x: Int, val y: Int, val w: Int, val h: Int) {
  fun intersects(o: Rect) = x < o.x + o.w && o.x < x + w && y < o.y + o.h && o.y < y + h
  fun contains(px: Int, py: Int) = px >= x && py >= y && px < x + w && py < y + h
}

/** A rectangle on the floor in iso units: from ([u], [v]) [w] along u and [d] along v. */
data class URect(val u: Int, val v: Int, val w: Int, val d: Int) {
  fun intersects(o: URect) = u < o.u + o.w && o.u < u + w && v < o.v + o.d && o.v < v + d
  fun inside(size: Int) = u >= 0 && v >= 0 && u + w <= size && v + d <= size
}

enum class ItemKind { FLOOR_BASE, SPRITE, RUG, NAMEPLATE, PERSON }

/** A person that still needs a spot: [seat] is its desk index (or -1), ([u], [v]) the spot it would like. */
private class Standing(val ai: Int, val person: Person, val seat: Int, val u: Double, val v: Double, val rug: URect)

/**
 * One thing to draw. ([x], [y]) is the scene pixel the frame's anchor goes to; the list is sorted back to front by ([layer], [key]).
 * [label] is the name plate text; [tabId] names the person of a [ItemKind.PERSON] item.
 */
data class DrawItem(
  val layer: Int,
  val key: Double,
  val kind: ItemKind,
  val frame: String?,
  val x: Int,
  val y: Int,
  val label: String? = null,
  val tabId: String? = null,
)

data class SeatSlot(val orientation: Orientation, val u: Int, val v: Int)

/** Where one worktree's desk area goes: its rug, the plate position and the desks. */
data class AreaSlot(val rug: URect, val rugStyle: Int, val plateU: Int, val plateV: Int, val seats: List<SeatSlot>)

class AreaPlacement(val index: Int, val area: DeskArea, val slot: AreaSlot, val rugBounds: Rect, val plateX: Int, val plateY: Int) {
  val worktreePath: String get() = area.worktree.path
}

/** A desk of an area and whether someone sits at it; [bounds] is the desk sprite's rectangle, the click target for an empty desk. */
class DeskPlacement(val areaIndex: Int, val worktreePath: String, val seat: Int, val bounds: Rect, val occupiedBy: String?)

/**
 * A person: [x], [y] is the feet point, [bounds] the sprite rectangle, [bubbleBounds] the state bubble above the head.
 * The tag goes [TAG_DY] screen pixels (not scaled) below the feet point, a little more while [sitting].
 */
class PersonPlacement(
  val person: Person,
  val areaIndex: Int,
  val seat: Int,
  val frame: String,
  val x: Int,
  val y: Int,
  val sitting: Boolean,
  val bounds: Rect,
  val bubbleFrame: String,
  val bubbleBounds: Rect,
) {
  val tabId: String get() = person.session.tabId
  val state: VizState get() = person.state
  val tagDy: Int get() = if (sitting) TAG_DY_SEATED else TAG_DY

  companion object {
    const val TAG_DY = 10
    const val TAG_DY_SEATED = 12
  }
}

/** One page of one floor, ready to draw: [items] back to front, plus the things to hit-test and label. */
class Scene(
  val items: List<DrawItem>,
  val areas: List<AreaPlacement>,
  val desks: List<DeskPlacement>,
  val people: List<PersonPlacement>,
  val page: Int,
  val pageCount: Int,
  val width: Int,
  val height: Int,
)

/**
 * The room: a 96-unit square (6x6 tiles of 16 units) with a wall along each of the two back edges, seen in 2:1 iso, one unit
 * is (2, 1) pixels. Lays out up to [PER_PAGE] worktrees per page as desk areas (rug, plate, desks) and their sessions as
 * people. Knows no color or frame name: those come from the [theme] and the [atlas].
 */
class OfficeLayout(private val theme: Theme, private val atlas: Atlas) {

  fun layout(areas: List<DeskArea>, page: Int = 0): Scene {
    val pages = pageCount(areas.size)
    val pg = page.coerceIn(0, pages - 1)
    val shown = areas.drop(pg * PER_PAGE).take(PER_PAGE)
    val slots = slots(shown.size)
    val items = ArrayList<DrawItem>()
    items += DrawItem(0, -1.0, ItemKind.FLOOR_BASE, null, proj(0, 0), projY(0, 0))
    for (i in 0 until ROOM / SEG) for (j in 0 until ROOM / SEG) {
      val tile = theme.floorTiles[Math.floorMod(i * 31 + j * 17 + (i xor j), theme.floorTiles.size)]
      items += sprite(0, 1.0 + i + j, tile, i * SEG, j * SEG)
    }
    items += sprite(1, 0.0, theme.wallCorner, 0, 0)
    for (i in 0 until ROOM / SEG) {
      items += sprite(1, 1.0 + i, if (i in theme.windowSegmentsRight) theme.wallRightWindow else theme.wallRight, i * SEG, 0)
      items += sprite(1, 1.0 + i, if (i in theme.windowSegmentsLeft) theme.wallLeftWindow else theme.wallLeft, 0, i * SEG)
    }
    for (d in theme.decor) items += sprite(d.layer, d.key, d.frame, d.u, d.v, d.z)

    val placements = ArrayList<AreaPlacement>()
    val desks = ArrayList<DeskPlacement>()
    val people = ArrayList<PersonPlacement>()
    val standing = ArrayList<Standing>()
    shown.forEachIndexed { ai, area ->
      val slot = slots[ai]
      val r = slot.rug
      items += rugTiles(ai, r, theme.rugFrame(slot.rugStyle))
      val px = proj(slot.plateU, slot.plateV)
      val py = projY(slot.plateU, slot.plateV)
      val label = area.worktree.title + (area.worktree.prNumber?.let { " #$it" } ?: "")
      items += DrawItem(5, (slot.plateU + slot.plateV).toDouble(), ItemKind.NAMEPLATE, theme.nameplate, px, py, label = label)
      placements += AreaPlacement(ai, area, slot, rugBounds(r), px, py)

      slot.seats.forEachIndexed { si, seat -> items += seatItems(ai, area, si, seat, desks, people, standing, r) }
      // people without a desk stand on the rug
      area.people.drop(slot.seats.size).forEach { p -> standing += Standing(ai, p, -1, 0.0, 0.0, r) }
    }
    // standing people go last: each takes the first free spot (its own by the desk, else a grid over its rug, else anywhere in the room)
    for (st in standing.sortedBy { if (it.seat >= 0) 0 else 1 }) {
      val spots = candidates(st)
      val pick = spots.firstOrNull { (u, v) -> fits(placementFor(st.ai, st.person, st.seat, u, v, false, 0), people, strict = true) }
        ?: spots.firstOrNull { (u, v) -> fits(placementFor(st.ai, st.person, st.seat, u, v, false, 0), people, strict = false) }
        ?: spots.last()
      items += personItems(st.ai, st.person, st.seat, pick.first, pick.second, sitting = false, people)
    }
    items.sortWith(compareBy({ it.layer }, { it.key }))
    return Scene(items, placements, desks, people, pg, pages, SCENE_W, SCENE_H)
  }

  private fun seatItems(
    ai: Int, area: DeskArea, si: Int, seat: SeatSlot,
    desks: MutableList<DeskPlacement>, people: MutableList<PersonPlacement>, standing: MutableList<Standing>, rug: URect,
  ): List<DrawItem> {
    val o = seat.orientation
    val res = ArrayList<DrawItem>()
    val u = seat.u
    val v = seat.v
    val deskFrame = theme.desk(o)
    val deskItem = sprite(5, (u + v + 15).toDouble(), deskFrame, u, v)
    res += deskItem
    // chair centre; the desk spans 20x10 (rw) or 10x20 (lw) units from (u, v)
    val cu = if (o == Orientation.RW) u + 10.0 else u + 12.5
    val cv = if (o == Orientation.RW) v + 12.5 else v + 10.0
    val base = cu + cv
    val chair = theme.chair(o)
    res += sprite(5, base, chair.seat, cu, cv)
    chair.back?.let { res += sprite(5, base + 0.2, it, cu, cv) }
    val p = area.people.getOrNull(si)
    val f = atlas[deskFrame]
    desks += DeskPlacement(ai, area.worktree.path, si, Rect(deskItem.x - f.anchorX, deskItem.y - f.anchorY, f.w, f.h), p?.session?.tabId)
    if (p != null) {
      if (p.state == VizState.WORKING || p.state == VizState.IDLE) {
        res += personItems(ai, p, si, cu, cv, sitting = true, people, z = 7, key = base + 0.1)
      } else {
        val (su, sv) = when {
          o == Orientation.LW -> u + 16.0 to v + 22.0
          si % 2 == 1 -> u + 15.0 to v + 19.0
          else -> u - 3.0 to v + 16.0
        }
        standing += Standing(ai, p, si, su, sv, rug)
      }
    }
    return res
  }

  private fun placementFor(ai: Int, p: Person, seat: Int, u: Double, v: Double, sitting: Boolean, z: Int): PersonPlacement {
    val frame = theme.person(p.seed % theme.personVariants, p.state)
    val x = proj(u, v)
    val y = projY(u, v, z)
    val f = atlas[frame]
    val bubble = theme.bubble(p.state)
    val b = atlas[bubble]
    val head = y - f.anchorY
    val bubbleBounds = Rect(x + theme.bubbleAnchorDx - b.anchorX, head + theme.bubbleAnchorDy - b.anchorY, b.w, b.h)
    return PersonPlacement(p, ai, seat, frame, x, y, sitting, Rect(x - f.anchorX, y - f.anchorY, f.w, f.h), bubble, bubbleBounds)
  }

  /** Strict: neither the sprite nor the bubble touches another person's sprite or bubble. Otherwise only the sprites must not touch. */
  private fun fits(c: PersonPlacement, others: List<PersonPlacement>, strict: Boolean): Boolean = others.none { o ->
    c.bounds.intersects(o.bounds) || (strict && (c.bubbleBounds.intersects(o.bubbleBounds) ||
      c.bubbleBounds.intersects(o.bounds) || c.bounds.intersects(o.bubbleBounds)))
  }

  private fun candidates(st: Standing): List<Pair<Double, Double>> {
    val r = st.rug
    val out = ArrayList<Pair<Double, Double>>()
    if (st.seat >= 0) out += st.u to st.v
    fun grid(u0: Int, u1: Int, v0: Int, v1: Int, du: Int, dv: Int) {
      var v = v1
      while (v >= v0) {
        var u = u0
        while (u <= u1) { out += u.toDouble() to v.toDouble(); u += du }
        v -= dv
      }
    }
    grid(r.u + 6, r.u + r.w - 4, r.v + 6, r.v + r.d - 6, 7, 6)
    grid(6, ROOM - 6, 6, ROOM - 6, 6, 6)
    return out
  }

  private fun personItems(
    ai: Int, p: Person, seat: Int, u: Double, v: Double, sitting: Boolean, people: MutableList<PersonPlacement>,
    z: Int = 0, key: Double = u + v,
  ): DrawItem {
    val pl = placementFor(ai, p, seat, u, v, sitting, z)
    people += pl
    return DrawItem(5, key, ItemKind.PERSON, pl.frame, pl.x, pl.y, tabId = p.session.tabId)
  }

  /** Whole rug frames laid edge to edge, as many as fit the footprint, centred on it. */
  private fun rugTiles(ai: Int, r: URect, frame: String): List<DrawItem> {
    val tu = theme.rugTileU
    val tv = theme.rugTileV
    val cols = maxOf(1, r.w / tu)
    val rows = maxOf(1, r.d / tv)
    val u0 = r.u + (r.w - cols * tu) / 2
    val v0 = r.v + (r.d - rows * tv) / 2
    return (0 until cols).flatMap { c ->
      (0 until rows).map { k ->
        val u = u0 + c * tu
        val v = v0 + k * tv
        DrawItem(3, ai + (c + k) / 100.0, ItemKind.RUG, frame, proj(u, v), projY(u, v))
      }
    }
  }

  private fun sprite(layer: Int, key: Double, frame: String, u: Number, v: Number, z: Int = 0) =
    DrawItem(layer, key, ItemKind.SPRITE, frame, proj(u.toDouble(), v.toDouble()), projY(u.toDouble(), v.toDouble(), z))

  private fun rugBounds(r: URect): Rect {
    val x0 = proj(r.u, r.v + r.d)
    val x1 = proj(r.u + r.w, r.v)
    return Rect(x0, projY(r.u, r.v), x1 - x0, projY(r.u + r.w, r.v + r.d) - projY(r.u, r.v))
  }

  companion object {
    const val ROOM = 96
    const val SEG = 16
    const val PER_PAGE = 6
    const val OX = 275
    const val OY = 86
    const val SCENE_W = 480
    const val SCENE_H = 300

    fun proj(u: Int, v: Int): Int = OX + 2 * u - 2 * v
    fun proj(u: Double, v: Double): Int = (OX + 2 * u - 2 * v).roundToInt()
    fun projY(u: Int, v: Int, z: Int = 0): Int = OY + u + v - z
    fun projY(u: Double, v: Double, z: Int = 0): Int = (OY + u + v - z).roundToInt()

    fun pageCount(worktrees: Int): Int = maxOf(1, ceil(worktrees / PER_PAGE.toDouble()).toInt())

    private fun rw(cols: Int, rows: Int = 1, du: Int = 20, dv: Int = 0, firstU: Int, firstV: Int) =
      (0 until rows).flatMap { r -> (0 until cols).map { c -> SeatSlot(Orientation.RW, firstU + c * du, firstV + r * dv) } }

    /** The desk areas for [count] (1..6) worktrees: inside the room, no two rugs overlap, every desk inside its rug. */
    fun slots(count: Int): List<AreaSlot> = when (count.coerceIn(1, PER_PAGE)) {
      1 -> listOf(AreaSlot(URect(4, 4, 88, 88), 0, 48, 94, rw(3, 2, 24, 38, 12, 14)))
      2 -> listOf(
        AreaSlot(URect(4, 4, 88, 40), 0, 48, 47, rw(3, 1, 24, 0, 12, 12)),
        AreaSlot(URect(4, 52, 88, 40), 1, 48, 95, rw(3, 1, 24, 0, 12, 60)),
      )
      3 -> listOf(
        AreaSlot(URect(9, 4, 46, 34), 0, 32, 41, listOf(SeatSlot(Orientation.RW, 14, 8), SeatSlot(Orientation.RW, 34, 8))),
        AreaSlot(URect(61, 3, 32, 50), 1, 87, 57, listOf(SeatSlot(Orientation.LW, 64, 6), SeatSlot(Orientation.LW, 64, 29))),
        AreaSlot(URect(19, 58, 56, 32), 2, 50, 93, listOf(SeatSlot(Orientation.RW, 24, 62), SeatSlot(Orientation.RW, 48, 62))),
      )
      4 -> (0 until 4).map { i ->
        val ru = 2 + 47 * (i % 2)
        val rv = 4 + 48 * (i / 2)
        AreaSlot(URect(ru, rv, 45, 36), i % 3, ru + 22, rv + 39, rw(2, 1, 20, 0, ru + 3, rv + 4))
      }
      else -> (0 until count.coerceIn(5, PER_PAGE)).map { i ->
        val ru = 3 + 31 * (i % 3)
        val rv = 3 + 47 * (i / 3)
        AreaSlot(
          URect(ru, rv, 30, 43), i % 3, ru + 24, rv + 45,
          listOf(SeatSlot(Orientation.LW, ru + 3, rv + 2), SeatSlot(Orientation.LW, ru + 3, rv + 22)),
        )
      }
    }
  }
}
