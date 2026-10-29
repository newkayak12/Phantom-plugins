// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.visualization.render

import dev.phantom.ai.visualization.model.DrawItem
import dev.phantom.ai.visualization.model.Elapsed
import dev.phantom.ai.visualization.model.Floor
import dev.phantom.ai.visualization.model.HelperPlacement
import dev.phantom.ai.visualization.model.ItemKind
import dev.phantom.ai.visualization.model.OfficeLayout
import dev.phantom.ai.visualization.model.PersonPlacement
import dev.phantom.ai.visualization.model.Rect
import dev.phantom.ai.visualization.model.Scene
import dev.phantom.ai.visualization.model.TagLayout
import dev.phantom.ai.visualization.model.TagRect
import dev.phantom.ai.visualization.model.TagRequest
import dev.phantom.ai.visualization.model.VizState
import dev.phantom.ai.visualization.theme.Atlas
import dev.phantom.ai.visualization.theme.Theme
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** The font all text is drawn with: the first installed family that can show Hangul (checked with canDisplayUpTo), else Dialog. */
object Fonts {
  const val HANGUL_SAMPLE = "작업 중 응답 필요 완료 대기"

  private val preferred = listOf("Apple SD Gothic Neo", "Malgun Gothic", "Noto Sans CJK KR", "Noto Sans KR", "NanumGothic", "Nanum Gothic", "Dialog")

  /** The family chosen for [sample]; "Dialog" when no installed font can display it. */
  fun family(sample: String = HANGUL_SAMPLE): String = cache.computeIfAbsent(sample) { choose(it) }

  private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()

  private fun shows(name: String, sample: String): Boolean {
    val f = Font(name, Font.PLAIN, 12)
    val real = f.family.equals(name, ignoreCase = true) || name == Font.DIALOG
    return real && f.canDisplayUpTo(sample) == -1
  }

  private fun choose(sample: String): String {
    for (n in preferred) if (shows(n, sample)) return n
    val all = try {
      GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toList()
    } catch (_: Throwable) {
      emptyList()
    }
    return all.firstOrNull { shows(it, sample) } ?: Font.DIALOG
  }

  /** A font of [px] pixels; [bold] for emphasis. */
  fun font(px: Float, bold: Boolean = false): Font =
    Font(family(), if (bold) Font.BOLD else Font.PLAIN, 12).deriveFont(px)

  /** True when the chosen font can show Hangul. */
  fun canShowHangul(): Boolean = font(12f).canDisplayUpTo(HANGUL_SAMPLE) == -1
}

/** Where a name plate's board goes in scene pixels and how wide it is once stretched to its label. */
data class PlateGeo(val boardX: Int, val boardY: Int, val width: Int, val boardHeight: Int)

/** A tag as drawn: its rectangle in screen pixels and the text it shows; [subagentId] names the subordinate it labels, if any. */
data class TagDraw(val tabId: String, val rect: Rect, val text: String, val state: VizState, val subagentId: String? = null)

/**
 * What a paint put on screen, for hit-testing and the probe: the people at their animated positions and the tags; the subordinates
 * (helpers) and their name tags (a helper tag's [TagDraw.tabId] is its parent's, a "+N" tag included); the activity labels above
 * every person and subordinate.
 */
class PaintResult(
  val people: List<PlacedPerson>,
  val tags: List<TagDraw>,
  val helpers: List<PlacedHelper> = emptyList(),
  val helperTags: List<TagDraw> = emptyList(),
  val activities: List<TagDraw> = emptyList(),
)

/** A person as drawn: [screen] is the sprite rectangle in screen pixels at the current animation phase. */
class PlacedPerson(val placement: PersonPlacement, val personFrame: String, val bubbleFrame: String, val screen: Rect)

/** A helper as drawn: [frame] is its (unshrunk) person frame in this phase, [screen] its shrunk sprite in screen pixels. */
class PlacedHelper(val placement: HelperPlacement, val frame: String, val screen: Rect)

/** The rectangles of a floor picker with [count] cards in a column [width] wide. */
object PickerLayout {
  const val HEADER = 34
  const val CARD_H = 50
  const val GAP = 8
  const val MARGIN = 12
  fun card(index: Int, width: Int) = Rect(MARGIN, HEADER + index * (CARD_H + GAP), max(0, width - 2 * MARGIN), CARD_H)
  fun height(count: Int) = HEADER + count * (CARD_H + GAP)
}

