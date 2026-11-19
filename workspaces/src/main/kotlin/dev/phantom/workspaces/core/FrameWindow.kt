// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.workspaces.core

import java.awt.Frame
import javax.swing.JFrame

/** Full screen of a project frame. [toggle] is asynchronous; [done] runs on the EDT when it is over. */
interface FullScreenControl {
  val isInFullScreen: Boolean
  fun toggle(state: Boolean, done: () -> Unit)
}

/** A project window for [WindowStack]. Without [fullScreen] the frame's full screen is left alone. */
class FrameWindow(
  val frame: JFrame,
  private val fullScreen: FullScreenControl?,
  private val bringToFront: () -> Unit = { frame.toFront() },
) : StackedWindow {
  override var isInTransition: Boolean = false
    private set

  override val state: WindowState
    get() = WindowState(
      bounds = frame.bounds,
      maximized = frame.extendedState and Frame.MAXIMIZED_BOTH == Frame.MAXIMIZED_BOTH,
      fullScreen = fullScreen?.isInFullScreen == true,
    )

  override val isActive: Boolean get() = frame.isActive

  override val screen: java.awt.Rectangle? get() = frame.graphicsConfiguration?.bounds

  override fun apply(state: WindowState) {
    val control = fullScreen
    if (control != null && state.fullScreen != control.isInFullScreen) {
      isInTransition = true
      control.toggle(state.fullScreen) {
        isInTransition = false
        if (!state.fullScreen) applyWindowed(state)
      }
      return
    }
    if (!state.fullScreen) applyWindowed(state)
  }

  /** Maximizing leaves the normal bounds as they are, so un-maximizing comes back to them. */
  private fun applyWindowed(state: WindowState) {
    val maximized = frame.extendedState and Frame.MAXIMIZED_BOTH == Frame.MAXIMIZED_BOTH
    if (state.maximized) {
      if (!maximized) frame.extendedState = frame.extendedState or Frame.MAXIMIZED_BOTH
      return
    }
    if (maximized) frame.extendedState = frame.extendedState and Frame.MAXIMIZED_BOTH.inv()
    frame.bounds = state.bounds
  }

  override fun activate() {
    bringToFront()
  }

  override val isShown: Boolean get() = frame.isVisible

  override fun show(shown: Boolean) {
    frame.isVisible = shown
  }
}
