// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.realide.probe

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.DialogWrapperDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.awt.AWTEvent
import java.awt.Component
import java.awt.Container
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Robot
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.InputEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.JTextField
import javax.swing.MenuSelectionManager
import javax.swing.SwingUtilities
import javax.swing.event.ChangeListener

/**
 * Right-clicks as a user does and drives the menu that opens. [diagnose] tries every way a right-click can reach a
 * component and records, per event, who saw it; the menu steps then use the first way that opened the platform's own
 * popup (its handlePopup binds the clicked tab). Items are run with JMenuItem.doClick on that popup.
 */
object ProbePopup {
  /** How a right-click is made. Only these count as real input; "direct" (dispatchEvent, no queue) is a control. */
  val VARIANTS = listOf("label.pressTrigger", "window.pressTrigger", "label.releaseTrigger", "label.bothTrigger", "label.s1Sequence", "robot")

  private fun post(e: AWTEvent) = Toolkit.getDefaultToolkit().systemEventQueue.postEvent(e)

  private fun ev(c: Component, id: Int, p: Point, button: Int, mods: Int, clicks: Int, popup: Boolean) =
    MouseEvent(c, id, System.currentTimeMillis(), mods, p.x, p.y, clicks, popup, button)

  /** Window that holds [c] and [p] converted into it: the event then goes through the window's LightweightDispatcher. */
  private fun inWindow(c: Component, p: Point): Pair<Component, Point> {
    val w = SwingUtilities.getWindowAncestor(c) ?: return c to p
    return w to SwingUtilities.convertPoint(c, p, w)
  }

  /**
   * Posts a BUTTON1 or BUTTON3 click on [c] at [p] the way [variant] says; "robot" is not posted here (see [robotClick]).
   * Call on the EDT.
   */
  fun postClick(c: Component, p: Point, variant: String, button: Int = MouseEvent.BUTTON3) {
    val down = if (button == MouseEvent.BUTTON3) InputEvent.BUTTON3_DOWN_MASK else InputEvent.BUTTON1_DOWN_MASK
    val right = button == MouseEvent.BUTTON3
    val (pressTrigger, releaseTrigger) = when (variant) {
      "label.releaseTrigger" -> false to right
      "label.bothTrigger" -> right to right
      else -> right to false
    }
    val (target, at) = if (variant.startsWith("window.")) inWindow(c, p) else c to p
    if (variant == "label.s1Sequence") {
      // exactly what s1's smoke posted (entered, moved, pressed and released both as trigger, no click)
      post(ev(c, MouseEvent.MOUSE_ENTERED, p, MouseEvent.NOBUTTON, 0, 0, false))
      post(ev(c, MouseEvent.MOUSE_MOVED, p, MouseEvent.NOBUTTON, 0, 0, false))
      post(ev(c, MouseEvent.MOUSE_PRESSED, p, button, down, 1, true))
      post(ev(c, MouseEvent.MOUSE_RELEASED, p, button, 0, 1, true))
      return
    }
    if (variant == "direct") {
      c.dispatchEvent(ev(c, MouseEvent.MOUSE_PRESSED, p, button, down, 1, pressTrigger))
      c.dispatchEvent(ev(c, MouseEvent.MOUSE_RELEASED, p, button, 0, 1, releaseTrigger))
      return
    }
    post(ev(target, MouseEvent.MOUSE_MOVED, at, MouseEvent.NOBUTTON, 0, 0, false))
    post(ev(target, MouseEvent.MOUSE_PRESSED, at, button, down, 1, pressTrigger))
    post(ev(target, MouseEvent.MOUSE_RELEASED, at, button, 0, 1, releaseTrigger))
    post(ev(target, MouseEvent.MOUSE_CLICKED, at, button, 0, 1, false))
  }

