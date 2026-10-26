// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.workspaces.core

import java.awt.Rectangle

/**
 * Where a window is. [bounds] are the normal (not maximized, not full screen) bounds; while [maximized] or
 * [fullScreen] they are the bounds to come back to.
 */
data class WindowState(val bounds: Rectangle, val maximized: Boolean, val fullScreen: Boolean)

interface StackedWindow {
  val state: WindowState
  val isActive: Boolean

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
class WindowStack {
  private val windows = ArrayList<StackedWindow>()
  private var pending = false

  var shared: WindowState? = null
    private set

  var front: StackedWindow? = null
    private set

  /** A new window; it is moved on top of the others unless [align] is false (the stack is switched off). */
  fun add(window: StackedWindow, align: Boolean = true) {
    if (contains(window)) return
    windows += window
    if (front == null) front = window
    val target = shared
    if (target == null) {
      if (!window.isInTransition) shared = window.state
    }
    else if (align) {
      alignBehind(window, target)
    }
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
    pending = false
    val now = leader.state
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
