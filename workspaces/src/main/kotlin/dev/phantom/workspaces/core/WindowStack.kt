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

/**
 * Two windows side by side in [area]: the left one up to [seam], the right one from it. Pure, so the bounds math is
 * tested as is.
 */
data class Tiling(val area: Rectangle, val seam: Int) {
  val left: Rectangle get() = Rectangle(area.x, area.y, seam - area.x, area.height)
  val right: Rectangle get() = Rectangle(seam, area.y, area.x + area.width - seam, area.height)

  /**
   * The tiling after the left (or right) window became [now]. Moved without changing size, the pair moves with it;
   * resized, the seam follows its inner edge (kept a sixth of the area from either side) and the outer edges stay.
   */
  fun after(isLeft: Boolean, now: Rectangle): Tiling {
    val old = if (isLeft) left else right
    if (now.width == old.width && now.height == old.height) {
      val dx = now.x - old.x
      return Tiling(Rectangle(area).apply { translate(dx, now.y - old.y) }, seam + dx)
    }
    val edge = if (isLeft) now.x + now.width else now.x
    val min = area.width / 6
    return copy(seam = edge.coerceIn(area.x + min, area.x + area.width - min))
  }

  companion object {
    fun halves(area: Rectangle): Tiling = Tiling(Rectangle(area), area.x + area.width / 2)
  }
}

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

  /** Whether the window is on screen at all ([WindowStack.single] hides the windows behind). */
  val isShown: Boolean get() = true
  fun show(shown: Boolean) {}
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
 *
 * While [single], only the window in front is shown and the others are hidden, so there is one window on screen (one in
 * the Dock menu, Mission Control and the window switcher) whose project changes like a tab. Windows are kept in the
 * order they came to front, so closing the window in front shows the one in front before it.
 *
 * [split] puts two windows side by side over the stack's area, both shown; moving or resizing either keeps the pair
 * tiling it ([Tiling.after]). Switching to a third window, [unsplit], maximizing or full screen of either, or closing
 * either ends it; the stack is then one window again, at [shared].
 */
class WindowStack(private val clock: () -> Long = System::currentTimeMillis) {
  private val windows = ArrayList<StackedWindow>()
  private var pending = false
  private var suspectedSince: Long? = null

  private class Split(val left: StackedWindow, val right: StackedWindow, var tiling: Tiling) {
    fun other(window: StackedWindow): StackedWindow = if (window === left) right else left
  }

  private var split: Split? = null

  // the window of the pair that moved last
  private var mover: StackedWindow? = null

  /** The windows side by side, left first, or null. */
  val splitPair: Pair<StackedWindow, StackedWindow>? get() = split?.let { it.left to it.right }

  val tiling: Tiling? get() = split?.tiling

  fun isInSplit(window: StackedWindow): Boolean = split.let { it != null && (it.left === window || it.right === window) }

  var shared: WindowState? = null
    private set

  var front: StackedWindow? = null
    private set

  /**
   * Only the window in front is shown. Turning it off shows every window again; the OS puts a window it shows on top, so
   * the one in front is brought back over them.
   */
  var single: Boolean = false
    set(value) {
      field = value
      if (value) {
        hideBehind()
        return
      }
      val hidden = windows.filter { !it.isShown }
      if (hidden.isEmpty()) return
      hidden.forEach { it.show(true) }
      front?.activate()
    }

  /** A move of the window in front is still to be pushed: [flush] again later. */
  val hasPendingMove: Boolean get() = pending

