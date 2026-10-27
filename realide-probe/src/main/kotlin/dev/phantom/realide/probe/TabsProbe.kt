// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.realide.probe

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.fileEditor.impl.EditorTabPresentationUtil
import com.intellij.openapi.fileEditor.impl.EditorWindow
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.tabs.TabInfo
import com.intellij.ui.tabs.impl.TabLabel
import java.awt.Component
import java.awt.Container
import java.awt.Point
import java.awt.event.MouseEvent
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JPopupMenu
import javax.swing.SwingUtilities
import kotlinx.coroutines.delay

/**
 * What the tabs scenarios read and do in the real IDE: tabs per editor pane (TabInfo text, colour, hidden, order), the
 * group model the plugin keeps (read reflectively from its service state: the probe does not compile against the plugin),
 * and right-click menus opened on a tab's label.
 */
internal class TabsProbe(val ctx: ProbeContext) {
  val project = ctx.project
  val alpha: Path = ctx.fixtures.resolve("alpha")

  /** The right-click variant chosen by the popup diagnosis: the first real-input way that opened the platform popup. */
  var trigger: String? = null
  /** How a left click/drag is posted: on the label itself, or on its window (LightweightDispatcher). */
  var clickVariant: String = "label.pressTrigger"

  private val files = java.util.concurrent.ConcurrentHashMap<String, VirtualFile>()