  /**
   * java.awt.Robot control. The pointer is first only moved onto [c]; the right button is pressed only when the sandbox
   * window itself then reports the pointer (so a window of another app, e.g. the user's own IDE, is never clicked).
   * Call off the EDT.
   */
  suspend fun robotClick(ctx: ProbeContext, c: Component, p: Point, button: Int = MouseEvent.BUTTON3): Map<String, Any?> {
    val screen = ctx.edt { if (c.isShowing) ProbeInput.toScreen(c, p) else null } ?: return mapOf("robot" to "component not showing")
    val robot = try {
      Robot()
    }
    catch (e: Exception) {
      return mapOf("robot" to "unavailable: $e")
    }
    val saved = MouseInfo.getPointerInfo()?.location
    val seen = CopyOnWriteArrayList<String>()
    val spy = AWTEventListener { e ->
      if (e is MouseEvent && (e.id == MouseEvent.MOUSE_MOVED || e.id == MouseEvent.MOUSE_ENTERED)) {
        val w = SwingUtilities.getWindowAncestor(e.component)
        if (w != null && w === SwingUtilities.getWindowAncestor(c)) seen.add("${e.id}@${e.component.javaClass.simpleName}")
      }
    }
    Toolkit.getDefaultToolkit().addAWTEventListener(spy, AWTEvent.MOUSE_MOTION_EVENT_MASK or AWTEvent.MOUSE_EVENT_MASK)
    try {
      withContext(Dispatchers.IO) {
        robot.mouseMove(screen.x - 2, screen.y)
        robot.mouseMove(screen.x, screen.y)
      }
      delay(400)
      val reached = seen.isNotEmpty()
      if (!reached) return mapOf("robot" to "pointer moved, sandbox window got no mouse-moved (no Accessibility permission or window covered): not clicked", "screen" to listOf(screen.x, screen.y))
      val mask = if (button == MouseEvent.BUTTON3) InputEvent.BUTTON3_DOWN_MASK else InputEvent.BUTTON1_DOWN_MASK
      withContext(Dispatchers.IO) {
        robot.mousePress(mask)
        robot.delay(40)
        robot.mouseRelease(mask)
      }
      return mapOf("robot" to "clicked", "screen" to listOf(screen.x, screen.y), "movedSeen" to seen.take(5))
    }
    finally {
      Toolkit.getDefaultToolkit().removeAWTEventListener(spy)
      if (saved != null) withContext(Dispatchers.IO) { robot.mouseMove(saved.x, saved.y) }
    }
  }

  /** Closes whatever popup menu is open. Call on the EDT. */
  fun closeAll() {
    MenuSelectionManager.defaultManager().clearSelectedPath()
    var p = ProbeInput.openPopup()
    var guard = 0
    while (p != null && guard++ < 5) {
      p.isVisible = false
      p = ProbeInput.openPopup()
    }
  }

  /**
   * One right-click on [c] by [variant], with every mouse event recorded: the global AWT listener (source class, id,
   * isPopupTrigger, consumed) and a listener on [c] itself, run after the platform's own (consumed after it). Returns
   * whether a popup opened (seen by the MenuSelectionManager or showing in a window) and what it was.
   */
  suspend fun attempt(ctx: ProbeContext, c: Component, variant: String, waitMs: Long = 2500): Map<String, Any?> {
    val global = CopyOnWriteArrayList<String>()
    val onTarget = CopyOnWriteArrayList<String>()
    val selections = CopyOnWriteArrayList<String>()
    fun d(e: MouseEvent) = "${name(e.id)}@${e.component.javaClass.simpleName} trigger=${e.isPopupTrigger} button=${e.button} consumed=${e.isConsumed}"
    val spy = AWTEventListener { e -> if (e is MouseEvent && e.id != MouseEvent.MOUSE_MOVED) global.add(d(e)) }
    val own = object : MouseAdapter() {
      override fun mousePressed(e: MouseEvent) { onTarget.add(d(e)) }
      override fun mouseReleased(e: MouseEvent) { onTarget.add(d(e)) }
      override fun mouseClicked(e: MouseEvent) { onTarget.add(d(e)) }
    }
    val msm = ChangeListener {
      MenuSelectionManager.defaultManager().selectedPath.firstOrNull()?.let { selections.add(it.javaClass.simpleName) }
    }
    ctx.edt {
      closeAll()
      Toolkit.getDefaultToolkit().addAWTEventListener(spy, AWTEvent.MOUSE_EVENT_MASK)
      c.addMouseListener(own)
      MenuSelectionManager.defaultManager().addChangeListener(msm)
    }
    val p = ctx.edt { Point(c.width / 2, c.height / 2) }
    var robot: Map<String, Any?>? = null
    if (variant == "robot") robot = robotClick(ctx, c, p)
    else ctx.edt { postClick(c, p, variant) }
    val opened = ctx.waitUntil(waitMs) { ProbeInput.openPopup() != null }
    val popupInfo = ctx.edt {
      val popup = ProbeInput.openPopup()
      mapOf(
        "popupOpened" to (popup != null),
        "invoker" to popup?.invoker?.javaClass?.simpleName,
        "items" to (popup?.let { topItems(it).size } ?: 0),
      )
    }
    ctx.edt {
      Toolkit.getDefaultToolkit().removeAWTEventListener(spy)
      c.removeMouseListener(own)
      MenuSelectionManager.defaultManager().removeChangeListener(msm)
    }
    val context = if (opened) emptyMap() else ctx.edt {
      mapOf(
        "showingWindows" to Window.getWindows().filter { it.isShowing }.map { it.javaClass.simpleName + (if (it.isActive) "*" else "") },
        "jbPopupActive" to com.intellij.ide.IdeEventQueue.getInstance().isPopupActive,
        "focusOwner" to java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner?.javaClass?.simpleName,
        "targetShowing" to c.isShowing, "targetVisible" to c.isVisible, "targetBounds" to c.bounds.let { listOf(it.x, it.y, it.width, it.height) },
      )
    }
    return linkedMapOf<String, Any?>("variant" to variant, "opened" to opened) + popupInfo + context + mapOf(
      "global" to global.toList().take(12), "onTarget" to onTarget.toList().take(8), "menuSelection" to selections.distinct(),
    ) + (robot?.let { mapOf("robot" to it) } ?: emptyMap())
  }