/** The previous/next buttons of the page control, at the bottom centre of a canvas [w] x [h]. */
object PagerLayout {
  const val BTN = 28
  fun prev(w: Int, h: Int) = Rect(w / 2 - 60, h - 44, BTN, BTN)
  fun next(w: Int, h: Int) = Rect(w / 2 + 32, h - 44, BTN, BTN)
}

/**
 * Draws the office. The room is composed at native resolution into a BufferedImage from the [Scene]'s draw list (atlas frames,
 * the slab edge from the palette, rugs as atlas frames, 3-slice name plates), scaled by an integer zoom with nearest-neighbour interpolation,
 * and only then all text (plate labels, session tags, floor picker, legend) is drawn at screen resolution. Knows no frame name
 * or color of any theme: those come from the [theme] and its atlas.
 */
class OfficeRenderer(val theme: Theme, val atlas: Atlas = Atlas.of(theme)) {
  private val palette get() = theme.palette
  private val scratch = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
  private val frameImages = HashMap<String, BufferedImage>()
  private val scaledImages = HashMap<Pair<String, Int>, BufferedImage>()

  private fun frame(name: String) = frameImages.getOrPut(name) { atlas.sub(name) }

  /** [name] shrunk by [scale], sampled as [Atlas.scaledPixel] does, so hit-testing sees the pixels drawn. */
  private fun scaledFrame(name: String, scale: Int): BufferedImage = scaledImages.getOrPut(name to scale) {
    val f = atlas.scaled(name, scale)
    val img = BufferedImage(f.w, f.h, BufferedImage.TYPE_INT_ARGB)
    for (y in 0 until f.h) for (x in 0 until f.w) img.setRGB(x, y, atlas.scaledPixel(name, scale, x, y))
    img
  }

  // ---- text

  private fun textHints(g: Graphics2D) {
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
  }

  fun textWidth(text: String, font: Font): Int {
    val g = scratch.createGraphics()
    try {
      textHints(g)
      return ceil(g.getFontMetrics(font).stringWidth(text).toDouble()).toInt()
    } finally {
      g.dispose()
    }
  }

  private fun plateFont(zoom: Int) = Fonts.font(((zoom * 4 + 1).coerceIn(9, 14)).toFloat())
  private fun tagFont() = Fonts.font(11f)
  private fun helperTagFont() = Fonts.font(10f)
  private fun activityFont() = Fonts.font(11.5f, bold = true)

  fun ellipsize(s: String, max: Int) = if (s.length <= max) s else s.take(max - 1) + "\u2026"

  // ---- the scene

  fun plateGeo(item: DrawItem, zoom: Int): PlateGeo {
    val f = atlas[item.frame!!]
    val s = theme.nameplateSlice
    val textW = textWidth(ellipsize(item.label ?: "", 32), plateFont(zoom))
    val wn = max(f.w, ceil((textW + 6.0 * zoom) / zoom).toInt())
    return PlateGeo(item.x - wn / 2, item.y - f.anchorY, wn, s.boardHeight)
  }

  /** Person frames are this many times denser than the scene: see [Theme.personDetail]. */
  val detail: Int get() = theme.personDetail.coerceAtLeast(1)

  /**
   * How many image pixels per scene pixel the room is composed at for [zoom], before it is scaled up by whole pixels to the zoom:
   * 1 when the people are as dense as the scene or at zoom 1 (people shrunk by [detail]); [detail] at a multiple of it (people
   * 1:1); else the zoom itself (the room at screen resolution, each person pixel [zoom] / [detail] screen pixels wide, picked by
   * nearest neighbour, so 1 or 2 alternately at zoom 3: uneven but never blurred).
   */
  fun composeScale(zoom: Int): Int = when {
    detail == 1 || zoom <= 1 -> 1
    zoom % detail == 0 -> detail
    else -> zoom
  }

  /** The room at native resolution ([Scene.width] x [Scene.height]) in animation [phase]; [zoom] only sizes the name plates to their text. */
  fun nativeImage(scene: Scene, phase: Int, zoom: Int): BufferedImage = composeImage(scene, phase, zoom, 1)

