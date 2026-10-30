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

enum class ItemKind { FLOOR_BASE, SPRITE, RUG, NAMEPLATE, PERSON, HELPER }

/**
 * A person that still needs a spot: [seat] is its desk index (or -1), ([u], [v]) the spot it would like. [queue]: it waits for
 * the CEO's sign-off and lines up in front of the CEO's chair at ([u], [v]), on or before the CEO's rug [rug], not by its desk.
 */
private class Standing(val ai: Int, val person: Person, val seat: Int, val u: Double, val v: Double, val rug: URect, val queue: Boolean = false)

/**
 * One thing to draw. ([x], [y]) is the scene pixel the frame's anchor goes to; the list is sorted back to front by ([layer], [key]).
 * [label] is the name plate text; [tabId] names the person of a [ItemKind.PERSON] item and the helper ([HelperPlacement.key]) of a
 * [ItemKind.HELPER] item. A [scale] above 1 draws the frame shrunk by that whole factor (see [Atlas.scaled]).
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
  val scale: Int = 1,
)

data class SeatSlot(val orientation: Orientation, val u: Int, val v: Int)

/**
 * One partition segment between desk areas: [SEG] units long from ([u], [v]), along u for [Orientation.RW] and along v for
 * [Orientation.LW], one unit thick.
 */
data class PartitionSlot(val orientation: Orientation, val u: Int, val v: Int) {
  val footprint: URect get() = if (orientation == Orientation.RW) URect(u, v, OfficeLayout.SEG, 1) else URect(u, v, 1, OfficeLayout.SEG)
}

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
  val u: Double = 0.0,
  val v: Double = 0.0,
  /** The orientation of the desk it sits at, null when standing: see [Theme.personFrame]. */
  val facing: Orientation? = null,
) {
  val tabId: String get() = person.session.tabId
  val state: VizState get() = person.state
  val tagDy: Int get() = if (sitting) TAG_DY_SEATED else TAG_DY

  companion object {
    const val TAG_DY = 10
    const val TAG_DY_SEATED = 12
  }
}

/**
 * A running subagent of [parent] as its subordinate at floor spot ([u], [v]): a person frame [frame] (working, look [variant] from
 * the subagent's id) shrunk by [scale] (1 for the default full size) with its feet at ([x], [y]), on a chair whose rectangle is
 * [stoolBounds] (null: none). [bounds] is the person sprite, [footprint] sprite and chair.
 */
