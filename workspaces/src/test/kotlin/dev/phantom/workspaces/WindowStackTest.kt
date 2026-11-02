// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.workspaces

import dev.phantom.workspaces.core.StackedWindow
import dev.phantom.workspaces.core.Tiling
import dev.phantom.workspaces.core.WindowStack
import dev.phantom.workspaces.core.WindowState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Rectangle

class WindowStackTest {
  private val log = mutableListOf<String>()
  private val screen = Rectangle(0, 0, 1920, 1080)
  private val home = Rectangle(100, 100, 1200, 800)
  private val elsewhere = Rectangle(500, 50, 900, 700)

  /**
   * A project window as the OS treats it: maximized and full-screen windows report the screen as their bounds and keep
   * their normal bounds aside; entering or leaving full screen is asynchronous ([finishTransition]).
   */
  private inner class FakeWindow(
    val name: String,
    var normal: Rectangle,
    var maximized: Boolean = false,
    var fullScreen: Boolean = false,
  ) : StackedWindow {
    override var isActive: Boolean = false
    override var isInTransition: Boolean = false
    override val screen: Rectangle get() = this@WindowStackTest.screen
    val applied = mutableListOf<WindowState>()
    private var afterTransition: (() -> Unit)? = null

    override val state: WindowState
      get() = WindowState(if (maximized || fullScreen) screen else normal, maximized, fullScreen)

    override fun apply(state: WindowState) {
      log += "apply $name"
      applied += state
      if (state.fullScreen != fullScreen) {
        isInTransition = true
        if (!state.fullScreen) {
          // leaving: the window is out of full screen at once, the bounds settle when the animation ends
          fullScreen = false
          afterTransition = { windowed(state) }
        }
        else {
          afterTransition = { fullScreen = true }
        }
        return
      }
      if (!state.fullScreen) windowed(state)
    }

    private fun windowed(state: WindowState) {
      if (state.maximized) {
        maximized = true
      }
      else {
        maximized = false
        normal = state.bounds
      }
    }

    fun finishTransition() {
      isInTransition = false
      afterTransition?.invoke()
      afterTransition = null
    }

    override fun activate() {
      log += "activate $name"
    }

    override var isShown: Boolean = true

    override fun show(shown: Boolean) {
      log += (if (shown) "show " else "hide ") + name
      isShown = shown
    }
  }

  private var now = 0L
  private val stack = WindowStack { now }

  private fun window(name: String, bounds: Rectangle = home, maximized: Boolean = false, fullScreen: Boolean = false) =
    FakeWindow(name, bounds, maximized, fullScreen)

  /** [window] in front changes, the debounce timer fires. */
  private fun moveFront(window: FakeWindow, change: FakeWindow.() -> Unit) {
    window.change()
    stack.moved(window)
    stack.flush()
  }

  private fun stackOf(vararg windows: FakeWindow) {
    windows.forEach { stack.add(it) }
    stack.activated(windows.first())
    windows.first().isActive = true
    log.clear()
  }

  @Test
  fun `the first window sets where every project window goes`() {
    val a = window("a")
    stack.add(a)
    assertEquals(WindowState(home, maximized = false, fullScreen = false), stack.shared)
    assertTrue(a.applied.isEmpty())
  }

  @Test
  fun `a project that opens later opens exactly on top of the others`() {
    val a = window("a")
    val b = window("b", elsewhere)
    stack.add(a)
    stack.add(b)
    assertEquals(home, b.normal)
  }

  @Test
  fun `a window that opens while the stack is off is left where it is`() {
    val a = window("a")
    val b = window("b", elsewhere)
    stack.add(a)
    stack.add(b, align = false)
    assertEquals(elsewhere, b.normal)
  }

  @Test
  fun `moving or resizing the window in front moves the windows behind it`() {
    val a = window("a")
    val b = window("b")
    stackOf(a, b)
    moveFront(a) { normal = elsewhere }
    assertEquals(elsewhere, b.normal)
    assertEquals(elsewhere, stack.shared!!.bounds)
  }

