// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.realide.probe

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindowManager
import java.awt.Component
import java.awt.Container
import java.awt.Point
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.io.File
import javax.swing.JComponent
import javax.swing.SwingUtilities

private const val OFFICE_ACTION = "dev.phantom.ai.visualization.OpenAiOffice"
private const val PEOPLE = "phantom.aiviz.people"
private const val THEME = "phantom.aiviz.theme"
private const val AI_TOOL_WINDOW = "Phantom AI Integration"

/**
 * Phantom AI Visualization in a real IDE, through the UI only: no ai-visualization or ai-integrations class is imported. The AI Office
 * canvas is found by its read-only client property "phantom.aiviz.people"; fake sessions in all four states are put into the
 * SessionStore of the dev.phantom.ai plugin through that plugin's class loader (reflection), one real stub-CLI terminal tab is
 * launched the same way, and nothing goes through real hooks or the real home (the task redirects the agent dirs and CLIs).
 * Steps: office.opens, office.floors, office.people, office.state.working|waiting|done|idle (ai-office.png), office.hover,
 * office.theme.b (theme B set through the settings service, ai-office-b.png), office.theme.a (back to A),
 * office.click.person, office.pan, office.zoom (ai-office-zoom.png), office.timer.runs, office.timer.stops, office.timer.restarts.
 */