  /** Finds the fixture files in the VFS off the EDT (a VFS lookup is a slow operation there), for [file]. */
  suspend fun resolve(names: List<String>) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    for (n in names) LocalFileSystem.getInstance().refreshAndFindFileByNioFile(alpha.resolve(n))?.let { files[n] = it }
  }

  /** A fixture file found by [resolve]. */
  fun file(name: String): VirtualFile? = files[name]

  fun windows(): List<EditorWindow> = FileEditorManagerEx.getInstanceEx(project).windows.toList()

  fun infos(window: EditorWindow): List<TabInfo> = window.tabbedPane.tabs.tabs.toList()

  fun nameOf(info: TabInfo): String? = (info.`object` as? VirtualFile)?.name

  fun info(name: String, pane: Int = 0): TabInfo? = windows().getOrNull(pane)?.let { w -> infos(w).firstOrNull { nameOf(it) == name } }

  fun label(info: TabInfo): TabLabel? {
    val root = ProbeSnapshot.frame(project)?.rootPane ?: return null
    fun walk(c: Component): TabLabel? {
      if (c is TabLabel && c.info === info) return c
      if (c is Container) for (child in c.components) walk(child)?.let { return it }
      return null
    }
    return walk(root)
  }

  fun order(pane: Int = 0): List<String> = windows().getOrNull(pane)?.let { infos(it).mapNotNull(::nameOf) }.orEmpty()

  fun text(name: String, pane: Int = 0): String? = info(name, pane)?.text

  fun color(name: String, pane: Int = 0): String? = ProbeReport.hex(info(name, pane)?.tabColor)

  fun provider(name: String): String? = file(name)?.let { ProbeReport.hex(EditorTabPresentationUtil.getEditorTabBackgroundColor(project, it)) }

  fun hidden(name: String, pane: Int = 0): Boolean? = info(name, pane)?.isHidden

  /** Every tab of [pane]: name, text, TabInfo.tabColor, provider colour, hidden, label showing. */
  fun tabs(pane: Int = 0): List<Map<String, Any?>> = windows().getOrNull(pane)?.let { w ->
    infos(w).map { i ->
      val n = nameOf(i)
      mapOf(
        "file" to n, "text" to i.text, "tabColor" to ProbeReport.hex(i.tabColor), "provider" to n?.let(::provider),
        "hidden" to i.isHidden, "labelShowing" to (label(i)?.isShowing == true), "selected" to (w.tabbedPane.tabs.selectedInfo === i),
      )
    }
  }.orEmpty()

  private fun service(): Any? {
    val d = PluginManagerCore.getPlugin(PluginId.getId("dev.phantom.tabs")) ?: return null
    val cls = d.pluginClassLoader?.loadClass("dev.phantom.tabs.core.TabGroupsService") ?: return null
    return project.getService(cls)
  }

  private fun call(o: Any?, name: String): Any? = o?.javaClass?.methods?.firstOrNull { it.name == name && it.parameterCount == 0 }?.invoke(o)

  /** The plugin's own state (TabGroupsService.getState()): per pane, groups with name, colour, collapsed, member file names. */
  fun state(): List<List<Map<String, Any?>>> {
    val state = call(service(), "getState") ?: return emptyList()
    return (call(state, "getPanes") as? List<*>).orEmpty().map { pane ->
      (call(pane, "getGroups") as? List<*>).orEmpty().map { g ->
        mapOf(
          "name" to call(g, "getName"), "color" to call(g, "getColor"), "collapsed" to call(g, "getCollapsed"),
          "members" to (call(g, "getUrls") as? List<*>).orEmpty().map { it.toString().substringAfterLast('/') },
        )
      }
    }
  }

  fun controllerCount(): Any? = call(service(), "getControllerCount")

  /** Group name -> member file names, for [pane]. */
  fun members(pane: Int = 0): Map<String, List<String>> =
    state().getOrNull(pane).orEmpty().associate { (it["name"] as? String ?: "") to (it["members"] as List<*>).map(Any?::toString) }

  /** Group name -> members, every pane together. */
  fun allMembers(): Map<String, List<String>> = state().flatten().associate { (it["name"] as? String ?: "") to (it["members"] as List<*>).map(Any?::toString) }

  suspend fun open(names: List<String>, focus: Boolean = true) {
    resolve(names.filter { !files.containsKey(it) })
    ctx.edt {
      val fem = FileEditorManager.getInstance(project)
      for (n in names) file(n)?.let { fem.openFile(it, focus) }
    }
  }

  suspend fun select(name: String) = open(listOf(name))

  /** Right-clicks the tab [name] of [pane] with the chosen trigger and returns the popup that opened (or null). */
  suspend fun rightClick(name: String, pane: Int = 0): Pair<JPopupMenu?, Map<String, Any?>> {
    val variant = trigger ?: return null to mapOf("trigger" to "none: no real right-click opened the popup")
    val label = ctx.edt { info(name, pane)?.let(::label) } ?: return null to mapOf("label" to "no label for $name")
    // a person right-clicks again when no menu came up; every try is recorded
    val tries = ArrayList<Map<String, Any?>>()
    repeat(3) {
      val l = if (it == 0) label else ctx.edt { info(name, pane)?.let(::label) } ?: label
      val a = ProbePopup.attempt(ctx, l, variant, waitMs = 3000)
      tries.add(a)
      if (a["opened"] == true) return ctx.edt { ProbeInput.openPopup() } to (a + mapOf("tries" to tries.size, "failedTries" to tries.dropLast(1)))
      delay(700)
    }
    return null to mapOf("tries" to tries.size, "failedTries" to tries)
  }

  /**
   * Right-clicks tab [name] and runs [path] in the popup by doClick; with [answer] it answers the name dialog. Returns
   * observed details: via, the popup's item texts, what was clicked.
   */
  suspend fun menu(name: String, path: List<String>, answer: String? = null, pane: Int = 0, cancel: Boolean = false): Map<String, Any?> {
    val (popup, attempt) = rightClick(name, pane)
    if (popup == null) return mapOf("via" to "none", "popup" to false, "attempt" to attempt)
    val texts = ctx.edt { ProbePopup.texts(popup) }
    val clicked = ProbePopup.click(ctx, popup, path)
    val dialog = when {
      clicked["clicked"] != true -> null
      cancel -> ProbePopup.cancelDialog(ctx)
      answer != null -> ProbePopup.answerDialog(ctx, answer)
      else -> null
    }
    // a menu step only counts as driven through the popup when the item was really clicked
    if (clicked["clicked"] != true) return ctx.edt { ProbePopup.closeAll() }.let { mapOf("via" to "none", "popup" to true, "items" to texts, "click" to clicked) }
    ctx.waitUntil(3000) { ProbeInput.openPopup() == null }
    ctx.edt { ProbePopup.closeAll() }
    delay(300)
    return linkedMapOf<String, Any?>("via" to ProbeInput.VIA_POPUP, "trigger" to trigger, "popup" to true, "items" to texts, "click" to clicked) +
           (dialog?.let { mapOf("dialog" to it) } ?: emptyMap())
  }

  /** A left click on tab [name], posted through the system event queue. */
  suspend fun leftClick(name: String, pane: Int = 0): Boolean {
    val label = ctx.edt { info(name, pane)?.let(::label) } ?: return false
    ctx.edt { ProbePopup.postClick(label, Point(label.width / 2, label.height / 2), clickVariant, MouseEvent.BUTTON1) }
    return true
  }

  /**
   * Drags tab [name] by its label to [targetX] in the tab bar's coordinates (the x of the gap it should land in), posting
   * press, drags and release through the system event queue with small pauses, as a hand does.
   */
  suspend fun drag(name: String, targetX: (Map<String, java.awt.Rectangle>) -> Int, pane: Int = 0): Map<String, Any?> {
    val label = ctx.edt { info(name, pane)?.let(::label) } ?: return mapOf("label" to false)
    // screen points, fixed: a pointer does not move with the label it drags (the label itself does move)
    val (from, to, bar) = ctx.edt {
      val bar = SwingUtilities.getAncestorOfClass(com.intellij.ui.tabs.JBTabs::class.java, label) ?: label.parent
      val rects = infos(windows()[pane]).associate { i -> (nameOf(i) ?: "") to (label(i)?.let { SwingUtilities.convertRectangle(it.parent, it.bounds, bar) } ?: java.awt.Rectangle()) }
      val from = ProbeInput.toScreen(label, Point(label.width / 2, label.height / 2))
      val to = ProbeInput.toScreen(bar, Point(targetX(rects), rects[name]?.centerY?.toInt() ?: 0))
      Triple(from, to, rects.mapValues { listOf(it.value.x, it.value.width) })
    }
    val q = Toolkit.systemEventQueue
    /** [screen] in the label's coordinates now, made into an event on the label (where a pressed button's events go). */
    fun e(id: Int, screen: Point, mods: Int, button: Int): MouseEvent {
      val p = Point(screen).also { SwingUtilities.convertPointFromScreen(it, label) }
      return MouseEvent(label, id, System.currentTimeMillis(), mods, p.x, p.y, screen.x, screen.y, if (id == MouseEvent.MOUSE_DRAGGED) 0 else 1, false, button)
    }
    val down = java.awt.event.InputEvent.BUTTON1_DOWN_MASK
    ctx.edt { q.postEvent(e(MouseEvent.MOUSE_PRESSED, from, down, MouseEvent.BUTTON1)) }
    delay(80)
    val steps = 12
    for (i in 1..steps) {
      val p = Point(from.x + (to.x - from.x) * i / steps, from.y + (to.y - from.y) * i / steps)
      ctx.edt { q.postEvent(e(MouseEvent.MOUSE_DRAGGED, p, down, MouseEvent.NOBUTTON)) }
      delay(40)
    }
    delay(150)
    ctx.edt { q.postEvent(e(MouseEvent.MOUSE_RELEASED, to, 0, MouseEvent.BUTTON1)) }
    delay(800)
    return mapOf("from" to listOf(from.x, from.y), "to" to listOf(to.x, to.y), "bar" to bar)
  }

  /** Root-pane rectangle inside tab [name]'s label, inset so borders are left out: [x, y, w, h]. */
  fun labelRect(name: String, pane: Int = 0): List<Int>? {
    val l = info(name, pane)?.let(::label) ?: return null
    val root = ProbeSnapshot.frame(project)?.rootPane ?: return null
    val r = SwingUtilities.convertRectangle(l.parent, l.bounds, root)
    return listOf(r.x + 3, r.y + 3, maxOf(1, r.width - 6), maxOf(1, r.height - 6))
  }

  fun writeFixture(name: String) {
    val p = alpha.resolve(name)
    if (!Files.exists(p)) Files.writeString(p, "alpha ${name.substringBefore('.')}\n")
  }

  private object Toolkit {
    val systemEventQueue: java.awt.EventQueue get() = java.awt.Toolkit.getDefaultToolkit().systemEventQueue
  }
}
