// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.workspaces.core

import com.intellij.icons.AllIcons
import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.IdeRootPaneNorthExtension
import com.intellij.openapi.wm.impl.ProjectFrameHelper
import com.intellij.ui.InplaceButton
import com.intellij.openapi.ui.JBMenuItem
import com.intellij.openapi.ui.JBPopupMenu
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Point
import java.awt.dnd.DragSource
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
import kotlin.math.abs

/** Adds the project tab strip to every project frame, under the main toolbar and above the editor and tool windows. */
internal class ProjectTabsNorthExtension : IdeRootPaneNorthExtension {
  override val key: String get() = ProjectTabs.EXTENSION_KEY

  override fun createComponent(project: Project, isDocked: Boolean): JComponent? =
    if (isDocked) null else ProjectTabStrip(project)
}

private const val SYNC_DELAY_MS = 300

/** The narrowest a tab gets when the tabs do not fit; beyond that the strip scrolls. */
private const val MIN_TAB_WIDTH = 96
private const val WHEEL_STEP = 40

/**
 * One tab per open project; the tab of this frame's [project] is the current one. Click switches, middle-click or
 * the close button closes, dragging moves the tab; dragging it up or down out of the strip, or Detach to Separate Window
 * in its context menu, takes the project out into a window of its own; Open Side by Side shows it beside this one. Tabs
 * that do not fit get narrower (titles cut with …), and below [MIN_TAB_WIDTH] the strip scrolls with the wheel or a
 * trackpad, the current tab kept in view. Also watches its frame for [WindowStack] (activation, and moves/resizes, debounced).
 */
class ProjectTabStrip(val project: Project) : JPanel(null) {
  var tabs: List<ProjectTab> = emptyList()
    private set

  internal var window: FrameWindow? = null
    private set

  private val service: ProjectTabsService get() = ProjectTabsService.getInstance()

  // flushed again while the move stays pending (WindowStack defers bounds that look like full screen)
  private val syncTimer: Timer = Timer(SYNC_DELAY_MS) { e -> if (service.flushMoves()) (e.source as Timer).restart() }.apply { isRepeats = false }
  private val activationListener = object : WindowAdapter() {
    override fun windowActivated(e: WindowEvent) {
      window?.let { service.windowActivated(it) }
    }

    override fun windowDeactivated(e: WindowEvent) {
      if (syncTimer.isRunning) {
        syncTimer.stop()
        if (service.flushMoves()) syncTimer.restart()
      }
    }
  }
  private val moveListener = object : ComponentAdapter() {
    override fun componentMoved(e: ComponentEvent) = moved()
    override fun componentResized(e: ComponentEvent) = moved()
    override fun componentShown(e: ComponentEvent) {
      window?.let { service.windowActivated(it) }
    }
  }
  private val stateListener = WindowStateListener { moved() }

  // whether this window leads is decided now, not when the timer fires
  private fun moved() {
    val window = window ?: return
    if (service.windowMoved(window)) syncTimer.restart()
  }

  // how far the tabs are scrolled to the left; kept in range by doLayout
  private var scroll = 0
  private var revealCurrent = true

  init {
    isVisible = false
    border = JBUI.Borders.customLineBottom(JBColor.border())
    addMouseWheelListener { e ->
      val step = (e.preciseWheelRotation * JBUI.scale(WHEEL_STEP)).toInt()
      if (step != 0) {
        scroll += step
        doLayout()
        repaint()
      }
    }
  }

  override fun getPreferredSize(): Dimension {
    val ins = insets
    val height = components.maxOfOrNull { it.preferredSize.height } ?: 0
    return Dimension(components.sumOf { it.preferredSize.width } + ins.left + ins.right, height + ins.top + ins.bottom)
  }

  // never wider than the window: tabs that do not fit shrink and scroll
  override fun getMinimumSize(): Dimension = Dimension(0, preferredSize.height)