internal object AiVisualizationScenario : ProbeScenario {
  override suspend fun run(ctx: ProbeContext) {
    val report = ctx.report
    fun walk(c: Component, out: MutableList<Component> = ArrayList()): List<Component> {
      out += c
      if (c is Container) c.components.forEach { walk(it, out) }
      return out
    }
    fun editor(): FileEditor? = FileEditorManager.getInstance(ctx.project).selectedEditor
    fun officeEditorOpen(): Boolean = editor()?.name == "AI Office"
    fun canvas(): JComponent? = FileEditorManager.getInstance(ctx.project).allEditors.firstOrNull { it.name == "AI Office" }
      ?.component?.let { r -> walk(r).filterIsInstance<JComponent>().firstOrNull { it.getClientProperty(PEOPLE) != null } }
    fun simple(name: String): Component? = FileEditorManager.getInstance(ctx.project).allEditors.firstOrNull { it.name == "AI Office" }
      ?.component?.let { r -> walk(r).firstOrNull { it.javaClass.simpleName == name } }
    @Suppress("UNCHECKED_CAST")
    fun people(): List<Map<String, Any?>> = (canvas()?.getClientProperty(PEOPLE) as? List<Map<String, Any?>>).orEmpty()
    fun peopleRef(): Any? = canvas()?.getClientProperty(PEOPLE)
    fun aiTw() = ToolWindowManager.getInstance(ctx.project).getToolWindow(AI_TOOL_WINDOW)

    // the frame is big enough for a readable office
    ctx.edt { ProbeSnapshot.frame(ctx.project)?.let { f -> val s = f.graphicsConfiguration?.bounds; f.setSize(minOf(1500, s?.width ?: 1500), minOf(950, s?.height ?: 950)) } }

    // fake sessions in all four states, put into the SessionStore of dev.phantom.ai; Korean titles on purpose
    val fixtures = ctx.fixtures.toFile()
    fun wt(name: String) = fixtures.resolve(name).canonicalPath
    val seeded = ctx.edt {
      val loader = PluginManagerCore.getPlugin(PluginId.getId("dev.phantom.ai"))?.pluginClassLoader ?: return@edt "no dev.phantom.ai plugin"
      val storeClass = Class.forName("dev.phantom.ai.core.SessionStore", true, loader)
      val kindClass = Class.forName("dev.phantom.ai.core.AgentKind", true, loader)
      val statusClass = Class.forName("dev.phantom.ai.core.AgentStatus", true, loader)
      val sessionClass = Class.forName("dev.phantom.ai.core.AgentSession", true, loader)
      val store = ApplicationManager.getApplication().getService(storeClass)
      fun enumOf(c: Class<*>, n: String?): Any? = n?.let { c.enumConstants.first { e -> (e as Enum<*>).name == it } }
      val ctor = sessionClass.constructors.filter { c -> c.parameterTypes.none { it.name.endsWith("DefaultConstructorMarker") } }.maxByOrNull { it.parameterCount }!!
      val now = System.currentTimeMillis()
      fun put(tab: String, worktree: String, kind: String, status: String?, title: String?, minutesAgo: Long) {
        // tabId, worktree, kind, status, unread, sessionId, userTitle, firstPrompt, lastPrompt, lastAnswer, startedAt, accountId, subagents
        val s = ctor.newInstance(tab, worktree, enumOf(kindClass, kind), enumOf(statusClass, status), false, null, title, null, null, null, now - minutesAgo * 60_000, null, emptyList<Any>())
        storeClass.getMethod("put", sessionClass).invoke(store, s)
      }
      put("probe-w1", wt("alpha"), "CLAUDE", "WORKING", "로그인 버그 수정 작업 중", 12)
      put("probe-w2", wt("alpha"), "CODEX", "WAITING", "Refactor the 결제 module", 7)
      put("probe-w3", wt("alpha"), "CLAUDE", "DONE", "문서 정리 끝", 41)
      put("probe-a1", wt("alpha-feature"), "CLAUDE", "WAITING", "승인 대기: 마이그레이션 스크립트 실행", 3)
      put("probe-h1", wt("alpha-hotfix"), "CODEX", "DONE", "Hotfix 배포 완료", 95)
      put("probe-d1", wt("alpha-docs"), "CLAUDE", "WORKING", "README 한글 번역", 18)
      // a real stub-CLI terminal tab (status none) in alpha-docs, for click-to-focus
      val svcClass = Class.forName("dev.phantom.ai.terminal.AgentTerminalService", true, loader)
      val svc = ctx.project.getService(svcClass)
      val claude = enumOf(kindClass, "CLAUDE")
      val tab = svcClass.methods.first { it.name == "launch" && it.parameterCount == 3 }.invoke(svc, wt("alpha-docs"), claude, "probe idle")
      "ok tab=$tab"
    }
    report.step("office.seed", expected = mapOf("seeded" to true), observed = mapOf("seeded" to (seeded as? String)?.startsWith("ok tab="), "result" to seeded))

    // Open AI Office from its Tools-menu action
    val rootComponent = ctx.edt { ProbeSnapshot.frame(ctx.project)?.rootPane }
    val actionFound = ctx.edt { ActionManager.getInstance().getAction(OFFICE_ACTION) != null }
    ctx.edt {
      val a = ActionManager.getInstance().getAction(OFFICE_ACTION)
      if (a != null && rootComponent != null) ActionUtil.invokeAction(a, rootComponent, ActionPlaces.MAIN_MENU, null, null)
    }
    val opened = ctx.waitUntil(30_000) { officeEditorOpen() && canvas()?.isShowing == true }
    report.step(
      "office.opens",
      expected = mapOf("actionRegistered" to true, "aiOfficeTabSelected" to true, "canvasShowing" to true),
      observed = ctx.edt { mapOf("actionRegistered" to actionFound, "aiOfficeTabSelected" to officeEditorOpen(), "canvasShowing" to (canvas()?.isShowing == true), "opened" to opened) },
    )

    // the side tool windows would squeeze the office to a sliver: hide them for the screenshots
    suspend fun hideSideWindows() {
      ctx.edt { for (id in listOf("Project", AI_TOOL_WINDOW)) ToolWindowManager.getInstance(ctx.project).getToolWindow(id)?.hide() }
      ctx.waitUntil(5_000) { aiTw()?.isVisible != true }
    }
    hideSideWindows()

    // all four states drawn: data arrives through the debounced, off-EDT refresh
    val statesOf = { people().map { it["status"] }.toSet() }
    val four = setOf("working", "waiting", "done", "idle")
    val got = ctx.waitUntil(60_000) { statesOf().containsAll(four) && people().size >= 7 }
    ctx.edt { canvas()?.let { Toolkit.getDefaultToolkit().sync() } }
    report.step(
      "office.floors",
      expected = mapOf("pickerShowing" to true, "legendShowing" to true, "canvasHasFloor" to true),
      observed = ctx.edt {
        mapOf(
          "pickerShowing" to (simple("FloorPickerView")?.let { it.isShowing && it.width > 0 && it.height > 0 } == true),
          "legendShowing" to (simple("LegendView")?.let { it.isShowing && it.width > 0 } == true),
          "canvasHasFloor" to people().isNotEmpty(),
        )
      },
    )
    report.step(
      "office.people",
      expected = mapOf("allFourStates" to true, "sevenPeople" to true),
      observed = ctx.edt { mapOf("allFourStates" to statesOf().containsAll(four), "sevenPeople" to (people().size >= 7), "count" to people().size, "waited" to got) },
    )

    // one step per state: the person's frame, its bubble and an on-screen rectangle
    ctx.waitUntil(5_000) { true }
    for (state in listOf("working", "waiting", "done", "idle")) {
      val obs = ctx.edt {
        val c = canvas()
        val bounds = Rectangle(0, 0, c?.width ?: 0, c?.height ?: 0)
        val matching = people().filter { it["status"] == state }
        val p = matching.firstOrNull { (it["screen"] as? Rectangle)?.let { r -> bounds.contains(r) } == true } ?: matching.firstOrNull()
        val r = p?.get("screen") as? Rectangle
        mapOf(
          "found" to (p != null),
          "personFrameSuffix" to (p?.get("personFrame") as? String)?.endsWith("_$state"),
          "bubbleFrame" to (state == "working" || (p?.get("bubbleFrame") as? String)?.isNotEmpty() == true),
          "insideCanvas" to (r != null && bounds.contains(r) && r.width > 0 && r.height > 0),
          "personFrame" to p?.get("personFrame"), "bubble" to p?.get("bubbleFrame"), "rect" to r?.toString(),
        )
      }
      report.step("office.state.$state", expected = mapOf("found" to true, "personFrameSuffix" to true, "bubbleFrame" to true, "insideCanvas" to true), observed = obs)
    }
    ctx.edt { ProbeSnapshot.paint(ctx.project, ctx.out, "ai-office.png") }

    // theme B: set like Settings | Tools | Phantom AI Visualization does (VizSettings.themeId, through the plugin's class loader);
    // the open tab re-renders without being reopened. Then back to A, so the remaining steps and the next run start in A.
    suspend fun setTheme(id: String): Any? = ctx.edt {
      val loader = PluginManagerCore.getPlugin(PluginId.getId("dev.phantom.ai.visualization"))?.pluginClassLoader ?: return@edt "no plugin"
      val cls = Class.forName("dev.phantom.ai.visualization.settings.VizSettings", true, loader)
      val svc = ApplicationManager.getApplication().getService(cls)
      cls.getMethod("setThemeId", String::class.java).invoke(svc, id)
      cls.getMethod("getThemeId").invoke(svc)
    }
    for (id in listOf("B", "A")) {
      val stored = setTheme(id)
      val switched = ctx.waitUntil(15_000) { canvas()?.getClientProperty(THEME) == id && people().size >= 7 }
      ctx.waitUntil(1_000) { false }
      if (id == "B") ctx.edt { ProbeSnapshot.paint(ctx.project, ctx.out, "ai-office-b.png") }
      report.step(
        "office.theme.${id.lowercase()}",
        expected = mapOf("stored" to id, "canvasTheme" to id, "sevenPeople" to true),
        observed = ctx.edt { mapOf("stored" to stored, "canvasTheme" to canvas()?.getClientProperty(THEME), "sevenPeople" to (people().size >= 7), "waited" to switched) },
      )
    }

    // hover: the tooltip of a Korean-titled person shows title, agent and elapsed time
    val tip = ctx.edt {
      val c = canvas()
      val p = people().firstOrNull { it["tabId"] == "probe-w1" }
      val r = p?.get("screen") as? Rectangle
      if (c == null || r == null) null else c.getToolTipText(MouseEvent(c, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, r.x + r.width / 2, r.y + r.height / 2, 0, false))
    }
    report.step(
      "office.hover",
      expected = mapOf("hasTitle" to true, "hasAgent" to true, "hasElapsed" to true),
      observed = mapOf("hasTitle" to (tip?.contains("로그인 버그 수정 작업 중") == true), "hasAgent" to (tip?.contains("claude", ignoreCase = true) == true), "hasElapsed" to (tip?.contains("12") == true), "tooltip" to tip),
    )

    // click the person of the real stub tab: the agent terminal tab is focused in the Phantom AI Integration tool window
    val idle = ctx.waitUntil(30_000) { people().any { it["status"] == "idle" && it["tabId"] != "probe-w1" && !(it["tabId"] as String).startsWith("probe-") } }
    val realTab = ctx.edt { people().firstOrNull { it["status"] == "idle" && !(it["tabId"] as String).startsWith("probe-") }?.get("tabId") as? String }
    ctx.edt { aiTw()?.hide() }
    ctx.waitUntil(5_000) { aiTw()?.isVisible == false }
    ctx.edt {
      val c = canvas()
      val r = people().firstOrNull { it["tabId"] == realTab }?.get("screen") as? Rectangle
      if (c != null && r != null) ProbeInput.click(c, Point(r.x + r.width / 2, r.y + r.height / 2))
    }
    val focused = ctx.waitUntil(15_000) { aiTw()?.isVisible == true }
    report.step(
      "office.click.person",
      expected = mapOf("realTabPerson" to true, "toolWindowShown" to true, "via" to ProbeInput.VIA_QUEUE),
      observed = ctx.edt {
        mapOf("realTabPerson" to (realTab != null && idle), "toolWindowShown" to focused, "via" to ProbeInput.VIA_QUEUE, "tabId" to realTab, "toolWindowVisible" to (aiTw()?.isVisible == true))
      },
    )
    // back to the office tab if the click moved focus, side windows hidden again
    hideSideWindows()
    ctx.edt { FileEditorManager.getInstance(ctx.project).allEditors.firstOrNull { it.name == "AI Office" }?.let { e -> e.file?.let { FileEditorManager.getInstance(ctx.project).openFile(it, true) } } }
    ctx.waitUntil(10_000) { officeEditorOpen() && canvas()?.isShowing == true }

    // pan by dragging: the people move with the drag
    val beforePan = ctx.edt { (people().firstOrNull { it["tabId"] == "probe-w1" }?.get("screen") as? Rectangle)?.let { Point(it.x, it.y) } }
    ctx.edt {
      val c = canvas()
      if (c != null) ProbeInput.drag(c, Point(c.width / 2, c.height - 30), Point(c.width / 2 - 24, c.height - 30 - 12))
    }
    val panned = ctx.waitUntil(5_000) { (people().firstOrNull { it["tabId"] == "probe-w1" }?.get("screen") as? Rectangle)?.let { Point(it.x, it.y) } != beforePan }
    val afterPan = ctx.edt { (people().firstOrNull { it["tabId"] == "probe-w1" }?.get("screen") as? Rectangle)?.let { Point(it.x, it.y) } }
    report.step(
      "office.pan", expected = mapOf("moved" to true),
      observed = mapOf("moved" to (panned && beforePan != null && afterPan != null), "before" to beforePan?.toString(), "after" to afterPan?.toString()),
    )

    // ctrl+wheel: an integer zoom step changes the size of the people by the zoom ratio
    fun personWidth(): Int = (people().firstOrNull { it["tabId"] == "probe-w1" }?.get("screen") as? Rectangle)?.width ?: 0
    suspend fun wheel(rotation: Int) = ctx.edt {
      val c = canvas()
      if (c != null) Toolkit.getDefaultToolkit().systemEventQueue.postEvent(
        MouseWheelEvent(c, MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(), InputEvent.CTRL_DOWN_MASK, c.width / 2, c.height / 2, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, rotation),
      )
    }
    val w0 = ctx.edt { personWidth() }
    wheel(-1)
    ctx.waitUntil(3_000) { personWidth() != w0 }
    var w1 = ctx.edt { personWidth() }
    if (w1 == w0) { // already at the largest zoom: step down instead
      wheel(1)
      ctx.waitUntil(3_000) { personWidth() != w0 }
      w1 = ctx.edt { personWidth() }
    }
    // zoom z: width = base*z, so w1/w0 = (z±1)/z; both widths are whole multiples of the same base
    val big = maxOf(w0, w1)
    val small = minOf(w0, w1)
    val g = java.math.BigInteger.valueOf(small.toLong()).gcd(java.math.BigInteger.valueOf(big.toLong())).toInt().coerceAtLeast(1)
    report.step(
      "office.zoom", expected = mapOf("changed" to true, "integerRatioStep" to true, "via" to "mouseWheel"),
      observed = mapOf("changed" to (w1 != w0 && w0 > 0 && w1 > 0), "integerRatioStep" to (small > 0 && big / g - small / g == 1), "via" to "mouseWheel", "widthBefore" to w0, "widthAfter" to w1),
    )
    ctx.waitUntil(1_000) { false }
    ctx.edt { ProbeSnapshot.paint(ctx.project, ctx.out, "ai-office-zoom.png") }

    // the animation timer: the canvas republishes its people on every tick, so a new list = a tick
    suspend fun ticks(ms: Long): Boolean {
      val a = ctx.edt { peopleRef() }
      kotlinx.coroutines.delay(ms)
      val b = ctx.edt { peopleRef() }
      return a !== b
    }
    val runs = ticks(1_200)
    report.step("office.timer.runs", expected = mapOf("ticking" to true), observed = mapOf("ticking" to runs))

    // hidden editor tab: another file takes the editor, the timer stops
    val file = File(wt("alpha"), "a.txt")
    ctx.edt {
      val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
      if (vf != null) FileEditorManager.getInstance(ctx.project).openFile(vf, true)
    }
    val hidden = ctx.waitUntil(15_000) { !officeEditorOpen() && canvas()?.isShowing == false }
    val stillTicking = if (hidden) ticks(1_200) else true
    report.step(
      "office.timer.stops", expected = mapOf("otherEditorSelected" to true, "canvasHidden" to true, "ticking" to false),
      observed = mapOf("otherEditorSelected" to hidden, "canvasHidden" to (ctx.edt { canvas()?.isShowing == false }), "ticking" to stillTicking),
    )

    // back on the AI Office tab: the timer restarts
    ctx.edt { FileEditorManager.getInstance(ctx.project).allEditors.firstOrNull { it.name == "AI Office" }?.file?.let { FileEditorManager.getInstance(ctx.project).openFile(it, true) } }
    val back = ctx.waitUntil(15_000) { officeEditorOpen() && canvas()?.isShowing == true }
    val again = if (back) ticks(1_200) else false
    report.step("office.timer.restarts", expected = mapOf("tabShowing" to true, "ticking" to true), observed = mapOf("tabShowing" to back, "ticking" to again))
  }
}
