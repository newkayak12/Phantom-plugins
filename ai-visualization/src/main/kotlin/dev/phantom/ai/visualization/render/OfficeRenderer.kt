// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.visualization.render

import dev.phantom.ai.visualization.model.DrawItem
import dev.phantom.ai.visualization.model.Elapsed
import dev.phantom.ai.visualization.model.Floor
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

/** A tag as drawn: its rectangle in screen pixels and the text it shows. */
data class TagDraw(val tabId: String, val rect: Rect, val text: String, val state: VizState)

/** What a paint put on screen, for hit-testing and the probe: the people at their animated positions and the tags. */
class PaintResult(val people: List<PlacedPerson>, val tags: List<TagDraw>)

/** A person as drawn: [screen] is the sprite rectangle in screen pixels at the current animation phase. */
class PlacedPerson(val placement: PersonPlacement, val personFrame: String, val bubbleFrame: String, val screen: Rect)

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

  private fun frame(name: String) = frameImages.getOrPut(name) { atlas.sub(name) }

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

  fun ellipsize(s: String, max: Int) = if (s.length <= max) s else s.take(max - 1) + "\u2026"

  // ---- the scene

  fun plateGeo(item: DrawItem, zoom: Int): PlateGeo {
    val f = atlas[item.frame!!]
    val s = theme.nameplateSlice
    val textW = textWidth(ellipsize(item.label ?: "", 32), plateFont(zoom))
    val wn = max(f.w, ceil((textW + 6.0 * zoom) / zoom).toInt())
    return PlateGeo(item.x - wn / 2, item.y - f.anchorY, wn, s.boardHeight)
  }

  /** The room at native resolution ([Scene.width] x [Scene.height]) in animation [phase]; [zoom] only sizes the name plates to their text. */
  fun nativeImage(scene: Scene, phase: Int, zoom: Int): BufferedImage {
    val img = BufferedImage(scene.width, scene.height, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    try {
      g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
      val byTab = scene.people.associateBy { it.tabId }
      for (it in scene.items) when (it.kind) {
        ItemKind.FLOOR_BASE -> slab(g)
        ItemKind.SPRITE, ItemKind.RUG -> sprite(g, it.frame!!, it.x, it.y)
        ItemKind.NAMEPLATE -> plate(g, it, zoom)
        ItemKind.PERSON -> byTab[it.tabId]?.let { p ->
          val (frameName, off) = personAt(p, phase)
          sprite(g, frameName, it.x + off.dx, it.y + off.dy)
        }
      }
      // the theme's lighting goes over the room and under the bubbles, which stay readable
      theme.lighting?.let { LightOverlay.apply(img, LightOverlay.lights(scene, it), it) }
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
    theme.personFrame(p.person.seed % theme.personVariants, p.state, phase) to theme.personOffset(p.state, phase)

  private fun bubbleAt(p: PersonPlacement, phase: Int) = theme.bubbleFrame(p.state, phase) to theme.bubbleOffset(p.state, phase)

  private fun sprite(g: Graphics2D, name: String, x: Int, y: Int) {
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
    val native = nativeImage(scene, phase, zoom)
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
    return PaintResult(placed, drawTags(g, scene, zoom, ox, oy, phase))
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

  private fun drawTags(g: Graphics2D, scene: Scene, zoom: Int, ox: Int, oy: Int, phase: Int): List<TagDraw> {
    val font = tagFont()
    g.font = font
    val fm = g.fontMetrics
    val h = fm.height + 4
    val dot = 7
    val requests = scene.people.map { p ->
      val s = p.person.session
      val text = ellipsize("${s.agent.name.lowercase()} \u00b7 ${s.title}", 30)
      val w = fm.stringWidth(text) + dot + 16
      TagRequest(p.tabId, ox + p.x * zoom - w / 2, oy + p.y * zoom + p.tagDy, w, h) to text
    }
    val texts = requests.associate { it.first.id to it.second }
    val byTab = scene.people.associateBy { it.tabId }
    val rects = TagLayout.layout(requests.map { it.first }, obstacles = tagObstacles(scene, zoom, ox, oy))
    val out = ArrayList<TagDraw>()
    for (r in rects) {
      val p = byTab.getValue(r.id)
      g.color = palette.tagBackground
      g.fillRoundRect(r.x, r.y, r.w, r.h, r.h, r.h)
      g.color = palette.stateColor(p.state)
      g.fillOval(r.x + 6, r.y + (r.h - dot) / 2, dot, dot)
      g.color = palette.tagText
      g.drawString(texts.getValue(r.id), (r.x + 6 + dot + 4).toFloat(), (r.y + (r.h + fm.ascent - fm.descent) / 2).toFloat())
      out += TagDraw(r.id, Rect(r.x, r.y, r.w, r.h), texts.getValue(r.id), p.state)
    }
    return out
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
    return "${s.title} \u00b7 ${s.agent.name.lowercase()} \u00b7 ${Elapsed.format(s.startedAt, now)}"
  }
}
