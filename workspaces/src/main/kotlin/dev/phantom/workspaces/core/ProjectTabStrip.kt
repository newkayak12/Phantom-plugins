// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.workspaces.core

import com.intellij.icons.AllIcons
import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.IdeRootPaneNorthExtension
import com.intellij.openapi.wm.impl.ProjectFrameHelper
import com.intellij.ui.InplaceButton
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Graphics
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.event.WindowStateListener
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer

/** Adds the project tab strip to every project frame, under the main toolbar and above the editor and tool windows. */
internal class ProjectTabsNorthExtension : IdeRootPaneNorthExtension {
  override val key: String get() = ProjectTabs.EXTENSION_KEY

  override fun createComponent(project: Project, isDocked: Boolean): JComponent? =
    if (isDocked) null else ProjectTabStrip(project)
}

private const val SYNC_DELAY_MS = 300

/**
 * One tab per open project; the tab of this frame's [project] is the current one. Click switches, middle-click or
 * the close button closes. Also watches its frame for [WindowStack] (activation, and moves/resizes, debounced).
 */
class ProjectTabStrip(val project: Project) : JPanel(HorizontalLayout(0)) {
  var tabs: List<ProjectTab> = emptyList()
    private set

  internal var window: FrameWindow? = null
    private set

  private val service: ProjectTabsService get() = ProjectTabsService.getInstance()

  private val syncTimer = Timer(SYNC_DELAY_MS) { service.flushMoves() }.apply { isRepeats = false }
  private val activationListener = object : WindowAdapter() {
    override fun windowActivated(e: WindowEvent) {
      window?.let { service.windowActivated(it) }
    }

    override fun windowDeactivated(e: WindowEvent) {
      if (syncTimer.isRunning) {
        syncTimer.stop()
        service.flushMoves()
      }
    }
  }
  private val moveListener = object : ComponentAdapter() {
    override fun componentMoved(e: ComponentEvent) = moved()
    override fun componentResized(e: ComponentEvent) = moved()
  }
  private val stateListener = WindowStateListener { moved() }

  // whether this window leads is decided now, not when the timer fires
  private fun moved() {
    val window = window ?: return
    if (service.windowMoved(window)) syncTimer.restart()
  }

  init {
    isVisible = false
    border = JBUI.Borders.customLineBottom(JBColor.border())
  }

  override fun addNotify() {
    super.addNotify()
    attach()
  }

  override fun removeNotify() {
    detach()
    super.removeNotify()
  }

  /** Joins the open project windows. Called when the strip is added to its frame. */
  fun attach() {
    (SwingUtilities.getWindowAncestor(this) as? JFrame)?.let(::watch)
    service.attach(this)
  }

  /** Watches [frame] for the stack. */
  fun watch(frame: JFrame) {
    if (window != null) return
    val helper = ProjectFrameHelper.getFrameHelper(frame)
    val control = helper?.let { object : FullScreenControl {
      override val isInFullScreen: Boolean get() = it.isInFullScreen
      override fun toggle(state: Boolean, done: () -> Unit) {
        it.toggleFullScreen(state).invokeOnCompletion { SwingUtilities.invokeLater(done) }
      }
    } }
    window = FrameWindow(frame, control) { ProjectUtil.focusProjectWindow(project) }
    frame.addWindowListener(activationListener)
    frame.addWindowStateListener(stateListener)
    frame.addComponentListener(moveListener)
  }

  /** Leaves the open project windows. Called when the strip is removed from its frame. */
  fun detach() {
    // listeners first: on plugin unload the service may already be gone
    syncTimer.stop()
    window?.frame?.let { frame ->
      frame.removeWindowListener(activationListener)
      frame.removeWindowStateListener(stateListener)
      frame.removeComponentListener(moveListener)
    }
    serviceIfCreated<ProjectTabsService>()?.detach(this)
    window = null
  }

  internal fun display(tabs: List<ProjectTab>, visible: Boolean) {
    if (tabs != this.tabs) {
      this.tabs = tabs
      removeAll()
      for (tab in tabs) add(TabComponent(tab))
      revalidate()
      repaint()
    }
    if (isVisible != visible) isVisible = visible
  }

  private fun select(tab: ProjectTab) {
    if (!tab.isCurrent) service.switchTo(tab.key as Project)
  }

  private fun close(tab: ProjectTab) {
    service.close(tab.key as Project)
  }

  private inner class TabComponent(private val tab: ProjectTab) : JPanel(BorderLayout()) {
    private var hovered = false

    init {
      isOpaque = false
      border = JBUI.Borders.empty(5, 10, 5, 4)
      toolTipText = tab.tooltip
      val title = JBLabel(tab.title)
      if (!tab.isCurrent) title.foreground = UIUtil.getContextHelpForeground()
      add(title, BorderLayout.CENTER)
      val closeButton = InplaceButton(PhantomWorkspacesBundle.message("tab.close.tooltip"), AllIcons.Actions.Close) { close(tab) }
      closeButton.border = JBUI.Borders.emptyLeft(6)
      add(closeButton, BorderLayout.EAST)
      addMouseListener(object : MouseAdapter() {
        override fun mouseReleased(e: MouseEvent) {
          if (!contains(e.point)) return
          when {
            SwingUtilities.isMiddleMouseButton(e) -> close(tab)
            SwingUtilities.isLeftMouseButton(e) -> select(tab)
          }
        }

        override fun mouseEntered(e: MouseEvent) {
          hovered = true
          repaint()
        }

        override fun mouseExited(e: MouseEvent) {
          hovered = false
          repaint()
        }
      })
    }

    override fun paintComponent(g: Graphics) {
      super.paintComponent(g)
      val background = when {
        tab.isCurrent -> JBUI.CurrentTheme.EditorTabs.underlinedTabBackground()
        hovered -> JBUI.CurrentTheme.DefaultTabs.hoverBackground()
        else -> null
      }
      if (background != null) {
        g.color = background
        g.fillRect(0, 0, width, height)
      }
      if (tab.isCurrent) {
        val underline = JBUI.CurrentTheme.EditorTabs.underlineHeight()
        g.color = JBUI.CurrentTheme.EditorTabs.underlineColor()
        g.fillRect(0, height - underline, width, underline)
      }
    }
  }
}
