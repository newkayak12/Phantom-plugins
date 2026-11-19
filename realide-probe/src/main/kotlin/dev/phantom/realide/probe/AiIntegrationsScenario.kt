// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.realide.probe

import com.intellij.openapi.actionSystem.impl.ActionButton
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ToolWindowType
import com.intellij.openapi.wm.ex.ToolWindowEx
import com.intellij.ui.components.JBLabel
import java.awt.Component
import java.awt.Container
import java.awt.Point
import java.awt.Window
import java.awt.image.BufferedImage
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.Base64
import javax.imageio.ImageIO
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JTree
import javax.swing.SwingUtilities

private const val TOOL_WINDOW = "Phantom AI Integration"

/**
 * Phantom AI Integration in a real IDE, through the UI only (no plugin class is imported; components are found by their Swing type,
 * name and text). The sandbox's fixture "alpha" is a git repo with the worktree ../alpha-feature (branch feature) and Remote Control
 * is seeded on 127.0.0.1 with a fixed probe token (sysprops phantom.probe.remote.port / .token, set by the runIdeProbe task).
 * Steps: frame.size, toolwindow.opens, sidebar.worktrees, tree.collapse, terminal.new.claude, layout.docked.horizontal (ai-docked.png),
 * layout.floating (ai-floating.png, ai-floating-narrow.png), board.editor.drag, remote.http, remote.ws.
 */