  override fun doLayout() {
    val ins = insets
    val available = width - ins.left - ins.right
    val widths = ProjectTabs.fitted(components.map { it.preferredSize.width }, available, JBUI.scale(MIN_TAB_WIDTH))
    if (revealCurrent) {
      revealCurrent = false
      val current = tabs.indexOfFirst { it.isCurrent }
      if (current >= 0 && current < widths.size) {
        val start = widths.take(current).sum()
        scroll = ProjectTabs.reveal(scroll, start, start + widths[current], available)
      }
    }
    scroll = ProjectTabs.scrollOffset(scroll, widths.sum(), available)
    var x = ins.left - scroll
    val height = height - ins.top - ins.bottom
    components.forEachIndexed { i, tab ->
      tab.setBounds(x, ins.top, widths[i], height)
      x += widths[i]
    }
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

  // whether the tab components show [tabs]; they are made only once the strip is shown, so a single project (the strip
  // hidden) makes no tab components at startup
  private var built = false

  internal fun display(tabs: List<ProjectTab>, visible: Boolean) {
    if (tabs != this.tabs) {
      this.tabs = tabs
      built = false
    }
    if (visible && !built) {
      built = true
      removeAll()
      for (tab in tabs) add(TabComponent(tab))
      revealCurrent = true
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

  // the gap between tabs a dragged tab would drop at (0 before the first), -1 when no tab is dragged
  private var dropGap = -1

  private fun dragTo(x: Int) {
    val gap = components.count { it.x + it.width / 2 < x }
    if (gap != dropGap) {
      dropGap = gap
      repaint()
    }
  }

  private fun tearOut(tab: ProjectTab, moveBy: Point) {
    if (dropGap != -1) {
      dropGap = -1
      repaint()
    }
    service.detachProject(tab.key as Project, moveBy)
  }

  private fun showMenu(tab: ProjectTab, component: JComponent, at: Point) {
    val project = tab.key as Project
    val menu = JBPopupMenu()
    val detach = JBMenuItem(PhantomWorkspacesBundle.message("action.PhantomWorkspaces.DetachProject.text"))
    detach.isEnabled = service.canDetach(project)
    detach.addActionListener { service.detachProject(project) }
    menu.add(detach)
    menu.addSeparator()
    val sideBySide = JBMenuItem(PhantomWorkspacesBundle.message("tab.menu.open.side.by.side"))
    sideBySide.isEnabled = service.canOpenSideBySide(this.project, project)
    sideBySide.addActionListener { service.openSideBySide(this.project, project) }
    menu.add(sideBySide)
    if (service.sideBySide().isNotEmpty()) {
      val exit = JBMenuItem(PhantomWorkspacesBundle.message("tab.menu.exit.side.by.side"))
      // the window whose strip it is chosen in stays
      exit.addActionListener { service.exitSideBySide(this.project) }
      menu.add(exit)
    }
    menu.show(component, at.x, at.y)
  }

  private fun drop(tab: ProjectTab) {
    val gap = dropGap
    dropGap = -1
    repaint()
    if (gap >= 0) service.move(tab.key as Project, gap)
  }

  override fun paintChildren(g: Graphics) {
    super.paintChildren(g)
    if (dropGap < 0 || componentCount == 0) return
    val width = JBUI.scale(2)
    val x = if (dropGap < componentCount) getComponent(dropGap).x else getComponent(componentCount - 1).let { it.x + it.width }
    g.color = JBUI.CurrentTheme.EditorTabs.underlineColor()
    g.fillRect((x - width / 2).coerceIn(0, this.width - width), 0, width, height)
  }

  private inner class TabComponent(private val tab: ProjectTab) : JPanel(BorderLayout()) {
    private var hovered = false

    init {
      isOpaque = false
      border = JBUI.Borders.empty(5, 10, 5, 4)
      toolTipText = tab.tooltip
      val title = JBLabel(tab.title)
      if (!tab.isCurrent && !tab.isSideBySide) title.foreground = UIUtil.getContextHelpForeground()
      add(title, BorderLayout.CENTER)
      val closeButton = InplaceButton(PhantomWorkspacesBundle.message("tab.close.tooltip"), AllIcons.Actions.Close) { close(tab) }
      closeButton.border = JBUI.Borders.emptyLeft(6)
      add(closeButton, BorderLayout.EAST)
      val mouse = object : MouseAdapter() {
        private var pressAt: Point? = null
        private var dragging = false
        private var menuShown = false

        // the vertical distance a dragged tab has to go to be torn out: out of the strip and the drag threshold beyond
        private fun isTornOut(e: MouseEvent, start: Point) =
          ProjectTabs.isDetachDrag(e.yOnScreen - start.y, this@ProjectTabStrip.height, DragSource.getDragThreshold())

        override fun mousePressed(e: MouseEvent) {
          menuShown = e.isPopupTrigger
          if (menuShown) {
            pressAt = null
            showMenu(tab, this@TabComponent, e.point)
            return
          }
          pressAt = if (SwingUtilities.isLeftMouseButton(e)) e.locationOnScreen else null
        }

        override fun mouseDragged(e: MouseEvent) {
          val start = pressAt ?: return
          val threshold = DragSource.getDragThreshold()
          if (!dragging && abs(e.xOnScreen - start.x) < threshold && abs(e.yOnScreen - start.y) < threshold) return
          if (!dragging) {
            dragging = true
            cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
          }
          if (isTornOut(e, start)) {
            if (dropGap != -1) {
              dropGap = -1
              this@ProjectTabStrip.repaint()
            }
          }
          else {
            dragTo(SwingUtilities.convertPoint(this@TabComponent, e.point, this@ProjectTabStrip).x)
          }
        }

        override fun mouseReleased(e: MouseEvent) {
          val start = pressAt
          pressAt = null
          if (dragging) {
            dragging = false
            cursor = Cursor.getDefaultCursor()
            if (start != null && isTornOut(e, start)) tearOut(tab, Point(e.xOnScreen - start.x, e.yOnScreen - start.y)) else drop(tab)
            return
          }
          if (e.isPopupTrigger && !menuShown) {
            showMenu(tab, this@TabComponent, e.point)
            return
          }
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
      }
      addMouseListener(mouse)
      addMouseMotionListener(mouse)
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
      // the one beside the current one, side by side: underlined like the current one, in the inactive color
      if (tab.isCurrent || tab.isSideBySide) {
        val underline = JBUI.CurrentTheme.EditorTabs.underlineHeight()
        g.color = if (tab.isCurrent) JBUI.CurrentTheme.EditorTabs.underlineColor() else JBUI.CurrentTheme.EditorTabs.inactiveUnderlineColor()
        g.fillRect(0, height - underline, width, underline)
      }
    }
  }
}
