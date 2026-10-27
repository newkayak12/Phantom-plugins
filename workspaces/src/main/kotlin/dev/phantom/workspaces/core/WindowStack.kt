// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.workspaces.core

import java.awt.Rectangle

/** How long bounds that look like full screen wait for the window to report it (the animation takes about 0.5-1 s). */
internal const val FULL_SCREEN_SETTLE_MS = 1500L

/**
 * Where a window is. [bounds] are the normal (not maximized, not full screen) bounds; while [maximized] or
 * [fullScreen] they are the bounds to come back to.
 */
data class WindowState(val bounds: Rectangle, val maximized: Boolean, val fullScreen: Boolean)

interface StackedWindow {
  val state: WindowState
  val isActive: Boolean

  /** The whole screen the window is on (not less the menu bar or Dock), or null when not known. */
  val screen: Rectangle? get() = null

  /** Entering or leaving full screen, which is asynchronous; its bounds mean nothing until it is over. */
  val isInTransition: Boolean get() = false

  /** Moves the window to [state]. Maximizing keeps the normal bounds; leaving full screen sets bounds once it is over. */
  fun apply(state: WindowState)
  fun activate()
}

/**
 * Keeps every project window exactly on top of each other, so the windows look like one window whose content changes.
 *
 * The window in [front] (the last one brought to front, even while the IDE is in the background) leads: its moves and
 * resizes, recorded by [moved] when they happen and applied by [flush] (debounced by the caller), become the [shared]
 * state and are pushed to the windows behind. Moves of windows behind are ignored and windows already in place are not
 * touched, so pushing cannot echo back. A pending move is flushed before another window comes to front.
 *
 * Full screen is only ever entered or left by [switchTo]: it is never pushed to the windows behind (on macOS every
 * full-screen window gets its own Space), and a window in full screen is left in it until it is switched to.
 */
class WindowStack(private val clock: () -> Long = System::currentTimeMillis) {
  private val windows = ArrayList<StackedWindow>()
  private var pending = false
  private var suspectedSince: Long? = null

  var shared: WindowState? = null
    private set

  var front: StackedWindow? = null
    private set

  /** A move of the window in front is still to be pushed: [flush] again later. */
  val hasPendingMove: Boolean get() = pending

  /**
   * A new window; it is moved on top of the others unless [align] is false (the stack is switched off). A window that is
   * already active when it joins leads from then on: the OS makes a new window key while showing it, before it can join,
   * so no activation follows. It still goes where the stack is, maximized or not, so one project saved maximized does not
   * maximize the others. Normally [place] has already put it there before it was shown.
   */
  fun add(window: StackedWindow, align: Boolean = true) {
    if (contains(window)) return
    windows += window
    if (front == null || window.isActive) front = window
    val target = shared
    if (target == null) {
      if (!window.isInTransition) shared = window.state
    }
    else if (align) {
      alignBehind(window, target)
    }
  }

  /**
   * A window about to be shown that will join: put where the stack is, so it is not first seen somewhere else. A window
   * restored maximized is left as it is: the platform zooms it only as it shows it and lays out its tool windows only
   * after that zoom (IdeFrameImpl.isAboutToBeMaximized, ToolWindowPane.setWeight), which never comes if it is
   * un-maximized before it is shown. It goes where the stack is when it joins ([add]).
   */
  fun place(window: StackedWindow) {
    if (windows.isEmpty() || contains(window)) return
    flush()
    val target = shared ?: return
    if (window.state.maximized) return
    alignBehind(window, target)
  }

  fun remove(window: StackedWindow) {
    windows.removeIf { it === window }
    if (front === window) {
      front = null
      pending = false
    }
  }

  /** [window] moved, resized, maximized, or entered or left full screen. True when it leads and [flush] should follow. */
  fun moved(window: StackedWindow): Boolean {
    if (window !== front) return false
    pending = true
    return true
  }

  /** Takes the pending move of the window in front and pushes it to the windows behind. */
  fun flush() {
    if (!pending) return
    val leader = front ?: return
    if (leader.isInTransition) return // stays pending; taken once the window has settled
    val now = leader.state
    if (mayBeEnteringFullScreen(leader, now)) {
      val since = suspectedSince ?: clock().also { suspectedSince = it }
      if (clock() - since < FULL_SCREEN_SETTLE_MS) return // stays pending, flushed again later
    }
    suspectedSince = null
    pending = false
    val previous = shared
    val next = when {
      previous == null -> now
      now.fullScreen -> previous.copy(fullScreen = true)
      now.maximized -> previous.copy(maximized = true, fullScreen = false)
      else -> now
    }
    shared = next
    if (next.fullScreen) return
    for (other in windows) {
      if (other !== leader) alignBehind(other, next)
    }
  }

  /** [window] came to front some other way than [switchTo] (Window menu, next project window, Dock, a click). */
  fun activated(window: StackedWindow) {
    flush()
    if (!contains(window)) return
    front = window
    val target = shared
    if (target == null) shared = window.state else alignBehind(window, target)
  }

  /** Puts [window] in place, full screen included, then brings it to front. */
  fun switchTo(window: StackedWindow) {
    flush()
    if (contains(window)) front = window
    val target = shared
    if (target != null && !window.isInTransition && !inPlace(window.state, target)) window.apply(target)
    window.activate()
  }

  /** The stack is switched back on: [window] in front leads as it is now, the others follow. */
  fun lead(window: StackedWindow) {
    if (!contains(window)) return
    front = window
    pending = true
    flush()
  }

  /**
   * macOS gives a window going full screen its full-screen bounds (the whole width of the screen, down to its bottom) as
   * the animation starts, and the platform reports full screen only as it ends (IdeFrame.isInFullScreen), well after the
   * debounce. Such bounds of a window that is neither maximized nor in full screen are not pushed until it says it is in
   * full screen or [FULL_SCREEN_SETTLE_MS] has passed (the user made it that big).
   */
  private fun mayBeEnteringFullScreen(window: StackedWindow, state: WindowState): Boolean {
    if (state.fullScreen || state.maximized) return false
    val screen = window.screen ?: return false
    val b = state.bounds
    return b.x <= screen.x && b.x + b.width >= screen.x + screen.width && b.y + b.height >= screen.y + screen.height
  }

  private fun contains(window: StackedWindow): Boolean = windows.any { it === window }

  /** Puts a window that is not being switched to in place: never into or out of full screen. */
  private fun alignBehind(window: StackedWindow, target: WindowState) {
    if (window.isInTransition || window.state.fullScreen) return
    val windowed = target.copy(fullScreen = false)
    if (!inPlace(window.state, windowed)) window.apply(windowed)
  }

  private fun inPlace(state: WindowState, target: WindowState): Boolean =
    state.fullScreen == target.fullScreen &&
    state.maximized == target.maximized &&
    (state.fullScreen || state.maximized || state.bounds == target.bounds)
}