  @Test
  fun `changes of a window behind are ignored, so syncing cannot echo back and forth`() {
    val a = window("a")
    val b = window("b")
    stackOf(a, b)
    b.normal = elsewhere
    assertFalse(stack.moved(b))
    stack.flush()
    assertEquals(home, a.normal)
    assertEquals(home, stack.shared!!.bounds)
    assertTrue(a.applied.isEmpty())
  }

  @Test
  fun `a window that is already in place is not touched`() {
    val a = window("a")
    val b = window("b")
    stackOf(a, b)
    moveFront(a) { normal = elsewhere }
    moveFront(a) {}
    moveFront(a) {}
    assertEquals(1, b.applied.size)
    assertTrue(a.applied.isEmpty())
  }

  @Test
  fun `maximizing the window in front maximizes the others`() {
    val a = window("a")
    val b = window("b")
    stackOf(a, b)
    moveFront(a) { maximized = true }
    assertTrue(b.maximized)
  }

  // finding 5
  @Test
  fun `maximizing keeps the normal size, so un-maximizing comes back to it`() {
    val a = window("a")
    val b = window("b")
    stackOf(a, b)
    moveFront(a) { maximized = true }
    assertEquals(WindowState(home, maximized = true, fullScreen = false), stack.shared)
    assertEquals(home, b.normal)
    stack.switchTo(b)
    assertTrue(b.maximized)
    assertEquals(home, b.normal)
  }

  @Test
  fun `full screen is not pushed to the windows behind, it follows on switch`() {
    val a = window("a")
    val b = window("b")
    stackOf(a, b)
    moveFront(a) { fullScreen = true }
    assertTrue(b.applied.isEmpty())
    // the shared bounds stay the bounds to come back to when leaving full screen
    assertEquals(WindowState(home, maximized = false, fullScreen = true), stack.shared)

    stack.switchTo(b)
    b.finishTransition()
    assertTrue(b.fullScreen)
    assertEquals(listOf("apply b", "activate b"), log)
  }

  // finding 1
  @Test
  fun `a project that opens while the others are full screen does not go full screen too`() {
    val a = window("a")
    stackOf(a)
    moveFront(a) { fullScreen = true }
    val b = window("b", elsewhere)
    stack.add(b)
    b.finishTransition()
    assertFalse(b.fullScreen)
    assertTrue(b.applied.none { it.fullScreen })
    assertEquals(home, b.normal)
  }

  // finding 1
  @Test
  fun `a window that reopens in full screen is not taken out of it`() {
    val a = window("a")
    stackOf(a)
    val b = window("b", fullScreen = true)
    stack.add(b)
    b.finishTransition()
    assertTrue(b.fullScreen)
    assertTrue(b.applied.isEmpty())
  }

  // finding 1
  @Test
  fun `a full screen window that comes to front without a switch stays full screen`() {
    val a = window("a")
    val b = window("b", fullScreen = true)
    stackOf(a, b)
    stack.activated(b)
    b.finishTransition()
    assertTrue(b.fullScreen)
    assertTrue(b.applied.isEmpty())
  }

  // finding 2
  @Test
  fun `bounds are not taken from a window while it leaves full screen`() {
    val a = window("a")
    val b = window("b", bounds = elsewhere, fullScreen = true)
    stackOf(a, b)
    stack.switchTo(b)
    b.isActive = true
    a.isActive = false
    assertTrue(b.isInTransition)
    // the OS restores b's old bounds during the animation, before b is put in place
    moveFront(b) { normal = elsewhere }
    assertEquals(home, stack.shared!!.bounds)
    assertEquals(home, a.normal)

    b.finishTransition()
    assertFalse(b.fullScreen)
    assertEquals(home, b.normal)
    moveFront(b) {}
    assertEquals(home, stack.shared!!.bounds)
    assertEquals(home, a.normal)
  }

  // finding 3
  @Test
  fun `turning the stack back on takes the window in front as it is now`() {
    val a = window("a")
    val b = window("b")
    stackOf(a, b)
    // stack off: a is moved, nothing follows
    a.normal = elsewhere
    stack.lead(a)
    assertEquals(elsewhere, stack.shared!!.bounds)
    assertEquals(elsewhere, b.normal)
    stack.switchTo(b)
    assertEquals(elsewhere, b.normal)
  }

