// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.realide.probe

import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.ex.WindowManagerEx
import java.awt.Component
import java.awt.Desktop
import java.awt.Point
import java.awt.Toolkit
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.MenuElement
import javax.swing.MenuSelectionManager
import javax.swing.SwingUtilities

/**
 * Input as a user makes it. Mouse events go through the system event queue (via = "eventQueue"); a menu action is run by
 * opening the real popup from a popup-trigger event and clicking its item (via = "popup.doClick"), never through
 * ActionManager with a made-up DataContext. Call on the EDT.
 */
object ProbeInput {
  const val VIA_QUEUE = "eventQueue"
  const val VIA_POPUP = "popup.doClick"
  /** A popup shown by createActionPopupMenu(...).show, not opened by a right-click: never counts as popup.doClick. */
  const val VIA_PROGRAMMATIC = "popup.programmatic"

  private fun post(e: MouseEvent) = Toolkit.getDefaultToolkit().systemEventQueue.postEvent(e)

  private fun mouse(c: Component, id: Int, p: Point, button: Int, mods: Int, clicks: Int = 1, popup: Boolean = false) =
    MouseEvent(c, id, System.currentTimeMillis(), mods, p.x, p.y, clicks, popup, button)

  fun click(c: Component, p: Point, button: Int = MouseEvent.BUTTON1) {
    val mask = if (button == MouseEvent.BUTTON3) InputEvent.BUTTON3_DOWN_MASK else InputEvent.BUTTON1_DOWN_MASK
    post(mouse(c, MouseEvent.MOUSE_PRESSED, p, button, mask))
    post(mouse(c, MouseEvent.MOUSE_RELEASED, p, button, 0))
    post(mouse(c, MouseEvent.MOUSE_CLICKED, p, button, 0))
  }

  /** Press at [from], dragged through [steps] points, release at [to]. */
  fun drag(c: Component, from: Point, to: Point, steps: Int = 8) {
    val mask = InputEvent.BUTTON1_DOWN_MASK
    post(mouse(c, MouseEvent.MOUSE_PRESSED, from, MouseEvent.BUTTON1, mask))
    for (i in 1..steps) {
      val p = Point(from.x + (to.x - from.x) * i / steps, from.y + (to.y - from.y) * i / steps)
      post(mouse(c, MouseEvent.MOUSE_DRAGGED, p, MouseEvent.BUTTON1, mask))
    }
    post(mouse(c, MouseEvent.MOUSE_RELEASED, to, MouseEvent.BUTTON1, 0))
  }

  /** Right-click on [c] as a popup trigger (the platform opens EditorTabPopupMenu from it). */
  fun popupTrigger(c: Component, p: Point) {
    val mask = InputEvent.BUTTON3_DOWN_MASK
    // the pointer arrives first, as it does for a person: hover state and the tab under it are set before the press
    post(mouse(c, MouseEvent.MOUSE_ENTERED, p, MouseEvent.NOBUTTON, 0))
    post(mouse(c, MouseEvent.MOUSE_MOVED, p, MouseEvent.NOBUTTON, 0))
    post(mouse(c, MouseEvent.MOUSE_PRESSED, p, MouseEvent.BUTTON3, mask, popup = true))
    post(mouse(c, MouseEvent.MOUSE_RELEASED, p, MouseEvent.BUTTON3, 0, popup = true))
  }

  /**
   * The popup menu that is open now, if any: the one the MenuSelectionManager holds, else a showing JPopupMenu found in
   * the windows (a lightweight one sits in a layered pane, a heavyweight one in its own window).
   */
  fun openPopup(): JPopupMenu? =
    MenuSelectionManager.defaultManager().selectedPath.filterIsInstance<JPopupMenu>().firstOrNull()
      ?: java.awt.Window.getWindows().asSequence().filter { it.isShowing }.mapNotNull { findPopup(it) }.firstOrNull()

  private fun findPopup(c: Component): JPopupMenu? {
    if (c is JPopupMenu && c.isShowing) return c
    if (c is java.awt.Container) for (child in c.components) findPopup(child)?.let { return it }
    return null
  }