  /**
   * The room composed at [k] image pixels per scene pixel (see [composeScale]): every frame scaled up by [k] by pixel replication,
   * except person frames, which are [detail] x denser already and so are drawn at [k] / [detail] (1:1 at [k] = [detail]).
   */
  fun composeImage(scene: Scene, phase: Int, zoom: Int, k: Int): BufferedImage {
    val img = BufferedImage(scene.width * k, scene.height * k, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    try {
      g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
      g.scale(k.toDouble(), k.toDouble())
      val byTab = scene.people.associateBy { it.tabId }
      val helpers = scene.helpers.associateBy { it.key }
      for (it in scene.items) when (it.kind) {
        ItemKind.FLOOR_BASE -> slab(g)
        ItemKind.SPRITE, ItemKind.RUG -> sprite(g, it.frame!!, it.x, it.y, it.scale)
        ItemKind.HELPER -> helpers[it.tabId]?.let { h ->
          val (frameName, off) = helperAt(h, phase)
          personSprite(g, frameName, it.x + off.dx, it.y + off.dy, h.scale, k)
        }
        ItemKind.NAMEPLATE -> plate(g, it, zoom)
        ItemKind.PERSON -> byTab[it.tabId]?.let { p ->
          val (frameName, off) = personAt(p, phase)
          personSprite(g, frameName, it.x + off.dx, it.y + off.dy, 1, k)
        }
      }
      // the theme's lighting goes over the room and under the bubbles, which stay readable
      theme.lighting?.let { LightOverlay.apply(img, LightOverlay.lights(scene, it), it, k) }
      for (p in scene.people) {
        val (name, off) = bubbleAt(p, phase)
        val b = p.bubbleBounds
        g.drawImage(frame(name), b.x + off.dx, b.y + off.dy, null)
      }
    } finally {
      g.dispose()
    }
    return img
  }

  private fun personAt(p: PersonPlacement, phase: Int) =
    theme.personFrame(p.person.seed % theme.personVariants, p.state, phase, p.facing) to theme.personOffset(p.state, phase)

  private fun bubbleAt(p: PersonPlacement, phase: Int) = theme.bubbleFrame(p.state, phase) to theme.bubbleOffset(p.state, phase)

  /** A helper animates as a working person of its look. */
  fun helperAt(h: HelperPlacement, phase: Int) =
    theme.personFrame(h.variant, VizState.WORKING, phase) to theme.personOffset(VizState.WORKING, phase)

  /**
   * A person frame with its anchor at scene pixel ([x], [y]), shrunk by [scale] (a subordinate's), into an image composed at [k]
   * pixels per scene pixel whose graphics [g] is scaled by [k]: drawn 1:1 in image pixels when [detail] x [scale] equals [k].
   */
  private fun personSprite(g: Graphics2D, name: String, x: Int, y: Int, scale: Int, k: Int) {
    val ds = scale * detail
    if (k == 1) return sprite(g, name, x, y, ds)
    val old = g.transform
    g.scale(1.0 / k, 1.0 / k)
    try {
      if (ds % k == 0) {
        val f = atlas.scaled(name, ds / k)
        g.drawImage(scaledFrame(name, ds / k), x * k - f.anchorX, y * k - f.anchorY, null)
      } else {
        // k / ds image pixels per frame pixel: whole when k is a multiple of ds, else nearest neighbour
        val f = atlas[name]
        g.drawImage(frame(name), x * k - f.anchorX * k / ds, y * k - f.anchorY * k / ds, f.w * k / ds, f.h * k / ds, null)
      }
    } finally {
      g.transform = old
    }
  }

  private fun sprite(g: Graphics2D, name: String, x: Int, y: Int, scale: Int = 1) {
    if (scale > 1) {
      val f = atlas.scaled(name, scale)
      g.drawImage(scaledFrame(name, scale), x - f.anchorX, y - f.anchorY, null)
      return
    }
    val f = atlas[name]
    g.drawImage(frame(name), x - f.anchorX, y - f.anchorY, null)
  }

  /** The slab edge: the room's floor extended by 3 units on every side and 7 down, left face in the palette color, right face shaded. */
  private fun slab(g: Graphics2D) {
    val n = OfficeLayout.ROOM
    fun px(u: Int, v: Int) = OfficeLayout.proj(u, v)
    fun py(u: Int, v: Int, z: Int = 0) = OfficeLayout.projY(u, v, z)
    val a = -3
    val b = n + 3
    val drop = 7
    fun poly(c: Color, vararg pts: Pair<Int, Int>) {
      g.color = c
      g.fillPolygon(pts.map { it.first }.toIntArray(), pts.map { it.second }.toIntArray(), pts.size)
    }
    poly(palette.baseSide, px(a, a) to py(a, a), px(b, a) to py(b, a), px(b, b) to py(b, b), px(a, b) to py(a, b))
    poly(palette.baseSide, px(a, b) to py(a, b), px(b, b) to py(b, b), px(b, b) to py(b, b, -drop), px(a, b) to py(a, b, -drop))
    poly(palette.baseSideShade, px(b, b) to py(b, b), px(b, a) to py(b, a), px(b, a) to py(b, a, -drop), px(b, b) to py(b, b, -drop))
  }

  /** The 3-slice name plate: the caps stay, the middle stretches to [PlateGeo.width], the legs are copied centred. */
  private fun plate(g: Graphics2D, it: DrawItem, zoom: Int) {
    val f = atlas[it.frame!!]
    val s = theme.nameplateSlice
    val geo = plateGeo(it, zoom)
    val img = frame(it.frame)
    val bh = s.boardHeight
    val midNew = geo.width - s.leftInset - s.rightInset
    g.drawImage(img.getSubimage(0, 0, s.leftInset, bh), geo.boardX, geo.boardY, null)
    val midSrc = f.w - s.leftInset - s.rightInset
    if (midNew > 0 && midSrc > 0) {
      g.drawImage(img.getSubimage(s.leftInset + midSrc / 2, 0, 1, bh), geo.boardX + s.leftInset, geo.boardY, midNew, bh, null)
    }
    g.drawImage(img.getSubimage(f.w - s.rightInset, 0, s.rightInset, bh), geo.boardX + geo.width - s.rightInset, geo.boardY, null)
    if (f.h > bh) g.drawImage(img.getSubimage(0, bh, f.w, f.h - bh), geo.boardX + (geo.width - f.w) / 2, geo.boardY + bh, null)
  }

  // ---- painting at screen resolution

  /** The whole canvas content: background dots, the scaled room at ([ox], [oy]), then every text at screen resolution. */
  fun paintScene(g: Graphics2D, scene: Scene, zoom: Int, ox: Int, oy: Int, phase: Int): PaintResult {
    val native = composeImage(scene, phase, zoom, composeScale(zoom))
    val old = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION)
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
    g.drawImage(native, ox, oy, scene.width * zoom, scene.height * zoom, null)
    if (old != null) g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, old)
    textHints(g)
    // plate labels, centred on the board
    val pf = plateFont(zoom)
    for (it in scene.items) if (it.kind == ItemKind.NAMEPLATE) {
      val geo = plateGeo(it, zoom)
      val text = ellipsize(it.label ?: "", 32)
      g.font = pf
      g.color = palette.plateText
      val fm = g.fontMetrics
      val cx = ox + (geo.boardX + geo.width / 2.0) * zoom
      val cy = oy + (geo.boardY + geo.boardHeight / 2.0) * zoom
      g.drawString(text, (cx - fm.stringWidth(text) / 2.0).toFloat(), (cy + (fm.ascent - fm.descent) / 2.0).toFloat())
    }
    val placed = scene.people.map { p ->
      val (pn, po) = personAt(p, phase)
      val (bn, bo) = bubbleAt(p, phase)
      val r = p.bounds
      PlacedPerson(p, pn, bn, Rect(ox + (r.x + po.dx) * zoom, oy + (r.y + po.dy) * zoom, r.w * zoom, r.h * zoom))
    }
    val helpers = placedHelpers(scene, zoom, ox, oy, phase)
    val tags = drawTags(g, scene, zoom, ox, oy)
    return PaintResult(placed, tags.sessions, helpers, tags.helpers, tags.activities)
  }

  /** The helpers of [scene] at their animated positions in screen pixels. */
  fun placedHelpers(scene: Scene, zoom: Int, ox: Int, oy: Int, phase: Int): List<PlacedHelper> = scene.helpers.map { h ->
    val (fn, o) = helperAt(h, phase)
    val f = atlas.scaled(fn, h.scale * detail)
    PlacedHelper(h, fn, Rect(ox + (h.x + o.dx - f.anchorX) * zoom, oy + (h.y + o.dy - f.anchorY) * zoom, f.w * zoom, f.h * zoom))
  }

  /** Name plates and state bubbles in screen pixels: tags keep clear of them. */
  fun tagObstacles(scene: Scene, zoom: Int, ox: Int, oy: Int): List<TagRect> {
    val out = ArrayList<TagRect>()
    for (it in scene.items) if (it.kind == ItemKind.NAMEPLATE) {
      val geo = plateGeo(it, zoom)
      val f = atlas[it.frame!!]
      out += TagRect("plate", ox + geo.boardX * zoom, oy + geo.boardY * zoom, geo.width * zoom, f.h * zoom)
    }
    for (p in scene.people) {
      val b = p.bubbleBounds
      out += TagRect("bubble", ox + b.x * zoom, oy + (b.y - 2) * zoom, b.w * zoom, (b.h + 2) * zoom)
    }
    return out
  }

  private enum class TagKind { SESSION, HELPER, ACTIVITY }

  /**
   * One tag to draw: [state] gives the dot (or, for an activity label, the fill) color; [subagentId] names a subordinate's tag, whose
   * activity label starts with its [name] in the first [nameWidth] pixels.
   */
  private class TagSpec(
    val parent: PersonPlacement, val text: String, val kind: TagKind, val state: VizState, val subagentId: String? = null,
    val name: String? = null, val nameWidth: Int = 0,
  )

  private class Tags(val sessions: List<TagDraw>, val helpers: List<TagDraw>, val activities: List<TagDraw>)

  /** The text of an activity label: what the hooks reported, else what the state says. */
  fun activityText(activity: String?, state: VizState): String = ellipsize(activity?.takeIf { it.isNotBlank() } ?: DEFAULT_ACTIVITY.getValue(state), 28)

  /**
   * Every tag in one layout pass so none covers another: below each person its session tag ("\ud300\uc7a5 \u00b7 " first when it leads
   * subordinates); above each head (over the bubble) an activity label, a subordinate's led by its name; a "+N" beside the last
   * subordinate drawn.
   */
  private fun drawTags(g: Graphics2D, scene: Scene, zoom: Int, ox: Int, oy: Int): Tags {
    val big = g.getFontMetrics(tagFont())
    val small = g.getFontMetrics(helperTagFont())
    val act = g.getFontMetrics(activityFont())
    val dot = 7
    val smallDot = 5
    val specs = HashMap<String, TagSpec>()
    val requests = ArrayList<TagRequest>()
    fun activity(id: String, parent: PersonPlacement, text: String, state: VizState, x: Int, headY: Int, name: String? = null, subagentId: String? = null) {
      val nameW = name?.let { small.stringWidth(it) + 12 } ?: 0
      val w = nameW + act.stringWidth(text) + 14
      val h = act.height + 4
      requests += TagRequest(id, ox + x * zoom - w / 2, oy + headY * zoom - h - ACTIVITY_DY, w, h, up = true)
      specs[id] = TagSpec(parent, text, TagKind.ACTIVITY, state, subagentId, name, nameW)
    }
    for (p in scene.people) {
      val s = p.person.session
      val lead = if (p.person.helperCount > 0) "\ud300\uc7a5 \u00b7 " else ""
      val text = ellipsize("$lead${s.agent.name.lowercase()} \u00b7 ${s.title}", 34)
      val w = big.stringWidth(text) + dot + 16
      requests += TagRequest(p.tabId, ox + p.x * zoom - w / 2, oy + p.y * zoom + p.tagDy, w, big.height + 4)
      specs[p.tabId] = TagSpec(p, text, TagKind.SESSION, p.state)
      // above the bubble, which bobs up to 2 pixels
      activity("${p.tabId}/act", p, activityText(s.activity, p.state), p.state, p.x + theme.bubbleAnchorDx(p.facing) - theme.bubbleAnchorDx, p.bubbleBounds.y - 2)
    }
    val lastHelper = HashMap<String, HelperPlacement>()
    for (h in scene.helpers) {
      // one label above the head, its name and what it does side by side, so the two never drift apart
      val name = ellipsize(h.helper.name, 18)
      activity(h.key, h.parent, activityText(h.helper.activity, VizState.WORKING), VizState.WORKING, h.x, h.bounds.y - 1, name, h.helper.id)
      lastHelper[h.tabId] = h
    }
    val byTab = scene.people.associateBy { it.tabId }
    for ((tab, n) in scene.helperOverflow) {
      val p = byTab[tab] ?: continue
      val text = "+$n"
      val w = small.stringWidth(text) + smallDot + 10
      // next to the last subordinate drawn, else beside the person
      val (ax, ay) = lastHelper[tab]?.let { it.bounds.x + it.bounds.w + 2 to it.bounds.y + it.bounds.h / 2 } ?: (p.x + 8 to p.y)
      requests += TagRequest("$tab/+", ox + ax * zoom, oy + ay * zoom - small.height - 2 - HELPER_TAG_DY, w, small.height + 2, up = true)
      specs["$tab/+"] = TagSpec(p, text, TagKind.HELPER, p.state)
    }
    // no tag hides a subordinate; an activity label may stray over another head rather than climb away from its own
    val helperRects = scene.helpers.map { h -> h.bounds.let { TagRect("helper", ox + it.x * zoom, oy + (it.y - 1) * zoom, it.w * zoom, (it.h + 1) * zoom) } }
    val rects = TagLayout.layout(requests, obstacles = tagObstacles(scene, zoom, ox, oy) + helperRects)
    // a label pushed up by others keeps a thin line down to the head it belongs to
    val wanted = requests.associateBy { it.id }
    for (r in rects) {
      val spec = specs.getValue(r.id)
      val want = wanted.getValue(r.id)
      if (spec.kind != TagKind.ACTIVITY || r.y == want.y) continue
      g.color = palette.stateColor(spec.state)
      val cx = r.x + spec.nameWidth + (r.w - spec.nameWidth) / 2
      g.fillRect(cx - 1, r.y + r.h, 2, want.y + want.h + ACTIVITY_DY - r.y - r.h)
    }
    val out = ArrayList<TagDraw>()
    val helperOut = ArrayList<TagDraw>()
    val activityOut = ArrayList<TagDraw>()
    for (r in rects) {
      val spec = specs.getValue(r.id)
      val draw = TagDraw(spec.parent.tabId, Rect(r.x, r.y, r.w, r.h), spec.text, spec.state, spec.subagentId)
      when (spec.kind) {
        TagKind.ACTIVITY -> {
          activityLabel(g, act, small, r, spec)
          val n = spec.nameWidth
          activityOut += draw.copy(rect = Rect(r.x + n, r.y, r.w - n, r.h))
          if (spec.name != null) helperOut += TagDraw(spec.parent.tabId, Rect(r.x, r.y, n, r.h), spec.name, spec.state, spec.subagentId)
          continue
        }
        TagKind.HELPER -> helperOut += draw
        TagKind.SESSION -> out += draw
      }
      val isSmall = spec.kind == TagKind.HELPER
      val d = if (isSmall) smallDot else dot
      val fm = if (isSmall) small else big
      val inset = if (isSmall) 4 else 6
      g.font = fm.font
      g.color = palette.tagBackground
      g.fillRoundRect(r.x, r.y, r.w, r.h, r.h, r.h)
      g.color = palette.stateColor(spec.state)
      g.fillOval(r.x + inset, r.y + (r.h - d) / 2, d, d)
      g.color = palette.tagText
      g.drawString(spec.text, (r.x + inset + d + if (isSmall) 3 else 4).toFloat(), (r.y + (r.h + fm.ascent - fm.descent) / 2).toFloat())
    }
    return Tags(out, helperOut, activityOut)
  }

  /**
   * A pill in the state's color with a dark rim, its text black or white, whichever reads better on that color; a subordinate's
   * name goes first on the dark rim color, in the tag text color.
   */
  private fun activityLabel(g: Graphics2D, fm: java.awt.FontMetrics, nameFm: java.awt.FontMetrics, r: TagRect, spec: TagSpec) {
    val fill = palette.stateColor(spec.state)
    g.color = palette.tagBackground
    g.fillRoundRect(r.x - 1, r.y - 1, r.w + 2, r.h + 2, r.h + 2, r.h + 2)
    val n = spec.nameWidth
    if (spec.name != null) {
      g.color = palette.tagText
      g.font = nameFm.font
      g.drawString(spec.name, (r.x + 7).toFloat(), (r.y + (r.h + nameFm.ascent - nameFm.descent) / 2).toFloat())
    }
    g.color = fill
    g.fillRoundRect(r.x + n, r.y, r.w - n, r.h, r.h, r.h)
    val luma = 0.299 * fill.red + 0.587 * fill.green + 0.114 * fill.blue
    g.color = if (luma > 150) Color(20, 20, 24) else Color.WHITE
    g.font = fm.font
    g.drawString(spec.text, (r.x + n + (r.w - n - fm.stringWidth(spec.text)) / 2).toFloat(), (r.y + (r.h + fm.ascent - fm.descent) / 2).toFloat())
  }

  /** The canvas background: a flat color with a dot grid. */
  fun paintBackground(g: Graphics2D, w: Int, h: Int) {
    g.color = palette.canvasBackground
    g.fillRect(0, 0, w, h)
    g.color = palette.canvasDot
    var y = 12
    while (y < h) {
      var x = 12
      while (x < w) {
        g.fillRect(x, y, 2, 2)
        x += 24
      }
      y += 24
    }
  }

  /** "1F colo-backend · 6 sessions · 3 worktrees" at ([x], [y]) (baseline). */
  fun drawHeader(g: Graphics2D, floor: Floor?, x: Int, y: Int) {
    textHints(g)
    g.font = Fonts.font(13f, bold = true)
    g.color = palette.text
    val title = if (floor == null) "AI Office" else "${floor.label}  ${floor.name}"
    g.drawString(title, x.toFloat(), y.toFloat())
    if (floor != null) {
      val w = g.fontMetrics.stringWidth(title)
      g.font = Fonts.font(12f)
      g.color = palette.dim
      g.drawString("\u00b7  ${floor.sessionCount} sessions  \u00b7  ${floor.worktreeCount} worktrees", (x + w + 14).toFloat(), y.toFloat())
    }
  }

  fun drawPager(g: Graphics2D, w: Int, h: Int, page: Int, pages: Int) {
    textHints(g)
    g.font = Fonts.font(13f, bold = true)
    for ((r, label) in listOf(PagerLayout.prev(w, h) to "\u25c0", PagerLayout.next(w, h) to "\u25b6")) {
      g.color = palette.card
      g.fillRoundRect(r.x, r.y, r.w, r.h, 8, 8)
      g.color = palette.text
      val fm = g.fontMetrics
      g.drawString(label, (r.x + (r.w - fm.stringWidth(label)) / 2).toFloat(), (r.y + (r.h + fm.ascent - fm.descent) / 2).toFloat())
    }
    val label = "${page + 1} / $pages"
    val fm = g.fontMetrics
    g.color = palette.text
    g.drawString(label, (w / 2 - fm.stringWidth(label) / 2 + 0f), (h - 44 + (PagerLayout.BTN + fm.ascent - fm.descent) / 2).toFloat())
  }

  // ---- floor picker and legend (left column)

  private fun counts(g: Graphics2D, floor: Floor, x: Int, y: Int, fg: Color) {
    g.font = Fonts.font(11f)
    var cx = x
    for (s in VizState.values()) {
      val n = floor.counts[s] ?: 0
      if (n == 0) continue
      g.color = palette.stateColor(s)
      g.fillOval(cx, y - 8, 8, 8)
      g.color = fg
      val t = n.toString()
      g.drawString(t, (cx + 11).toFloat(), y.toFloat())
      cx += 11 + g.fontMetrics.stringWidth(t) + 8
    }
  }

  /** Draws the floors top-down as cards: "nF" badge, project name, per-state counts. */
  fun drawFloorPicker(g: Graphics2D, floorsTopDown: List<Floor>, selectedKey: String?, width: Int, hoverKey: String?): Map<String, Rect> {
    textHints(g)
    g.font = Fonts.font(11f, bold = true)
    g.color = palette.dim
    g.drawString("FLOORS \u00b7 \ud504\ub85c\uc81d\ud2b8", PickerLayout.MARGIN.toFloat() + 6, 22f)
    val rects = LinkedHashMap<String, Rect>()
    floorsTopDown.forEachIndexed { i, f ->
      val r = PickerLayout.card(i, width)
      rects[f.key] = r
      val sel = f.key == selectedKey
      g.color = if (sel) palette.accent else if (f.key == hoverKey) palette.line else palette.card
      g.fillRoundRect(r.x, r.y, r.w, r.h, 12, 12)
      val fg = if (sel) palette.accentText else palette.text
      g.color = if (sel) Color(255, 255, 255, 70) else palette.surface
      g.fillRoundRect(r.x + 8, r.y + 9, 32, 32, 8, 8)
      g.font = Fonts.font(13f, bold = true)
      g.color = fg
      val fm = g.fontMetrics
      g.drawString(f.label, (r.x + 8 + (32 - fm.stringWidth(f.label)) / 2).toFloat(), (r.y + 9 + (32 + fm.ascent - fm.descent) / 2).toFloat())
      g.font = Fonts.font(13f, bold = true)
      g.drawString(ellipsizeToWidth(g, f.name, r.w - 56), (r.x + 48).toFloat(), (r.y + 22).toFloat())
      counts(g, f, r.x + 48, r.y + 39, if (sel) palette.accentText else palette.dim)
    }
    return rects
  }

  private fun ellipsizeToWidth(g: Graphics2D, s: String, w: Int): String {
    if (g.fontMetrics.stringWidth(s) <= w) return s
    var t = s
    while (t.length > 1 && g.fontMetrics.stringWidth("$t\u2026") > w) t = t.dropLast(1)
    return "$t\u2026"
  }

  /** The rectangles of the legend's four rows in a column [width] wide whose rows start at y = [top]. */
  fun legendRow(i: Int, width: Int, top: Int) = Rect(PickerLayout.MARGIN, top + 34 + i * 52, max(0, width - 2 * PickerLayout.MARGIN), 46)

  fun legendHeight() = 34 + 4 * 52 + 8

  /** The four states: bubble icon at 2x, then a name and a short English note. */
  fun drawLegend(g: Graphics2D, width: Int, top: Int) {
    textHints(g)
    g.font = Fonts.font(11f, bold = true)
    g.color = palette.dim
    g.drawString("STATES \u00b7 \uc138\uc158 \uc0c1\ud0dc", (PickerLayout.MARGIN + 6).toFloat(), (top + 22).toFloat())
    val labels = mapOf(
      VizState.WORKING to ("\uc791\uc5c5 \uc911" to "working"),
      VizState.WAITING to ("\uc751\ub2f5 \ud544\uc694" to "waiting for you"),
      VizState.DONE to ("\uc644\ub8cc" to "done"),
      VizState.IDLE to ("\ub300\uae30 \u00b7 \uc7a0\ub4e6" to "idle"),
    )
    VizState.values().forEachIndexed { i, s ->
      val r = legendRow(i, width, top)
      g.color = palette.card
      g.fillRoundRect(r.x, r.y, r.w, r.h, 12, 12)
      val name = theme.bubble(s)
      val f = atlas[name]
      val old = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION)
      g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
      g.drawImage(frame(name), r.x + 8, r.y + (r.h - f.h * 2) / 2, f.w * 2, f.h * 2, null)
      if (old != null) g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, old)
      val (title, note) = labels.getValue(s)
      g.font = Fonts.font(13f, bold = true)
      g.color = palette.text
      g.drawString(title, (r.x + 58).toFloat(), (r.y + 20).toFloat())
      g.font = Fonts.font(11f)
      g.color = palette.dim
      g.drawString(note, (r.x + 58).toFloat(), (r.y + 36).toFloat())
    }
  }

  /** A whole canvas as an image (the room centred, header, no interaction state): for tests and probes. */
  fun render(scene: Scene, floor: Floor?, zoom: Int, phase: Int, width: Int = scene.width * zoom, height: Int = scene.height * zoom): BufferedImage {
    val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    try {
      paintBackground(g, width, height)
      paintScene(g, scene, zoom, (width - scene.width * zoom) / 2, (height - scene.height * zoom) / 2, phase)
      drawHeader(g, floor, 18, 26)
      if (scene.pageCount > 1) drawPager(g, width, height, scene.page, scene.pageCount)
    } finally {
      g.dispose()
    }
    return img
  }

  /** Elapsed text for the tooltip, kept here so every text format of the office lives with the renderer. */
  fun tooltip(p: PersonPlacement, now: Long): String {
    val s = p.person.session
    val n = p.person.helperCount
    val helpers = if (n == 0) "" else " \u00b7 $n helper${if (n == 1) "" else "s"}"
    val activity = s.activity?.let { " \u00b7 $it" } ?: ""
    return "${s.title} \u00b7 ${s.agent.name.lowercase()} \u00b7 ${Elapsed.format(s.startedAt, now)}$helpers$activity"
  }

  /** A subordinate's tooltip: the subagent's name, its parent's title and what it does. */
  fun tooltip(h: HelperPlacement): String =
    "${h.helper.name} \u00b7 helper of ${h.parent.person.session.title}" + (h.helper.activity?.let { " \u00b7 $it" } ?: "")

  companion object {
    /** A "+N" tag ends this many screen pixels (not scaled) above where it is anchored. */
    const val HELPER_TAG_DY = 2

    /** An activity label ends this many screen pixels (not scaled) above the bubble (or a subordinate's head). */
    const val ACTIVITY_DY = 3

    /** What an activity label says when the hooks reported nothing. */
    val DEFAULT_ACTIVITY = mapOf(
      VizState.WORKING to "작업 중",
      VizState.WAITING to "응답 필요",
      VizState.DONE to "완료",
      VizState.IDLE to "대기",
    )
  }
}