  /**
   * A new window; it is moved on top of the others unless [align] is false (the stack is switched off). A window that is
   * already active when it joins leads from then on: the OS makes a new window key while showing it, before it can join,
   * so no activation follows. It still goes where the stack is, maximized or not, so one project saved maximized does not
   * maximize the others. Normally [place] has already put it there before it was shown. While [single], a new window on
   * screen (a project just opened) comes to front even when it is not active yet, and the others are hidden.
   */
  fun add(window: StackedWindow, align: Boolean = true) {
    if (contains(window)) return
    windows += window
    var ended = emptyList<StackedWindow>()
    if (front == null || window.isActive || single && window.isShown) {
      toFront(window)
      ended = endSplit()
    }
    val target = shared
    if (target == null) {
      if (!window.isInTransition) shared = window.state
    }
    else if (align) {
      alignBehind(window, target)
    }
    if (single) hideBehind()
    alignEnded(ended)
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

  /**
   * [window] is gone. Returns the window to show in its place when it was in front while [single] (the one in front
   * before it), for the caller to [switchTo] once the closing is over; null otherwise.
   */
  fun remove(window: StackedWindow): StackedWindow? {
    // one of a pair side by side: the other one is shown alone in their place
    val partner = split?.takeIf { isInSplit(window) }?.other(window)
    if (partner != null) endSplit()
    windows.removeIf { it === window }
    if (front !== window) return partner
    front = null
    pending = false
    return partner ?: if (single) windows.lastOrNull() else null
  }

  /** [window] moved, resized, maximized, or entered or left full screen. True when it leads and [flush] should follow. */
  fun moved(window: StackedWindow): Boolean {
    if (isInSplit(window)) {
      mover = window
      pending = true
      return true
    }
    if (window !== front) return false
    pending = true
    return true
  }

  /** Takes the pending move of the window in front and pushes it to the windows behind. */
  fun flush() {
    if (!pending) return
    split?.let {
      flushSplit(it)
      return
    }
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
    toFront(window)
    if (isInSplit(window)) return
    val ended = endSplit()
    val target = shared
    if (target == null) shared = window.state else alignBehind(window, target)
    if (single) hideBehind()
    alignEnded(ended)
  }

  /**
   * Puts [window] in place, full screen included, then brings it to front. A hidden window is put in place while hidden,
   * shown (full screen is entered only once it is on screen), and the window it replaces is hidden only after that, so
   * nothing behind shows in between.
   */
  fun switchTo(window: StackedWindow) {
    flush()
    if (contains(window)) toFront(window)
    if (isInSplit(window)) {
      window.activate()
      return
    }
    val ended = endSplit()
    val target = shared
    if (!window.isShown) {
      if (target != null) alignBehind(window, target)
      window.show(true)
    }
    if (target != null && !window.isInTransition && !inPlace(window.state, target)) window.apply(target)
    window.activate()
    if (single) hideBehind()
    alignEnded(ended)
  }

  /**
   * Puts [left] and [right] side by side over the stack's area (the screen's when it is maximized), both shown and not
   * maximized, [left] in front. Ends a split of other windows. False when either is not in the stack, is in full screen
   * or is entering or leaving it, or the stack is in full screen.
   */
  fun split(left: StackedWindow, right: StackedWindow): Boolean {
    flush()
    if (left === right || !contains(left) || !contains(right)) return false
    val target = shared ?: return false
    if (target.fullScreen || listOf(left, right).any { it.isInTransition || it.state.fullScreen }) return false
    val ended = endSplit()
    val area = if (left.state.maximized) left.state.bounds else target.bounds
    val tiling = Tiling.halves(area)
    split = Split(left, right, tiling)
    tile(left, tiling.left)
    tile(right, tiling.right)
    if (!right.isShown) right.show(true)
    if (!left.isShown) left.show(true)
    toFront(right)
    toFront(left)
    left.activate()
    if (single) hideBehind()
    alignEnded(ended)
    return true
  }

  /**
   * Ends side by side: [keep] (the window of the pair in front when null or not in the pair) takes the stack's place
   * again, the other goes behind it.
   */
  fun unsplit(keep: StackedWindow? = null) {
    flush()
    val ended = endSplit()
    if (ended.isEmpty()) return
    val keep = listOf(keep, front).firstOrNull { w -> ended.any { it === w } } ?: ended.first()
    toFront(keep)
    val target = shared
    if (target != null && !keep.isInTransition && !inPlace(keep.state, target)) keep.apply(target)
    if (single) hideBehind()
    alignEnded(ended)
  }

  /**
   * A pending move of the pair side by side. The window off its tile (the one that moved last first) sets the tiling,
   * and the windows are put on their tiles; a window put there moves too, but is then on its tile, so nothing echoes.
   * Maximizing or full screen of either ends side by side, and that window leads as the window in front.
   */
  private fun flushSplit(split: Split) {
    val pair = listOfNotNull(mover?.takeIf(::isInSplit)) + listOf(split.left, split.right)
    if (pair.any { it.isInTransition }) return
    val zoomed = pair.firstOrNull { it.state.let { s -> s.maximized || s.fullScreen } }
    if (zoomed != null) {
      endSplit()
      toFront(zoomed)
      flush()
      if (single) hideBehind()
      return
    }
    val window = pair.firstOrNull { it.state.bounds != tileOf(split, it) }
    if (window != null && mayBeEnteringFullScreen(window, window.state)) {
      val since = suspectedSince ?: clock().also { suspectedSince = it }
      if (clock() - since < FULL_SCREEN_SETTLE_MS) return
    }
    suspectedSince = null
    pending = false
    mover = null
    if (window == null) return
    val next = split.tiling.after(window === split.left, window.state.bounds)
    split.tiling = next
    tile(split.left, next.left)
    tile(split.right, next.right)
    shared?.let { if (!it.maximized && !it.fullScreen) shared = it.copy(bounds = Rectangle(next.area)) }
  }

  private fun tileOf(split: Split, window: StackedWindow): Rectangle =
    if (window === split.left) split.tiling.left else split.tiling.right

  private fun tile(window: StackedWindow, bounds: Rectangle) {
    val state = window.state
    if (state.maximized || state.fullScreen || state.bounds != bounds) window.apply(WindowState(bounds, maximized = false, fullScreen = false))
  }

  /** Clears side by side; returns the pair, for [alignEnded] once the stack is one window again. */
  private fun endSplit(): List<StackedWindow> {
    val ended = split ?: return emptyList()
    split = null
    mover = null
    return listOf(ended.left, ended.right)
  }

  /** The windows of an ended pair that are not in front go back to where the stack is, behind it. */
  private fun alignEnded(ended: List<StackedWindow>) {
    val target = shared ?: return
    for (window in ended) {
      if (window !== front && contains(window)) alignBehind(window, target)
    }
  }

  /**
   * The stack is switched back on: [window] in front leads as it is now, the others follow. While [single] it is the one
   * shown: turning single on hid every window but the one in front before, which the stack stops following while off.
   */
  fun lead(window: StackedWindow) {
    if (!contains(window)) return
    val ended = endSplit()
    toFront(window)
    if (!window.isShown) window.show(true)
    pending = true
    flush()
    if (single) hideBehind()
    alignEnded(ended)
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

  private fun toFront(window: StackedWindow) {
    front = window
    windows.removeIf { it === window }
    windows += window
  }

  private fun hideBehind() {
    val shown = front ?: return
    for (other in windows) {
      if (other !== shown && other.isShown && !isInSplit(other)) other.show(false)
    }
  }

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

  companion object {
    /** How far a window detached from the stack is put from it, per detached window, so it is seen as separate. */
    const val DETACH_STEP: Int = 40

    /**
     * [bounds] moved [step] right and down, kept on [screen]: shrunk to fit (not below half its size), then moved back
     * onto it if it still does not fit.
     */
    fun cascaded(bounds: Rectangle, step: Int, screen: Rectangle?): Rectangle {
      val r = Rectangle(bounds.x + step, bounds.y + step, bounds.width, bounds.height)
      if (screen == null) return r
      r.width = minOf(r.width, screen.x + screen.width - r.x).coerceAtLeast(bounds.width / 2)
      r.height = minOf(r.height, screen.y + screen.height - r.y).coerceAtLeast(bounds.height / 2)
      r.x = minOf(r.x, screen.x + screen.width - r.width).coerceAtLeast(screen.x)
      r.y = minOf(r.y, screen.y + screen.height - r.height).coerceAtLeast(screen.y)
      return r
    }
  }
}
