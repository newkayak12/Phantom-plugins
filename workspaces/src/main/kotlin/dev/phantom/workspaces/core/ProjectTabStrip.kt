// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.workspaces.core

import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.IdeRootPaneNorthExtension
import com.intellij.openapi.wm.impl.ProjectFrameHelper
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.event.WindowStateListener
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * Adds a [ProjectTabStrip] to every project frame: how a project window joins the open project windows. It is never shown;
 * the Projects and Files list shows the open projects instead of a strip of tabs.
 */
internal class ProjectTabsNorthExtension : IdeRootPaneNorthExtension {
  override val key: String get() = ProjectTabs.EXTENSION_KEY

  override fun createComponent(project: Project, isDocked: Boolean): JComponent? =
    if (isDocked) null else ProjectTabStrip(project)
}

private const val SYNC_DELAY_MS = 300

/**
 * The window of [project] among the open project windows: it joins them when added to its frame, leaves when removed, and
 * watches its frame for [WindowStack] (activation, and moves/resizes, debounced). [tabs] are the project tabs as this
 * window sees them (order, its own the current one, side by side); the Projects and Files list ([ProjectsList]) is what
 * shows the open projects, so the strip itself stays hidden and empty.
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

  init {
    isVisible = false
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

  internal fun display(tabs: List<ProjectTab>) {
    this.tabs = tabs
  }
}