  /** Menu items of [menu] (recursively through submenus' popups), in order. */
  fun items(menu: MenuElement): List<JMenuItem> =
    menu.subElements.flatMap { e -> if (e is JMenuItem) listOf(e) + e.subElements.flatMap(::items) else items(e) }

  fun doClick(item: JMenuItem) = item.doClick(0)

  /** Outcome of [focus]: [env] is "focus-unavailable" when even the platform's own baseline could not make the app active. */
  class Focus(val active: Boolean, val env: String?, val baseline: Map<String, Any?>) {
    /** Merge into a step's observed map; pass [env] along, so a failed baseline gives ok:null rather than a plugin failure. */
    fun observed(): Map<String, Any?> = mapOf("active" to active, "baseline" to baseline)
  }

  private fun appActive() = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow != null

  /**
   * Makes the sandbox the active app before focus-dependent steps. When it still is not active, runs the platform's own
   * baseline (ProjectUtil.focusProjectWindow(project, true)) and reports it: baseline.ok=false means the environment cannot
   * give focus, not that the plugin is wrong. Call on the EDT.
   */
  fun focus(project: Project): Focus {
    if (bringToFront(project)) return Focus(true, null, mapOf("ok" to true, "via" to "requestForeground"))
    try {
      ProjectUtil.focusProjectWindow(project, true)
    }
    catch (e: Exception) {
      return Focus(false, "focus-unavailable", mapOf("ok" to false, "via" to "focusProjectWindow", "error" to e.toString()))
    }
    val ok = appActive()
    return Focus(ok, if (ok) null else "focus-unavailable", mapOf("ok" to ok, "via" to "focusProjectWindow", "osascript" to lastOsascript))
  }

  /**
   * Baseline for zoom steps: one frame, toggled to maximized and back by the window system itself. ok=false (env
   * "zoom-unavailable") means this environment does not zoom frames at all. Call on the EDT.
   */
  fun zoomBaseline(project: Project): Focus {
    val frame = WindowManagerEx.getInstanceEx().getFrame(project) ?: return Focus(false, "zoom-unavailable", mapOf("ok" to false, "reason" to "no frame"))
    val before = frame.extendedState
    val size = frame.size
    frame.extendedState = before xor java.awt.Frame.MAXIMIZED_BOTH
    val changed = frame.size != size || frame.extendedState != before
    frame.extendedState = before
    return Focus(changed, if (changed) null else "zoom-unavailable", mapOf("ok" to changed, "via" to "extendedState"))
  }

  /** Whether a window of the app is active after asking the OS for the foreground. */
  fun bringToFront(project: Project): Boolean {
    try {
      if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_REQUEST_FOREGROUND)) {
        Desktop.getDesktop().requestForeground(true)
      }
    }
    catch (_: Exception) {}
    if (!appActive() && System.getProperty("os.name").startsWith("Mac")) activateByPid()
    try {
      val frame = WindowManagerEx.getInstanceEx().getFrame(project)
      frame?.toFront()
      IdeFocusManager.getInstance(project).requestFocusInProject(frame?.rootPane ?: return false, project)
    }
    catch (_: Exception) {}
    return appActive()
  }

  /** macOS: the OS is asked to make this process frontmost, which Desktop.requestForeground does not always manage. */
  private fun activateByPid() {
    try {
      val script = "tell application \"System Events\" to set frontmost of (first process whose unix id is ${ProcessHandle.current().pid()}) to true"
      val p = ProcessBuilder("osascript", "-e", script).redirectErrorStream(true).start()
      val output = String(p.inputStream.readAllBytes()).trim()
      val exited = p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
      lastOsascript = mapOf("rc" to (if (exited) p.exitValue() else null), "output" to output.take(300))
      val end = System.currentTimeMillis() + 1500
      while (!appActive() && System.currentTimeMillis() < end) Thread.sleep(50)
    }
    catch (e: Exception) {
      lastOsascript = mapOf("rc" to null, "output" to e.toString())
    }
  }

  /** Exit code and output of the last osascript activation (a TCC/Automation denial shows here), null if never run. */
  @Volatile var lastOsascript: Map<String, Any?>? = null

  fun toScreen(c: Component, p: Point): Point = Point(p).also { SwingUtilities.convertPointToScreen(it, c) }
}