  // finding 4
  @Test
  fun `a resize right before a switch is not lost`() {
    val a = window("a")
    val b = window("b")
    stackOf(a, b)
    a.normal = elsewhere
    stack.moved(a) // the debounce timer has not fired yet
    stack.switchTo(b)
    assertEquals(elsewhere, b.normal)
    assertEquals(elsewhere, a.normal)
    stack.flush() // the timer fires late
    assertEquals(elsewhere, a.normal)
  }

  // finding 4
  @Test
  fun `a resize right before another window comes to front is not lost`() {
    val a = window("a")
    val b = window("b")
    stackOf(a, b)
    a.normal = elsewhere
    stack.moved(a)
    a.isActive = false
    b.isActive = true
    stack.activated(b)
    stack.flush()
    assertEquals(elsewhere, b.normal)
    assertEquals(elsewhere, a.normal)
  }

  // finding 4
  @Test
  fun `the window in front leads even while the IDE is in the background`() {
    val a = window("a")
    val b = window("b")
    stackOf(a, b)
    a.isActive = false // e.g. moved by a window manager tool
    moveFront(a) { normal = elsewhere }
    assertEquals(elsewhere, b.normal)
    stack.activated(a)
    assertEquals(elsewhere, a.normal)
  }

  @Test
  fun `switching puts the target in place before bringing it to front`() {
    val a = window("a")
    val b = window("b")
    stackOf(a, b)
    b.normal = elsewhere // e.g. restored from its own saved bounds after the stack was off
    stack.switchTo(b)
    assertEquals(home, b.normal)
    assertEquals(listOf("apply b", "activate b"), log)
  }

  @Test
  fun `switching to a window that is in place only brings it to front`() {
    val a = window("a")
    val b = window("b")
    stackOf(a, b)
    stack.switchTo(b)
    assertEquals(listOf("activate b"), log)
  }

  @Test
  fun `a window brought to front some other way is put in place too`() {
    val a = window("a")
    val b = window("b")
    stackOf(a, b)
    b.normal = elsewhere
    stack.activated(b)
    assertEquals(home, b.normal)
  }

  // W2 (real IDE, S3): macOS makes a new project window key while it is shown, ~100 ms before its strip joins the stack,
  // so its WINDOW_ACTIVATED reaches no listener of ours; the user then moves the window they have in front
  @Test
  fun newWindowActivatedBeforeItJoinedLeadsItsMoves() {
    val a = window("a")
    val b = window("b", elsewhere)
    stackOf(a)
    a.isActive = false
    b.isActive = true // activated while shown, before the join
    stack.add(b)
    val moved = Rectangle(160, 140, 1000, 700)
    moveFront(b) { normal = moved }
    assertEquals(moved, a.normal)
    assertEquals(moved, stack.shared!!.bounds)
  }

  // W9 (real IDE, S3): after W2 the user leaves the new window and comes back to it (the OS activates it again)
  @Test
  fun comingBackToAMovedNewWindowLeavesItWhereItWasMoved() {
    val a = window("a")
    val b = window("b", elsewhere)
    stackOf(a)
    a.isActive = false
    b.isActive = true
    stack.add(b)
    val moved = Rectangle(160, 140, 1000, 700)
    moveFront(b) { normal = moved }
    stack.activated(b) // the user went to another app and came back to b
    assertEquals(moved, b.normal)
    assertEquals(moved, a.normal)
  }

  // W10 rework (s5 gate-1): a project saved maximized is shown maximized by the platform while the stack is not. The
  // platform zooms it only as it shows it, so it cannot be put in place before (IdeFrameImpl.isAboutToBeMaximized would
  // stay set and its tool windows never be laid out). When it joins, active, it leads but goes where the stack is: the
  // other windows are not maximized because one project was saved maximized
  @Test
  fun newWindowShownMaximizedWhileTheStackIsNotJoinsTheWindowedStack() {
    val a = window("a")
    val b = window("b", elsewhere, maximized = true)
    stackOf(a)
    a.isActive = false
    b.isActive = true
    stack.add(b)
    assertFalse(b.maximized)
    assertEquals(home, b.normal)
    assertFalse(a.maximized)
    assertEquals(home, a.normal)
    assertEquals(WindowState(home, maximized = false, fullScreen = false), stack.shared)
    assertSame(b, stack.front)
  }