  private fun name(id: Int) = when (id) {
    MouseEvent.MOUSE_PRESSED -> "PRESSED"
    MouseEvent.MOUSE_RELEASED -> "RELEASED"
    MouseEvent.MOUSE_CLICKED -> "CLICKED"
    MouseEvent.MOUSE_ENTERED -> "ENTERED"
    MouseEvent.MOUSE_EXITED -> "EXITED"
    MouseEvent.MOUSE_DRAGGED -> "DRAGGED"
    else -> id.toString()
  }

  /** The items of [popup] itself, in order, without going into submenus. */
  fun topItems(popup: JPopupMenu): List<JMenuItem> = popup.components.filterIsInstance<JMenuItem>()

  fun texts(popup: JPopupMenu): List<String> = topItems(popup).filter { it.isVisible }.mapNotNull { it.text }

  /**
   * Runs the item at [path] (top-level text, then submenu texts) of the open [popup] by JMenuItem.doClick. A submenu is
   * opened by selecting it, as hovering does. The click is queued (not run inside this call): an item that opens a
   * modal dialog would otherwise block the step that has to answer it.
   */
  suspend fun click(ctx: ProbeContext, popup: JPopupMenu, path: List<String>): Map<String, Any?> {
    var items = ctx.edt { topItems(popup) }
    var trail: Array<javax.swing.MenuElement> = arrayOf(popup)
    for ((i, text) in path.withIndex()) {
      val item = ctx.edt { items.firstOrNull { it.text == text && it.isVisible } }
        ?: return mapOf("clicked" to false, "missing" to text, "available" to ctx.edt { items.mapNotNull { it.text } })
      if (i < path.lastIndex) {
        val menu = item as? JMenu ?: return mapOf("clicked" to false, "notASubmenu" to text)
        ctx.edt {
          trail = trail + menu + menu.popupMenu
          MenuSelectionManager.defaultManager().selectedPath = trail
        }
        ctx.waitUntil(4000) { menu.popupMenu.components.any { it is JMenuItem } }
        items = ctx.edt { menu.popupMenu.components.filterIsInstance<JMenuItem>() }
        continue
      }
      ctx.edt {
        ApplicationManager.getApplication().invokeLater({ item.doClick(0) }, ModalityState.nonModal())
      }
      return mapOf("clicked" to true, "item" to text)
    }
    return mapOf("clicked" to false)
  }

  /**
   * Answers the modal dialog the last action opened: finds the showing DialogWrapper, puts [text] in its text field and
   * runs its doOKAction. Returns what was found.
   */
  suspend fun answerDialog(ctx: ProbeContext, text: String, timeoutMs: Long = 6000): Map<String, Any?> {
    var wrapper: DialogWrapper? = null
    ctx.waitUntilAny(timeoutMs) {
      wrapper = Window.getWindows().filter { it.isShowing }.filterIsInstance<DialogWrapperDialog>().firstOrNull()?.dialogWrapper
      wrapper != null
    }
    val w = wrapper ?: return mapOf("dialog" to false)
    return ctx.edtAny {
      val field = findField(w.contentPane ?: w.rootPane)
      field?.text = text
      val ok = DialogWrapper::class.java.getDeclaredMethod("doOKAction").apply { isAccessible = true }
      ok.invoke(w)
      mapOf("dialog" to true, "title" to w.title, "field" to (field != null), "answered" to text, "closed" to !w.isShowing)
    }
  }

  /** Cancels the modal dialog the last action opened (its doCancelAction, as Esc or the Cancel button does). */
  suspend fun cancelDialog(ctx: ProbeContext, timeoutMs: Long = 6000): Map<String, Any?> {
    var wrapper: DialogWrapper? = null
    ctx.waitUntilAny(timeoutMs) {
      wrapper = Window.getWindows().filter { it.isShowing }.filterIsInstance<DialogWrapperDialog>().firstOrNull()?.dialogWrapper
      wrapper != null
    }
    val w = wrapper ?: return mapOf("dialog" to false)
    return ctx.edtAny {
      val prefilled = findField(w.contentPane ?: w.rootPane)?.text
      w.doCancelAction()
      mapOf("dialog" to true, "title" to w.title, "prefilled" to prefilled, "cancelled" to true, "closed" to !w.isShowing, "exitCode" to w.exitCode)
    }
  }

  private fun findField(c: Component?): JTextField? {
    if (c is JTextField && c.isShowing && c.isEditable) return c
    if (c is Container) for (child in c.components) findField(child)?.let { return it }
    return null
  }
}