internal object AiIntegrationsScenario : ProbeScenario {
  override suspend fun run(ctx: ProbeContext) {
    val report = ctx.report
    fun tw(): ToolWindow? = ToolWindowManager.getInstance(ctx.project).getToolWindow(TOOL_WINDOW)
    fun walk(c: Component, out: MutableList<Component> = ArrayList()): List<Component> {
      out += c
      if (c is Container) c.components.forEach { walk(it, out) }
      return out
    }
    fun root(): Component? = tw()?.component
    fun named(n: String): Component? = root()?.let { r -> walk(r).firstOrNull { it.name == n } }
    fun tree(): JTree? = root()?.let { r -> walk(r).filterIsInstance<JTree>().firstOrNull() }
    fun rowsText(): List<String> = tree()?.let { t -> (0 until t.rowCount).map { t.getPathForRow(it).lastPathComponent.toString() } }.orEmpty()
    fun titles(): List<String> = (root()?.let { r -> walk(r).mapNotNull { it.name } } ?: emptyList()).filter { it.startsWith("agent.tab.") && !it.contains("close") && it != "agent.tabs.strip" }
    fun screen(c: Component?): Point? = if (c != null && c.isShowing) c.locationOnScreen else null
    fun headerButton(vararg texts: String): ActionButton? {
      val r = root() ?: return null
      var top: Component = r
      while (top.parent != null) top = top.parent
      return walk(top).filterIsInstance<ActionButton>().firstOrNull { b -> b.isShowing && b.action.templatePresentation.text in texts || b.isShowing && b.presentation.text in texts }
    }
    fun paintWindow(w: Window, name: String): Map<String, Any?> {
      val img = BufferedImage(w.width, w.height, BufferedImage.TYPE_INT_RGB)
      val g = img.createGraphics()
      try { w.paint(g) } finally { g.dispose() }
      ctx.out.toFile().mkdirs()
      val ok = ImageIO.write(img, "png", ctx.out.resolve(name).toFile())
      return mapOf("written" to ok, "png" to name, "width" to w.width, "height" to w.height)
    }
    fun windowOf(): Window? = root()?.let { SwingUtilities.getWindowAncestor(it) }

    // frame: big enough for a docked, side-by-side tool window
    val frameSize = ctx.edt {
      ProbeSnapshot.frame(ctx.project)?.let { f ->
        val screen = f.graphicsConfiguration?.bounds
        val w = minOf(1700, screen?.width ?: 1700)
        val h = minOf(1000, screen?.height ?: 1000)
        f.setSize(w, h)
        f.size.let { it.width to it.height }
      }
    }
    report.step(
      "frame.size",
      expected = mapOf("wideEnough" to true, "tallEnough" to true),
      observed = mapOf("wideEnough" to ((frameSize?.first ?: 0) >= 1200), "tallEnough" to ((frameSize?.second ?: 0) >= 800), "size" to "${frameSize?.first}x${frameSize?.second}"),
    )

    // the tool window opens docked, with the tree, the buttons and the terminal area in one panel
    ctx.edt { tw()?.show() }
    val opened = ctx.waitUntil(30_000) { tw()?.isVisible == true && named("toolwindow.treeColumn") != null && named("btn.claude") != null }
    report.step(
      "toolwindow.opens",
      expected = mapOf("visible" to true, "hasTreeColumn" to true, "hasClaudeButton" to true, "oneContent" to true, "docked" to true),
      observed = ctx.edt {
        mapOf(
          "visible" to (tw()?.isVisible == true), "hasTreeColumn" to (named("toolwindow.treeColumn") != null), "hasClaudeButton" to (named("btn.claude") != null),
          "oneContent" to (tw()?.contentManager?.contentCount == 1), "docked" to (tw()?.type == ToolWindowType.DOCKED), "opened" to opened,
        )
      },
    )

    // the current project's repo and its linked worktree are in the tree
    val haveRows = ctx.waitUntil(60_000) { tree()?.isShowing == true && rowsText().any { it.contains("alpha-feature") } }
    report.step(
      "sidebar.worktrees",
      expected = mapOf("hasMainWorktree" to true, "hasFeatureWorktree" to true, "noGroupUi" to true),
      observed = ctx.edt {
        val rows = rowsText()
        mapOf(
          "hasMainWorktree" to rows.any { it.contains("alpha") && !it.contains("alpha-feature") }, "hasFeatureWorktree" to rows.any { it.contains("alpha-feature") },
          "noGroupUi" to (root()?.let { r -> walk(r).filterIsInstance<JButton>().none { it.text?.contains("Add Repo") == true } } ?: false), "rows" to rows, "loaded" to haveRows,
        )
      },
    )

    // the tool window is made wide enough to be side by side while docked
    ctx.edt {
      val w = tw() as? ToolWindowEx ?: return@edt
      val have = w.component.width
      if (have < 900) w.stretchWidth(900 - have)
    }
    ctx.waitUntil(10_000) { (tw()?.component?.width ?: 0) >= 800 }

    // a tree hidden by an earlier run is shown first
    if (ctx.edt { headerButton("Show Tree") } != null) {
      ctx.edt { headerButton("Show Tree")?.let { ProbeInput.click(it, Point(it.width / 2, it.height / 2)) } }
      ctx.waitUntil(10_000) { named("toolwindow.treeColumn")?.isShowing == true }
    }
    ctx.waitUntil(5_000) { headerButton("Hide Tree") != null }

    // Hide Tree: the tree column is gone and the tabs take the whole width; Show Tree: back
    fun stripWidth(): Int = named("agent.tabs.strip")?.parent?.width ?: -1
    val before = ctx.edt { Triple(named("toolwindow.treeColumn")?.isShowing == true, stripWidth(), root()?.width ?: 0) }
    val hideBtn = ctx.edt { headerButton("Hide Tree") }
    ctx.edt { hideBtn?.let { ProbeInput.click(it, Point(it.width / 2, it.height / 2)) } }
    val hidden = ctx.waitUntil(10_000) { named("toolwindow.treeColumn")?.isShowing == false }
    val duringHidden = ctx.edt { Triple(named("toolwindow.treeColumn")?.isShowing == true, stripWidth(), root()?.width ?: 0) }
    ctx.waitUntil(5_000) { headerButton("Show Tree") != null }
    val showBtn = ctx.edt { headerButton("Show Tree") }
    ctx.edt { showBtn?.let { ProbeInput.click(it, Point(it.width / 2, it.height / 2)) } }
    val shown = ctx.waitUntil(10_000) { named("toolwindow.treeColumn")?.isShowing == true }
    val after = ctx.edt { Triple(named("toolwindow.treeColumn")?.isShowing == true, stripWidth(), root()?.width ?: 0) }
    report.step(
      "tree.collapse",
      expected = mapOf("hideButton" to true, "treeHidden" to true, "tabsFullWidth" to true, "showButton" to true, "treeRestored" to true, "tabsNarrowerAgain" to true, "via" to ProbeInput.VIA_QUEUE),
      observed = mapOf(
        "hideButton" to (hideBtn != null), "treeHidden" to hidden, "tabsFullWidth" to (duringHidden.second >= duringHidden.third * 9 / 10 && duringHidden.third > 0),
        "showButton" to (showBtn != null), "treeRestored" to shown, "tabsNarrowerAgain" to (after.second in 1 until after.third * 9 / 10), "via" to ProbeInput.VIA_QUEUE,
        "before" to before.toString(), "hiddenState" to duringHidden.toString(), "after" to after.toString(),
      ),
    )

    // + Claude: the stub CLI starts in the selected worktree's terminal tab; its tab shows in the same panel
    val callsLog = System.getProperty("phantom.ai.agentBinDir")?.let { File(it, "calls.log") }
    val tabsBefore = ctx.edt { titles().size }
    val rowIdx = ctx.edt { tree()?.let { t -> (0 until t.rowCount).firstOrNull { t.getPathForRow(it).lastPathComponent.toString().contains("alpha-feature") } } }
    ctx.edt {
      val t = tree()
      if (t != null && rowIdx != null) {
        val r = t.getRowBounds(rowIdx)
        ProbeInput.click(t, Point(r.x + r.width / 2, r.y + r.height / 2))
      }
    }
    ctx.waitUntil(5_000) { tree()?.selectionCount == 1 }
    val claudeBtn = ctx.edt { named("btn.claude") as? JButton }
    ctx.edt { claudeBtn?.let { ProbeInput.click(it, Point(it.width / 2, it.height / 2)) } }
    val tab = ctx.waitUntil(30_000) { titles().size > tabsBefore }
    val stubRan = callsLog != null && ctx.waitUntilAny(30_000) { callsLog.isFile && callsLog.readLines().any { it.contains("/bin/claude") } }
    val sandboxEnv = callsLog?.takeIf { it.isFile }?.readLines()?.any { l -> l.contains("CLAUDE_CONFIG_DIR=") && l.contains("realide-agent-homes/claude") } == true
    report.step(
      "terminal.new.claude",
      expected = mapOf("worktreeRowSelected" to true, "claudeButton" to true, "terminalTabAppeared" to true, "claudeStubStarted" to true, "sandboxConfigDir" to true, "via" to ProbeInput.VIA_QUEUE),
      observed = ctx.edt {
        mapOf(
          "worktreeRowSelected" to (rowIdx != null), "claudeButton" to (claudeBtn != null), "terminalTabAppeared" to tab, "claudeStubStarted" to stubRan,
          "sandboxConfigDir" to sandboxEnv, "via" to ProbeInput.VIA_QUEUE, "tabs" to titles(),
        )
      },
    )
    ctx.waitUntil(5_000) { false }.let { }

    // docked and wide: tree column left of the tabs, buttons in the tree column
    val docked = ctx.edt {
      val tc = named("toolwindow.treeColumn")
      val strip = named("agent.tabs.strip")
      val tp = screen(tc)
      val sp = screen(strip)
      val header = named("toolwindow.treeHeader") as? Container
      val footer = named("toolwindow.treeFooter") as? Container
      mapOf(
        "type" to tw()?.type.toString(),
        "treeLeftOfTabs" to (tp != null && sp != null && tc != null && tp.x + tc.width <= sp.x + 2),
        "sameRow" to (tp != null && sp != null && kotlin.math.abs(tp.y - sp.y) < (tc?.height ?: 0)),
        "headerHasClaudeCodex" to (header?.components?.mapNotNull { it.name }?.containsAll(listOf("btn.claude", "btn.codex")) == true && header.isShowing),
        "footerHasBoardDashboard" to (footer?.components?.mapNotNull { it.name }?.containsAll(listOf("btn.board", "btn.dashboard")) == true && footer.isShowing),
        "topRowHidden" to (named("toolwindow.topRow")?.isShowing == false),
        "size" to "${root()?.width}x${root()?.height}",
      )
    }
    val dockedShot = ctx.edt { ProbeSnapshot.paint(ctx.project, ctx.out, "ai-docked.png") }
    report.step(
      "layout.docked.horizontal",
      expected = mapOf("type" to "DOCKED", "treeLeftOfTabs" to true, "sameRow" to true, "headerHasClaudeCodex" to true, "footerHasBoardDashboard" to true, "topRowHidden" to true, "png" to true),
      observed = docked + mapOf("png" to (dockedShot["written"] == true)),
    )

    // floating: Float from the title action; the same panel in its own window
    val floatBtn = ctx.edt { headerButton("Float") }
    var floatVia = ProbeInput.VIA_QUEUE
    ctx.edt { floatBtn?.let { ProbeInput.click(it, Point(it.width / 2, it.height / 2)) } }
    var floated = ctx.waitUntil(10_000) { tw()?.type == ToolWindowType.FLOATING }
    if (!floated) {
      floatVia = "toolWindow.setType"
      ctx.edt { tw()?.setType(ToolWindowType.FLOATING, null) }
      floated = ctx.waitUntil(10_000) { tw()?.type == ToolWindowType.FLOATING }
    }
    // a floating window may come up with the bounds the IDE remembered: set ours until they stick
    suspend fun resizeFloat(w: Int, h: Int) {
      // the IDE sizes a window it has just floated once more after showing it: wait that out, then check the size held
      ctx.waitUntil(2_500) { false }
      repeat(6) {
        ctx.waitUntil(5_000) { windowOf()?.let { win -> if (win.width != w || win.height != h) win.setSize(w, h); win.width == w && win.height == h } == true }
        ctx.waitUntil(1_500) { false }
        if (ctx.edt { windowOf()?.let { it.width == w && it.height == h } } == true) return
      }
    }
    resizeFloat(1140, 890)
    ctx.waitUntil(10_000) { (root()?.width ?: 0) > 1000 }
    ctx.waitUntil(3_000) { false }.let { }
    suspend fun floatState() = ctx.edt {
      val top = named("toolwindow.topRow")
      val tc = named("toolwindow.treeColumn")
      val strip = named("agent.tabs.strip")
      val tp = screen(tc)
      val sp = screen(strip)
      val p = root()
      mapOf(
        "type" to tw()?.type.toString(),
        "window" to windowOf()?.size?.let { "${it.width}x${it.height}" },
        "topRowShown" to (top?.isShowing == true),
        "topRowFullWidth" to (top != null && p != null && top.width >= p.width - 4),
        "topRowHasFour" to ((top as? Container)?.components?.mapNotNull { it.name }?.containsAll(listOf("btn.claude", "btn.codex", "btn.board", "btn.dashboard")) == true),
        "treeLeftOfTabs" to (tp != null && sp != null && tc != null && tp.x + tc.width <= sp.x + 2),
        "treeAboveTabs" to (tp != null && sp != null && tc != null && tp.y + tc.height <= sp.y + 2 && kotlin.math.abs(tp.x - sp.x) < 4),
        "panel" to "${p?.width}x${p?.height}",
      )
    }
    val wide = floatState()
    val wideShot = ctx.edt { windowOf()?.let { paintWindow(it, "ai-floating.png") } ?: mapOf("written" to false) }
    resizeFloat(520, 820)
    ctx.waitUntil(10_000) { (root()?.width ?: 9999) < 600 }
    ctx.waitUntil(3_000) { false }.let { }
    val narrow = floatState()
    val narrowShot = ctx.edt { windowOf()?.let { paintWindow(it, "ai-floating-narrow.png") } ?: mapOf("written" to false) }
    // dock and float again: the floating bounds are kept
    val boundsBefore = ctx.edt { windowOf()?.bounds }
    val dockBtn = ctx.edt { headerButton("Dock") }
    ctx.edt { dockBtn?.let { ProbeInput.click(it, Point(it.width / 2, it.height / 2)) } }
    var redocked = ctx.waitUntil(10_000) { tw()?.type == ToolWindowType.DOCKED }
    if (!redocked) {
      ctx.edt { tw()?.setType(ToolWindowType.DOCKED, null) }
      redocked = ctx.waitUntil(10_000) { tw()?.type == ToolWindowType.DOCKED }
    }
    ctx.edt { tw()?.setType(ToolWindowType.FLOATING, null) }
    ctx.waitUntil(10_000) { tw()?.type == ToolWindowType.FLOATING && windowOf() != null }
    ctx.waitUntil(3_000) { false }.let { }
    val boundsAfter = ctx.edt { windowOf()?.bounds }
    val kept = boundsBefore != null && boundsAfter != null && boundsBefore.width == boundsAfter.width && boundsBefore.height == boundsAfter.height
    ctx.edt { tw()?.setType(ToolWindowType.DOCKED, null) }
    val endsDocked = ctx.waitUntil(10_000) { tw()?.type == ToolWindowType.DOCKED }
    report.step(
      "layout.floating",
      expected = mapOf(
        "floated" to true, "wideHorizontal" to true, "wideHeaderFullWidth" to true, "wideHeaderFourButtons" to true, "wideSize" to true,
        "narrowVertical" to true, "narrowHeaderFullWidth" to true, "narrowSize" to true, "boundsKept" to true, "endsDocked" to true, "pngs" to true,
      ),
      observed = mapOf(
        "floated" to floated, "floatVia" to floatVia, "wideHorizontal" to (wide["treeLeftOfTabs"] == true), "wideHeaderFullWidth" to (wide["topRowShown"] == true && wide["topRowFullWidth"] == true),
        "wideHeaderFourButtons" to (wide["topRowHasFour"] == true), "wideSize" to (wide["window"] == "1140x890"),
        "narrowVertical" to (narrow["treeAboveTabs"] == true), "narrowHeaderFullWidth" to (narrow["topRowShown"] == true && narrow["topRowFullWidth"] == true),
        "narrowSize" to (narrow["window"] == "520x820"), "boundsKept" to kept, "endsDocked" to (endsDocked && redocked),
        "pngs" to (wideShot["written"] == true && narrowShot["written"] == true), "wide" to wide.toString(), "narrow" to narrow.toString(),
        "before" to boundsBefore.toString(), "after" to boundsAfter.toString(),
      ),
    )

    // Board button opens the Board editor tab; a card is dragged into another column
    val boardBtn = ctx.edt { named("btn.board") as? JButton }
    ctx.edt { boardBtn?.let { ProbeInput.click(it, Point(it.width / 2, it.height / 2)) } }
    fun editorNames(): List<String> = FileEditorManager.getInstance(ctx.project).openFiles.map { it.name }
    val boardOpen = ctx.waitUntil(30_000) { "Board" in editorNames() }
    fun boardRoot(): Component? = FileEditorManager.getInstance(ctx.project).selectedEditor?.component
    fun cards(): List<JComponent> = boardRoot()?.let { b -> walk(b).filterIsInstance<JComponent>().filter { it.name?.startsWith("card:") == true } }.orEmpty()
    fun columnTitles(): List<String> = boardRoot()?.let { b -> walk(b).filterIsInstance<JBLabel>().map { it.text.orEmpty() }.filter { it.matches(Regex(".+ \\(\\d+\\)")) } }.orEmpty()
    val haveCard = ctx.waitUntil(30_000) { cards().isNotEmpty() && cards().first().isShowing }
    val colsBefore = ctx.edt { columnTitles() }
    val dragged = ctx.edt {
      val card = cards().firstOrNull()
      val b = boardRoot()
      val label = columnTitles().let { t -> b?.let { walk(it).filterIsInstance<JBLabel>().firstOrNull { l -> l.text == t.getOrNull(1) } } }
      val body = label?.parent?.let { p -> p.components.firstOrNull { it !== label } }
      if (card == null || label == null || body == null || !card.isShowing) false
      else {
        val target = SwingUtilities.convertPoint(body, Point(body.width / 2, 10), card)
        ProbeInput.drag(card, Point(card.width / 2, card.height / 2), target)
        true
      }
    }
    val moved = ctx.waitUntil(10_000) { columnTitles().let { it.size >= 2 && it != colsBefore && it[1].endsWith("(1)") } }
    report.step(
      "board.editor.drag",
      expected = mapOf("boardButton" to true, "boardEditorTab" to true, "cardShown" to true, "dragged" to true, "movedToSecondColumn" to true, "via" to ProbeInput.VIA_QUEUE),
      observed = ctx.edt {
        mapOf(
          "boardButton" to (boardBtn != null), "boardEditorTab" to boardOpen, "cardShown" to haveCard, "dragged" to dragged, "movedToSecondColumn" to moved,
          "via" to ProbeInput.VIA_QUEUE, "before" to colsBefore, "after" to columnTitles(), "editors" to editorNames(),
        )
      },
    )

    // Remote Control over loopback: the token gate, /pair, the cookie, /api/state and a terminal's WebSocket
    val port = System.getProperty("phantom.probe.remote.port")?.toIntOrNull() ?: 0
    val token = System.getProperty("phantom.probe.remote.token").orEmpty()
    val base = "http://127.0.0.1:$port"
    class Resp(val code: Int, val body: String, val headers: Map<String?, List<String>>)
    fun http(path: String, headers: Map<String, String> = emptyMap()): Resp {
      val c = URI("$base$path").toURL().openConnection() as HttpURLConnection
      c.instanceFollowRedirects = false
      c.connectTimeout = 5000
      c.readTimeout = 10000
      headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
      val code = c.responseCode
      val body = (if (code < 400) c.inputStream else c.errorStream)?.use { String(it.readBytes()) }.orEmpty()
      return Resp(code, body, c.headerFields)
    }
    // the listener comes up with the project's startup activity
    val listening = ctx.waitUntilAny(60_000) { port > 0 && runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 500) } }.isSuccess }
    val results = LinkedHashMap<String, Any?>()
    var cookie = ""
    var stateBody = ""
    runCatching {
      results["rootNoToken"] = http("/").code
      results["xtermNoToken"] = http("/vendor/xterm/xterm.js").code
      val pair = http("/pair?token=$token")
      results["pairCode"] = pair.code
      val setCookie = pair.headers.entries.firstOrNull { it.key.equals("Set-Cookie", ignoreCase = true) }?.value?.firstOrNull().orEmpty()
      results["pairSetCookie"] = setCookie.replace(token, "<token>")
      results["pairHttpOnly"] = setCookie.contains("HttpOnly")
      results["pairSameSiteStrict"] = setCookie.contains("SameSite=Strict")
      cookie = setCookie.substringBefore(';')
      val withCookie = http("/", mapOf("Cookie" to cookie))
      results["rootWithCookie"] = withCookie.code
      results["rootHasTitle"] = withCookie.body.contains("Phantom AI Integration")
      val state = http("/api/state", mapOf("Authorization" to "Bearer $token"))
      stateBody = state.body
      results["stateCode"] = state.code
      results["stateHasFeature"] = state.body.contains("alpha-feature")
      results["badTokenPair"] = http("/pair?token=wrong").code
    }.onFailure { results["error"] = it.toString() }
    report.step(
      "remote.http",
      expected = mapOf(
        "listening" to true, "rootNoToken" to 401, "xtermNoToken" to 401, "pairCode" to 303, "pairHttpOnly" to true, "pairSameSiteStrict" to true,
        "rootWithCookie" to 200, "rootHasTitle" to true, "stateCode" to 200, "stateHasFeature" to true, "badTokenPair" to 401,
      ),
      observed = mapOf("listening" to listening) + results.filterKeys { it != "pairSetCookie" && it != "error" } + mapOf("note" to (results["error"] ?: "")),
    )

    // WebSocket: no credential -> 401; with the cookie (and our own origin) -> a screen frame for the live terminal tab
    val tabId = Regex("\"tabId\":\"([^\"]+)\"").find(stateBody)?.groupValues?.get(1)
    fun ws(withCookie: Boolean): Pair<String, String> {
      Socket().use { s ->
        s.connect(InetSocketAddress("127.0.0.1", port), 3000)
        s.soTimeout = 15000
        val key = Base64.getEncoder().encodeToString(ByteArray(16) { it.toByte() })
        val req = "GET /ws?tab=${tabId ?: "x"} HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: $key\r\n" +
          "Sec-WebSocket-Version: 13\r\nOrigin: http://127.0.0.1:$port\r\n" + (if (withCookie) "Cookie: $cookie\r\n" else "") + "\r\n"
        val out = s.getOutputStream()
        out.write(req.toByteArray())
        out.flush()
        val input = s.getInputStream()
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
          val b = input.read()
          if (b < 0) break
          head.append(b.toChar())
        }
        val status = head.lineSequence().firstOrNull().orEmpty()
        if (!status.contains(" 101")) return status to ""
        // masked client text frame {"t":"auth","token":""}: the cookie already proved the credential
        val payload = "{\"t\":\"auth\",\"token\":\"\"}".toByteArray()
        val mask = byteArrayOf(1, 2, 3, 4)
        out.write(byteArrayOf(0x81.toByte(), (0x80 or payload.size).toByte()) + mask + ByteArray(payload.size) { (payload[it].toInt() xor mask[it % 4].toInt()).toByte() })
        out.flush()
        fun b() = input.read().also { if (it < 0) throw java.io.EOFException() }
        val b0 = b()
        var len = b() and 0x7f
        if (len == 126) len = (b() shl 8) or b() else if (len == 127) { var l = 0L; repeat(8) { l = (l shl 8) or b().toLong() }; len = l.toInt() }
        val data = ByteArray(len)
        var read = 0
        while (read < len) { val n = input.read(data, read, len - read); if (n < 0) break; read += n }
        return status to (if (b0 and 0x0f == 1) String(data, 0, read) else "opcode ${b0 and 0x0f}")
      }
    }
    var noCred = "" to ""
    var withCred = "" to ""
    var wsError = ""
    runCatching {
      noCred = ws(false)
      if (tabId != null) withCred = ws(true)
    }.onFailure { wsError = it.toString() }
    report.step(
      "remote.ws",
      expected = mapOf("liveTab" to true, "noCredential401" to true, "withCookie101" to true, "screenFrame" to true),
      observed = mapOf(
        "liveTab" to (tabId != null), "noCredential401" to noCred.first.contains(" 401"), "withCookie101" to withCred.first.contains(" 101"),
        "screenFrame" to withCred.second.contains("\"t\":\"screen\""), "noCredentialStatus" to noCred.first, "withCookieStatus" to withCred.first, "error" to wsError,
      ),
    )
  }
}