  // a window put in place before it was shown (WindowStack.place) and active when it joins stays where it is
  @Test
  fun newWindowPlacedBeforeItWasShownIsNotMovedAgainWhenItJoins() {
    val a = window("a")
    val b = window("b", elsewhere)
    stackOf(a)
    stack.place(b)
    assertEquals(home, b.normal)
    val moves = b.applied.size
    a.isActive = false
    b.isActive = true
    stack.add(b)
    assertEquals(moves, b.applied.size)
    assertSame(b, stack.front)
  }

  // the platform's zoom is never undone before the window is shown (see above)
  @Test
  fun newWindowRestoredMaximizedIsNotPlacedBeforeItIsShown() {
    val a = window("a")
    val b = window("b", elsewhere, maximized = true)
    stackOf(a)
    stack.place(b)
    assertTrue(b.maximized)
    assertTrue(b.applied.isEmpty())
  }

  // W13 (real IDE, s5 attempt 2): the user puts the front window in full screen. macOS gives it its full-screen bounds
  // (the whole width, down to the bottom of the screen) as the animation starts, and the platform says it is in full
  // screen only when the animation ends, more than the 300 ms debounce later. Pushed as a move, those bounds made macOS
  // zoom every window behind
  @Test
  fun frontWindowEnteringFullScreenIsNotPushedBehindBeforeItSaysSo() {
    val a = window("a")
    val b = window("b")
    stackOf(b, a)
    val fullScreenBounds = Rectangle(0, 33, 1920, 1047)
    moveFront(b) { normal = fullScreenBounds } // the debounce fires, the platform still says not in full screen
    now += 300
    stack.flush()
    assertEquals(home, a.normal)
    assertFalse(a.maximized)
    assertTrue(stack.hasPendingMove)
    now += 400
    b.fullScreen = true // windowEnteredFullScreen
    stack.flush()
    assertEquals(home, a.normal)
    assertFalse(a.fullScreen)
    assertFalse(stack.hasPendingMove)
    assertEquals(WindowState(home, maximized = false, fullScreen = true), stack.shared)
  }

  // a window the user made as big as its screen, which never goes full screen, is followed once it has settled
  @Test
  fun frontWindowAsBigAsItsScreenIsFollowedOnceItSettles() {
    val a = window("a")
    val b = window("b")
    stackOf(b, a)
    val big = Rectangle(0, 33, 1920, 1047)
    moveFront(b) { normal = big }
    now += 2000
    stack.flush()
    assertEquals(big, a.normal)
    assertFalse(stack.hasPendingMove)
  }

  @Test
  fun `a closed window is no longer moved`() {
    val a = window("a")
    val b = window("b")
    stackOf(a, b)
    stack.remove(b)
    moveFront(a) { normal = elsewhere }
    assertEquals(home, b.normal)
  }

  @Test
  fun `in single mode a project that opens hides the window that was in front`() {
    stack.single = true
    val a = window("a")
    val b = window("b", elsewhere)
    stack.add(a)
    stack.add(b)
    assertSame(b, stack.front)
    assertFalse(a.isShown)
    assertTrue(b.isShown)
    assertEquals(home, b.normal)
  }

  @Test
  fun `in single mode switching shows the window in place before hiding the one it replaces`() {
    stack.single = true
    val a = window("a")
    val b = window("b")
    stack.add(a)
    stack.add(b)
    moveFront(b) { normal = elsewhere }
    log.clear()
    stack.switchTo(a)
    // the hidden window followed the move already, so it is only shown
    assertEquals(listOf("show a", "activate a", "hide b"), log)
    assertEquals(elsewhere, a.normal)
    assertSame(a, stack.front)
  }

