// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.workspaces

import dev.phantom.workspaces.core.StackedWindow
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
}