class HelperPlacement(
  val parent: PersonPlacement,
  val helper: Helper,
  val index: Int,
  val variant: Int,
  val frame: String,
  val scale: Int,
  val u: Double,
  val v: Double,
  val x: Int,
  val y: Int,
  val bounds: Rect,
  val stoolBounds: Rect?,
) {
  val tabId: String get() = parent.tabId
  val key: String get() = "$tabId/${helper.id}"
  val footprint: Rect get() = stoolBounds?.let { union(bounds, it) } ?: bounds

  private fun union(a: Rect, b: Rect): Rect {
    val x0 = minOf(a.x, b.x)
    val y0 = minOf(a.y, b.y)
    return Rect(x0, y0, maxOf(a.x + a.w, b.x + b.w) - x0, maxOf(a.y + a.h, b.y + b.h) - y0)
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
  val helpers: List<HelperPlacement> = emptyList(),
  /** Per parent tab id, how many of its running subagents are not drawn (over the cap or no room): its "+N". */
  val helperOverflow: Map<String, Int> = emptyMap(),
  /** The part of the scene worth showing (the desk areas with room above for heads and labels); the canvas fits its zoom to it. */
  val focus: Rect = Rect(0, 0, width, height),
)

/**
 * The room: a 96-unit square (6x6 tiles of 16 units) with a wall along each of the two back edges, seen in 2:1 iso, one unit
 * is (2, 1) pixels. Lays out up to [PER_PAGE] worktrees per page as desk areas (rug, plate, desks) and their sessions as
 * people. Knows no color or frame name: those come from the [theme] and the [atlas].
 */
class OfficeLayout(private val theme: Theme, private val atlas: Atlas) {
  /** Person frames are this many times denser than the scene: see [Theme.personDetail]. */
  private val detail = theme.personDetail.coerceAtLeast(1)

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
    if (shown.isNotEmpty()) for (ps in partitions(shown.size)) {
      val frame = theme.partition(ps.orientation) ?: continue
      val f = ps.footprint
      items += sprite(5, f.u + f.w / 2.0 + f.v + f.d / 2.0, frame, ps.u, ps.v)
    }

    val placements = ArrayList<AreaPlacement>()
    val desks = ArrayList<DeskPlacement>()
    val people = ArrayList<PersonPlacement>()
    val standing = ArrayList<Standing>()
    // what the others ask the CEO to sign off, on the whole floor
    val approvals = areas.filter { it.kind != AreaKind.CEO }.sumOf { a -> a.people.count { it.state == VizState.WAITING } }
    shown.forEachIndexed { ai, area ->
      val slot = slots[ai]
      val r = slot.rug
      items += rugTiles(ai, r, theme.rugFrame(slot.rugStyle))
      val px = proj(slot.plateU, slot.plateV)
      val py = projY(slot.plateU, slot.plateV)
      val label = if (area.kind == AreaKind.CEO && approvals > 0) "${area.label} \u00b7 \uacb0\uc7ac $approvals" else area.label
      items += DrawItem(5, (slot.plateU + slot.plateV).toDouble(), ItemKind.NAMEPLATE, theme.nameplate, px, py, label = label)
      placements += AreaPlacement(ai, area, slot, rugBounds(r), px, py)

      val seats = seatIndices(area.kind, slot)
      seats.forEachIndexed { pi, si -> items += seatItems(ai, area, si, pi, slot.seats[si], desks, people, standing, r) }
      // people without a desk stand on the rug
      area.people.drop(seats.size).forEach { p -> standing += Standing(ai, p, -1, 0.0, 0.0, r) }
    }
    // whoever waits for an answer takes it to the CEO: they line up on the CEO's rug, their desk kept
    val ceo = shown.indexOfFirst { it.kind == AreaKind.CEO }
    if (ceo >= 0) {
      val seat = slots[ceo].seats[seatIndices(AreaKind.CEO, slots[ceo]).single()]
      val (cu, cv) = chairOf(seat)
      standing.replaceAll { st ->
        if (st.person.state != VizState.WAITING || shown[st.ai].kind == AreaKind.CEO) st
        else Standing(st.ai, st.person, st.seat, cu, cv, slots[ceo].rug, queue = true)
      }
    }
    // standing people go last: each takes the first free spot (its own by the desk, else a grid over its rug, else anywhere in the room)
    for (st in standing.sortedBy { if (it.queue) 0 else if (it.seat >= 0) 1 else 2 }) {
      val spots = candidates(st)
      val pick = spots.firstOrNull { (u, v) -> fits(placementFor(st.ai, st.person, st.seat, u, v, false, 0), people, strict = true) }
        ?: spots.firstOrNull { (u, v) -> fits(placementFor(st.ai, st.person, st.seat, u, v, false, 0), people, strict = false) }
        // a full room: where it covers the fewest people
        ?: spots.minBy { (u, v) -> placementFor(st.ai, st.person, st.seat, u, v, false, 0).bounds.let { b -> people.count { it.bounds.intersects(b) } } }
      items += personItems(st.ai, st.person, st.seat, pick.first, pick.second, sitting = false, people)
    }
    val helpers = ArrayList<HelperPlacement>()
    val overflow = LinkedHashMap<String, Int>()
    if (people.any { it.person.helperCount > 0 }) placeHelpers(slots, items, desks, people, helpers, overflow)
    items.sortWith(compareBy({ it.layer }, { it.key }))
    return Scene(items, placements, desks, people, pg, pages, SCENE_W, SCENE_H, helpers, overflow, focus(placements, desks, people, helpers))
  }

  /**
   * The rugs and desks of the areas shown and every person (with its bubble) and subordinate, with [FOCUS_TOP] pixels above for
   * the activity labels and a margin elsewhere; the whole scene when there is none. A person who found no room on its rug and
   * stands elsewhere in the room is inside it too, so a fitted view never crops anyone.
   */
  private fun focus(areas: List<AreaPlacement>, desks: List<DeskPlacement>, people: List<PersonPlacement>, helpers: List<HelperPlacement>): Rect {
    val rects = areas.map { it.rugBounds } + desks.map { it.bounds } + people.flatMap { listOf(it.bounds, it.bubbleBounds) } +
      helpers.map { it.footprint }
    if (rects.isEmpty()) return Rect(0, 0, SCENE_W, SCENE_H)
    val x0 = rects.minOf { it.x } - FOCUS_SIDE
    val y0 = rects.minOf { it.y } - FOCUS_TOP
    val x1 = rects.maxOf { it.x + it.w } + FOCUS_SIDE
    val y1 = rects.maxOf { it.y + it.h } + FOCUS_BOTTOM
    return Rect(maxOf(0, x0), maxOf(0, y0), minOf(SCENE_W, x1) - maxOf(0, x0), minOf(SCENE_H, y1) - maxOf(0, y0))
  }

  /**
   * Subordinates (helpers) go after every person: each takes the nearest free spot beside its parent on the parent's rug where it
   * stays wholly visible: its chair and itself clear of every person's sprite and bubble and of every subordinate placed before it,
   * no opaque pixel of furniture drawn in front of it (desk, chair, decor, partition) over an opaque pixel of it, and none of it over
   * an opaque pixel of its own area's desks (its chair may tuck under one) unless [Theme.helperMayCoverOwnDesks]. It may sit in
   * front of another area's furniture, as people in an iso room do. One with no free spot is counted in the parent's "+N" instead.
   */
  private fun placeHelpers(
    slots: List<AreaSlot>, items: MutableList<DrawItem>, desks: List<DeskPlacement>, people: List<PersonPlacement>,
    helpers: MutableList<HelperPlacement>, overflow: MutableMap<String, Int>,
  ) {
    val furniture = items.filter { it.layer == 5 && it.kind == ItemKind.SPRITE }
    val taken = people.flatMap { listOf(it.bounds, it.bubbleBounds) }
    val s = theme.helperScale.coerceAtLeast(1)
    val stool = theme.helperStool
    for (p in people) {
      val rug = slots[p.areaIndex].rug
      val ownDesks = desks.filter { it.areaIndex == p.areaIndex }
        .map { theme.desk(slots[it.areaIndex].seats[it.seat].orientation) to it.bounds }
      var dropped = 0
      p.person.helpers.forEachIndexed { i, h ->
        val c = helperSpots(p.u, p.v).asSequence()
          .filter { (u, v) -> u - 2 >= rug.u && v - 2 >= rug.v && u + 2 <= rug.u + rug.w && v + 2 <= rug.v + rug.d }
          .map { (u, v) -> helperAt(p, h, i, u, v, s, stool) }
          .firstOrNull { c ->
            taken.none { it.intersects(c.footprint) } && helpers.none { it.footprint.intersects(c.footprint) } &&
              furniture.none { it.key > c.u + c.v && atlas[it.frame!!].let { f -> overlaps(it.frame, it.x - f.anchorX, it.y - f.anchorY, c) } } &&
              (theme.helperMayCoverOwnDesks || ownDesks.none { (frame, b) -> overlaps(frame, b.x, b.y, c) })
          }
        if (c == null) {
          dropped++
          return@forEachIndexed
        }
        helpers += c
        stool?.let { items += DrawItem(5, c.u + c.v - 0.05, ItemKind.SPRITE, it, proj(c.u, c.v), projY(c.u, c.v), scale = s) }
        items += DrawItem(5, c.u + c.v, ItemKind.HELPER, c.frame, c.x, c.y, label = h.name, tabId = c.key, scale = s)
      }
      val more = p.person.moreHelpers + dropped
      if (more > 0) overflow[p.tabId] = more
    }
  }

  private fun helperAt(p: PersonPlacement, h: Helper, i: Int, u: Double, v: Double, s: Int, stool: String?): HelperPlacement {
    // its own look, from its id; never its lead's, so the two do not read as one person
    val n = theme.personVariants
    val own = h.seed % n
    val variant = if (n > 1 && own == p.person.seed % n) (own + 1) % n else own
    val frame = theme.person(variant, VizState.WORKING)
    val x = proj(u, v)
    val y = projY(u, v, if (stool != null) SEAT_Z / s else 0)
    return HelperPlacement(p, h, i, variant, frame, s, u, v, x, y, boundsOf(frame, x, y, s * detail), stool?.let { boundsOf(it, x, projY(u, v), s) })
  }

  /**
   * Whether the frame [frame] with its top left at ([fx], [fy]) has an opaque pixel on an opaque pixel of the subordinate [c] in
   * any animation phase. Pixels, not rectangles: a theme's working pose may be wide (a cook's arms, a mop) and mostly transparent.
   */
  private fun overlaps(frame: String, fx: Int, fy: Int, c: HelperPlacement): Boolean {
    val f = atlas[frame]
    val poses = (0 until theme.animationPhases).map { ph ->
      val name = theme.personFrame(c.variant, VizState.WORKING, ph)
      val off = theme.personOffset(VizState.WORKING, ph)
      val sf = atlas.scaled(name, c.scale * detail)
      Triple(name, Rect(c.x + off.dx - sf.anchorX, c.y + off.dy - sf.anchorY, sf.w, sf.h), sf)
    }.distinct()
    for ((name, b, _) in poses) {
      if (!b.intersects(Rect(fx, fy, f.w, f.h))) continue
      for (py in maxOf(b.y, fy) until minOf(b.y + b.h, fy + f.h)) for (px in maxOf(b.x, fx) until minOf(b.x + b.w, fx + f.w)) {
        if (atlas.pixel(frame, px - fx, py - fy) ushr 24 != 0 && atlas.scaledPixel(name, c.scale * detail, px - b.x, py - b.y) ushr 24 != 0) return true
      }
    }
    return false
  }

  private fun boundsOf(frame: String, x: Int, y: Int, scale: Int): Rect = atlas.scaled(frame, scale).let { Rect(x - it.anchorX, y - it.anchorY, it.w, it.h) }

  private fun seatItems(
    ai: Int, area: DeskArea, si: Int, pi: Int, seat: SeatSlot,
    desks: MutableList<DeskPlacement>, people: MutableList<PersonPlacement>, standing: MutableList<Standing>, rug: URect,
  ): List<DrawItem> {
    val o = seat.orientation
    val res = ArrayList<DrawItem>()
    val u = seat.u
    val v = seat.v
    val deskFrame = theme.desk(o)
    val deskItem = sprite(5, (u + v + 15).toDouble(), deskFrame, u, v)
    res += deskItem
    val (cu, cv) = chairOf(seat)
    val base = cu + cv
    val chair = theme.chair(o)
    res += sprite(5, base, chair.seat, cu, cv)
    chair.back?.let { res += sprite(5, base + 0.2, it, cu, cv) }
    val p = area.people.getOrNull(pi)
    val f = atlas[deskFrame]
    desks += DeskPlacement(ai, area.worktree.path, si, Rect(deskItem.x - f.anchorX, deskItem.y - f.anchorY, f.w, f.h), p?.session?.tabId)
    if (p != null) {
      if (p.state == VizState.WORKING || p.state == VizState.IDLE) {
        res += personItems(ai, p, si, cu, cv, sitting = true, people, z = SEAT_Z, key = base + 0.1, facing = o)
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

  /** The centre of the chair at [seat]'s desk; the desk spans 20 x 10 (rw) or 10 x 20 (lw) units from the seat point. */
  private fun chairOf(seat: SeatSlot): Pair<Double, Double> =
    if (seat.orientation == Orientation.RW) seat.u + 10.0 to seat.v + 12.5 else seat.u + 12.5 to seat.v + 10.0

  /**
   * The desks of [slot] an area of [kind] uses, as indexes into its seats: the CEO and a team lead get the one desk nearest the
   * middle of the rug (the lead's subordinates sit around it), the open desks all of them.
   */
  private fun seatIndices(kind: AreaKind, slot: AreaSlot): List<Int> {
    if (kind == AreaKind.OPEN) return slot.seats.indices.toList()
    val r = slot.rug
    val cu = r.u + r.w / 2.0
    val cv = r.v + r.d / 2.0
    // a desk spans 20 x 10 (rw) or 10 x 20 (lw) units from its seat point
    fun centre(s: SeatSlot) = if (s.orientation == Orientation.RW) s.u + 10.0 to s.v + 5.0 else s.u + 5.0 to s.v + 10.0
    return listOf(slot.seats.indices.minBy { i -> centre(slot.seats[i]).let { (u, v) -> (u - cu) * (u - cu) + (v - cv) * (v - cv) } })
  }

  private fun placementFor(ai: Int, p: Person, seat: Int, u: Double, v: Double, sitting: Boolean, z: Int, facing: Orientation? = null): PersonPlacement {
    val frame = theme.personFrame(p.seed % theme.personVariants, p.state, 0, facing)
    val x = proj(u, v)
    val y = projY(u, v, z)
    val f = atlas.scaled(frame, detail)
    val bubble = theme.bubble(p.state)
    val b = atlas[bubble]
    val head = y - f.anchorY
    val bubbleBounds = Rect(x + theme.bubbleAnchorDx(facing) - b.anchorX, head + theme.bubbleAnchorDy - b.anchorY, b.w, b.h)
    return PersonPlacement(p, ai, seat, frame, x, y, sitting, Rect(x - f.anchorX, y - f.anchorY, f.w, f.h), bubble, bubbleBounds, u, v, facing)
  }

  /** Strict: neither the sprite nor the bubble touches another person's sprite or bubble. Otherwise only the sprites must not touch. */
  private fun fits(c: PersonPlacement, others: List<PersonPlacement>, strict: Boolean): Boolean = others.none { o ->
    c.bounds.intersects(o.bounds) || (strict && (c.bubbleBounds.intersects(o.bubbleBounds) ||
      c.bubbleBounds.intersects(o.bounds) || c.bounds.intersects(o.bubbleBounds)))
  }

  private fun candidates(st: Standing): List<Pair<Double, Double>> {
    val r = st.rug
    val out = ArrayList<Pair<Double, Double>>()
    if (st.queue) {
      // a line before the CEO's chair, nearest first; never behind the desk
      val q = ArrayList<Pair<Double, Double>>()
      for (du in -30..30 step 6) for (dv in 6..36 step 6) {
        val u = st.u + du
        val v = st.v + dv
        if (u >= 6 && v >= 6 && u <= ROOM - 6 && v <= ROOM - 6 && u + v > st.u + st.v + 4) q += u to v
      }
      out += q.sortedBy { (u, v) -> (u - st.u) * (u - st.u) + (v - st.v - 12) * (v - st.v - 12) }
    } else if (st.seat >= 0) out += st.u to st.v
    fun grid(u0: Int, u1: Int, v0: Int, v1: Int, du: Int, dv: Int) {
      var v = v1
      while (v >= v0) {
        var u = u0
        while (u <= u1) { out += u.toDouble() to v.toDouble(); u += du }
        v -= dv
      }
    }
    grid(r.u + 6, r.u + r.w - 4, r.v + 6, r.v + r.d - 6, 7, 6)
    grid(6, ROOM - 6, 6, ROOM - 6, 3, 3)
    return out
  }

  private fun personItems(
    ai: Int, p: Person, seat: Int, u: Double, v: Double, sitting: Boolean, people: MutableList<PersonPlacement>,
    z: Int = 0, key: Double = u + v, facing: Orientation? = null,
  ): DrawItem {
    val pl = placementFor(ai, p, seat, u, v, sitting, z, facing)
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

    /** How high a chair's seat is: a seated person's feet point is lifted by this many pixels. */
    const val SEAT_Z = 7

    /** How far (iso units along u and v) a subordinate may sit from its parent. */
    const val HELPER_REACH = 40

    /** How far (scene pixels) a subordinate's feet may be from its parent's, across and down or up the screen. */
    const val HELPER_REACH_X = 96
    const val HELPER_REACH_Y = 56

    /** Scene pixels the canvas keeps around the desk areas and people when it fits its zoom: above (activity labels), at the sides, below (tags). */
    const val FOCUS_TOP = 20
    const val FOCUS_SIDE = 16
    const val FOCUS_BOTTOM = 24

    /**
     * The spots a helper may take around a person at ([u], [v]), best first: beside it on screen, about a person's width away (along
     * the iso diagonal that keeps the screen y), before in front of or behind it, and the right before the left.
     */
    fun helperSpots(u: Double, v: Double): List<Pair<Double, Double>> {
      val out = ArrayList<Triple<Int, Int, Int>>()
      for (du in -HELPER_REACH..HELPER_REACH step 2) for (dv in -HELPER_REACH..HELPER_REACH step 2) {
        val sx = 2 * (du - dv)
        val sy = du + dv
        if (sx == 0 && sy == 0 || kotlin.math.abs(sx) > HELPER_REACH_X || kotlin.math.abs(sy) > HELPER_REACH_Y) continue
        // right below the person is where its tag goes: keep at least a person's width to the side
        val ax = kotlin.math.abs(sx)
        out += Triple(du, dv, ax + 2 * maxOf(0, 20 - ax) + 3 * kotlin.math.abs(sy) + (if (sy < 0) 6 else 0))
      }
      return out.sortedWith(compareBy({ it.third }, { -(it.first - it.second) }, { it.first }))
        .map { (du, dv) -> u + du to v + dv }
    }

    fun proj(u: Int, v: Int): Int = OX + 2 * u - 2 * v
    fun proj(u: Double, v: Double): Int = (OX + 2 * u - 2 * v).roundToInt()
    fun projY(u: Int, v: Int, z: Int = 0): Int = OY + u + v - z
    fun projY(u: Double, v: Double, z: Int = 0): Int = (OY + u + v - z).roundToInt()

    fun pageCount(worktrees: Int): Int = maxOf(1, ceil(worktrees / PER_PAGE.toDouble()).toInt())

    private fun rw(cols: Int, rows: Int = 1, du: Int = 20, dv: Int = 0, firstU: Int, firstV: Int) =
      (0 until rows).flatMap { r -> (0 until cols).map { c -> SeatSlot(Orientation.RW, firstU + c * du, firstV + r * dv) } }

    private fun line(o: Orientation, fixed: Int, vararg starts: Int) =
      starts.map { if (o == Orientation.RW) PartitionSlot(o, it, fixed) else PartitionSlot(o, fixed, it) }

    /**
     * The partition segments between the desk areas of [slots] ([count]): in the gaps between rugs, behind the name plates
     * in front of them, with a doorway where a wide plate would sit behind the glass. None for a single area. Used only when the
     * theme has partition frames.
     */
    fun partitions(count: Int): List<PartitionSlot> = when (count.coerceIn(1, PER_PAGE)) {
      1 -> emptyList()
      2 -> line(Orientation.RW, 45, 0, 16, 32, 48, 64, 80)
      3 -> line(Orientation.LW, 57, 0, 16, 32) + line(Orientation.RW, 54, 16, 32, 48, 64, 80)
      4 -> line(Orientation.RW, 41, 0, 16, 32, 48, 64, 80) + line(Orientation.LW, 48, 0, 16, 25, 42, 58, 74)
      else -> line(Orientation.RW, 47, 0, 16, 32, 48, 64, 80) +
        line(Orientation.LW, 33, 0, 16, 31, 64, 80) + line(Orientation.LW, 64, 0, 16, 31, 64, 80)
    }

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