  @Test
  fun `in single mode a hidden window switched to while the stack is full screen enters it once shown`() {
    stack.single = true
    val a = window("a")
    val b = window("b")
    stack.add(a)
    stack.add(b)
    moveFront(b) { fullScreen = true }
    log.clear()
    stack.switchTo(a)
    assertEquals(listOf("show a", "apply a", "activate a", "hide b"), log)
    assertTrue(a.isInTransition)
  }

  @Test
  fun `in single mode closing the window in front gives back the one in front before it`() {
    stack.single = true
    val a = window("a")
    val b = window("b")
    val c = window("c")
    stack.add(a)
    stack.add(b)
    stack.add(c)
    stack.switchTo(b)
    stack.switchTo(a)
    assertSame(b, stack.remove(a))
  }

  @Test
  fun `in single mode closing a hidden window shows nothing`() {
    stack.single = true
    val a = window("a")
    val b = window("b")
    stack.add(a)
    stack.add(b)
    assertEquals(null, stack.remove(a))
  }

  @Test
  fun `turning single mode off shows every window again`() {
    stack.single = true
    val a = window("a")
    val b = window("b")
    stack.add(a)
    stack.add(b)
    stack.single = false
    assertTrue(a.isShown)
    assertTrue(b.isShown)
  }

  // the OS puts a window it shows on top: the one in front is brought back over them, so the project on screen stays
  @Test
  fun `turning single mode off keeps the window in front on top`() {
    stack.single = true
    val a = window("a")
    val b = window("b")
    stack.add(a)
    stack.add(b)
    log.clear()
    stack.single = false
    assertEquals(listOf("show a", "activate b"), log)
  }

  // the stack is turned back on while a window other than the last one in front is active (the stack does not follow
  // activations while off): turning single on hid that window, and it must be the one left on screen
  @Test
  fun `turning the stack back on in single mode shows the window that leads and hides the others`() {
    val a = window("a")
    val b = window("b")
    stack.add(a)
    stack.add(b)
    stack.switchTo(b)
    stack.single = true
    assertFalse(a.isShown)
    stack.lead(a)
    assertSame(a, stack.front)
    assertTrue(a.isShown)
    assertFalse(b.isShown)
  }

  @Test
  fun `a window activated some other way comes to front and hides the others in single mode`() {
    stack.single = true
    val a = window("a")
    val b = window("b")
    stack.add(a)
    stack.add(b)
    a.isShown = true
    stack.activated(a)
    assertSame(a, stack.front)
    assertFalse(b.isShown)
  }

  @Test
  fun `a window detached from the stack is never hidden, moved or resized by it again`() {
    stack.single = true
    val a = window("a")
    val b = window("b")
    val c = window("c")
    stack.add(a)
    stack.add(b)
    stack.add(c)
    stack.remove(b)
    b.show(true)
    b.normal = elsewhere
    log.clear()
    stack.switchTo(a)
    moveFront(a) { normal = Rectangle(0, 0, 640, 480) }
    stack.activated(b)
    assertTrue(b.isShown)
    assertEquals(elsewhere, b.normal)
    assertTrue(log.none { it.endsWith(" b") })
    assertSame(a, stack.front)
  }

  @Test
  fun `a detached window is put a step right and down of the stack`() {
    assertEquals(Rectangle(140, 140, 1200, 800), WindowStack.cascaded(home, 40, screen))
    assertEquals(Rectangle(180, 180, 1200, 800), WindowStack.cascaded(home, 80, screen))
  }

  @Test
  fun `a detached window that would leave the screen shrinks to fit, then moves back onto it`() {
    assertEquals(Rectangle(40, 40, 1880, 1040), WindowStack.cascaded(screen, 40, screen))
    val small = Rectangle(0, 0, 800, 600)
    assertEquals(Rectangle(1520, 780, 400, 300), WindowStack.cascaded(Rectangle(1500, 900, 800, 600), 40, screen))
    assertEquals(Rectangle(140, 140, 800, 600), WindowStack.cascaded(Rectangle(100, 100, 800, 600), 40, null))
    assertEquals(small.size, WindowStack.cascaded(small, 40, screen).size)
  }

  // side by side: home is (100, 100, 1200, 800), so the halves are 600 wide and meet at x 700
  private val leftHalf = Rectangle(100, 100, 600, 800)
  private val rightHalf = Rectangle(700, 100, 600, 800)

  private fun splitOf(vararg windows: FakeWindow): Triple<FakeWindow, FakeWindow, List<FakeWindow>> {
    stack.single = true
    windows.forEach { stack.add(it) }
    stack.switchTo(windows[0])
    assertTrue(stack.split(windows[0], windows[1]))
    log.clear()
    return Triple(windows[0], windows[1], windows.drop(2))
  }

  /** [window] of the pair changes, the debounce timer fires. */
  private fun moveSplit(window: FakeWindow, change: FakeWindow.() -> Unit) {
    window.change()
    assertTrue(stack.moved(window))
    stack.flush()
  }

  @Test
  fun `tiling halves the area and a seam moved by either inner edge gives the other the rest`() {
    val t = Tiling.halves(home)
    assertEquals(leftHalf, t.left)
    assertEquals(rightHalf, t.right)
    // the left one made 200 wider from its right edge
    assertEquals(900, t.after(isLeft = true, Rectangle(100, 100, 800, 800)).seam)
    // the right one made 100 wider from its left edge
    assertEquals(600, t.after(isLeft = false, Rectangle(600, 100, 700, 800)).seam)
    // an outer edge or the height: the seam stays
    assertEquals(700, t.after(isLeft = false, Rectangle(700, 100, 900, 800)).seam)
    assertEquals(700, t.after(isLeft = true, Rectangle(100, 100, 600, 500)).seam)
  }

  @Test
  fun `tiling keeps a sixth of the area on either side of the seam`() {
    val t = Tiling.halves(home)
    assertEquals(100 + 200, t.after(isLeft = true, Rectangle(100, 100, 50, 800)).seam)
    assertEquals(1300 - 200, t.after(isLeft = false, Rectangle(1250, 100, 50, 800)).seam)
  }

  @Test
  fun `tiling moves the pair with a window moved without resizing`() {
    val moved = Tiling.halves(home).after(isLeft = false, Rectangle(750, 130, 600, 800))
    assertEquals(Rectangle(150, 130, 1200, 800), moved.area)
    assertEquals(750, moved.seam)
  }

  @Test
  fun `side by side puts the current window on the left half and the other on the right, both shown`() {
    stack.single = true
    val a = window("a")
    val b = window("b")
    val c = window("c")
    stack.add(a)
    stack.add(b)
    stack.add(c)
    stack.switchTo(a)
    assertFalse(b.isShown)
    assertTrue(stack.split(a, b))
    assertEquals(leftHalf, a.normal)
    assertEquals(rightHalf, b.normal)
    assertTrue(a.isShown)
    assertTrue(b.isShown)
    assertFalse(c.isShown)
    assertSame(a, stack.front)
    assertEquals(a to b, stack.splitPair)
    // the stack's place is kept for when it is one window again
    assertEquals(home, stack.shared!!.bounds)
  }

  @Test
  fun `side by side from a maximized stack tiles the screen, not maximized`() {
    val a = window("a")
    val b = window("b")
    stackOf(a, b)
    moveFront(a) { maximized = true }
    assertTrue(stack.split(a, b))
    assertFalse(a.maximized)
    assertEquals(Rectangle(0, 0, 960, 1080), a.normal)
    assertEquals(Rectangle(960, 0, 960, 1080), b.normal)
  }

  @Test
  fun `no side by side in full screen`() {
    val a = window("a")
    val b = window("b")
    stackOf(a, b)
    moveFront(a) { fullScreen = true }
    assertFalse(stack.split(a, b))
    assertEquals(null, stack.splitPair)
  }

  @Test
  fun `resizing the inner edge of either window resizes the other one`() {
    val (a, b) = splitOf(window("a"), window("b"))
    moveSplit(a) { normal = Rectangle(100, 100, 800, 800) }
    assertEquals(Rectangle(900, 100, 400, 800), b.normal)
    moveSplit(b) { normal = Rectangle(500, 100, 800, 800) }
    assertEquals(Rectangle(100, 100, 400, 800), a.normal)
  }

  @Test
  fun `a window put on its tile moving too does not move the pair again`() {
    val (a, b) = splitOf(window("a"), window("b"))
    moveSplit(a) { normal = Rectangle(100, 100, 800, 800) }
    log.clear()
    // the event of the window just put in place
    stack.moved(b)
    stack.flush()
    assertTrue(log.isEmpty())
    assertFalse(stack.hasPendingMove)
  }

  @Test
  fun `an outer edge resized goes back to the pair's area`() {
    val (a, b) = splitOf(window("a"), window("b"))
    moveSplit(b) { normal = Rectangle(700, 100, 900, 600) }
    assertEquals(rightHalf, b.normal)
    assertEquals(leftHalf, a.normal)
  }

  @Test
  fun `moving either window moves the pair, and the stack with it`() {
    val (a, b) = splitOf(window("a"), window("b"), window("c"))
    moveSplit(a) { normal = Rectangle(150, 130, 600, 800) }
    assertEquals(Rectangle(750, 130, 600, 800), b.normal)
    assertEquals(Rectangle(150, 130, 1200, 800), stack.shared!!.bounds)
  }

  @Test
  fun `clicking into the other window of the pair keeps them side by side`() {
    val (a, b) = splitOf(window("a"), window("b"))
    stack.activated(b)
    assertSame(b, stack.front)
    assertTrue(a.isShown)
    assertEquals(leftHalf, a.normal)
    assertEquals(rightHalf, b.normal)
    stack.switchTo(a)
    assertTrue(b.isShown)
    assertEquals(a to b, stack.splitPair)
  }

  @Test
  fun `switching to a third window ends side by side`() {
    val (a, b, rest) = splitOf(window("a"), window("b"), window("c"))
    val c = rest.single()
    stack.switchTo(c)
    assertEquals(null, stack.splitPair)
    assertSame(c, stack.front)
    assertFalse(a.isShown)
    assertFalse(b.isShown)
    // behind, the pair is back where the stack is
    assertEquals(home, a.normal)
    assertEquals(home, b.normal)
  }

  @Test
  fun `exiting side by side gives the window in front the whole place again`() {
    val (a, b) = splitOf(window("a"), window("b"))
    stack.activated(b)
    stack.unsplit()
    assertEquals(null, stack.splitPair)
    assertSame(b, stack.front)
    assertEquals(home, b.normal)
    assertFalse(a.isShown)
    assertEquals(home, a.normal)
  }

  @Test
  fun `exiting side by side from one window of the pair keeps that one`() {
    val (a, b) = splitOf(window("a"), window("b"))
    stack.activated(b)
    stack.unsplit(a)
    assertSame(a, stack.front)
    assertEquals(home, a.normal)
    assertFalse(b.isShown)
  }

  @Test
  fun `maximizing a window side by side ends it and the window leads maximized`() {
    val (a, b) = splitOf(window("a"), window("b"))
    moveSplit(b) { maximized = true }
    assertEquals(null, stack.splitPair)
    assertSame(b, stack.front)
    assertTrue(stack.shared!!.maximized)
    assertFalse(a.isShown)
    assertTrue(a.maximized)
  }

  @Test
  fun `closing a window side by side shows the other one alone in their place`() {
    val (a, b, rest) = splitOf(window("a"), window("b"), window("c"))
    assertSame(b, stack.remove(a))
    assertEquals(null, stack.splitPair)
    stack.switchTo(b)
    assertEquals(home, b.normal)
    assertFalse(rest.single().isShown)
  }

  @Test
  fun `a project that opens ends side by side`() {
    val (a, b) = splitOf(window("a"), window("b"))
    val c = window("c", elsewhere)
    stack.add(c)
    assertEquals(null, stack.splitPair)
    assertSame(c, stack.front)
    assertEquals(home, c.normal)
    assertFalse(a.isShown)
    assertFalse(b.isShown)
  }
}
