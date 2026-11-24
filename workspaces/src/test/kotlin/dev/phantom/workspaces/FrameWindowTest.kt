// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.workspaces

import com.intellij.openapi.project.Project
import dev.phantom.workspaces.core.FrameWindow
import dev.phantom.workspaces.core.FullScreenControl
import dev.phantom.workspaces.core.ProjectTabStrip
import dev.phantom.workspaces.core.WindowStack
import dev.phantom.workspaces.core.WindowState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.lang.reflect.Proxy
import java.awt.Rectangle
import java.awt.Toolkit
import javax.swing.JFrame
import javax.swing.SwingUtilities

/** The stack on real AWT frames. Needs a screen; skipped when the tests run headless. */
class FrameWindowTest {
  private val frames = mutableListOf<JFrame>()

  @Before
  fun needsScreen() {
    assumeFalse("no screen", GraphicsEnvironment.isHeadless())
  }

  @After
  fun disposeFrames() {
    SwingUtilities.invokeAndWait { frames.forEach(JFrame::dispose) }
  }

  private fun frame(bounds: Rectangle): JFrame = JFrame().also {
    it.bounds = bounds
    frames += it
  }

  @Test
  fun `a second frame takes the bounds of the first`() {
    SwingUtilities.invokeAndWait {
      val a = FrameWindow(frame(Rectangle(100, 100, 800, 600)), null)
      val b = FrameWindow(frame(Rectangle(300, 200, 500, 400)), null)
      val stack = WindowStack()
      stack.add(a)
      stack.add(b)
      assertEquals(Rectangle(100, 100, 800, 600), frames[1].bounds)
    }
  }

  @Test
  fun `state reads bounds and maximized from the frame`() {
    SwingUtilities.invokeAndWait {
      val window = FrameWindow(frame(Rectangle(120, 130, 640, 480)), null)
      assertEquals(WindowState(Rectangle(120, 130, 640, 480), maximized = false, fullScreen = false), window.state)
      assertFalse(window.isActive)
    }
  }

  // finding 5
  @Test
  fun `maximizing a frame keeps its normal bounds`() {
    SwingUtilities.invokeAndWait {
      val frame = frame(Rectangle(120, 130, 640, 480))
      FrameWindow(frame, null).apply(WindowState(Rectangle(0, 0, 1920, 1080), maximized = true, fullScreen = false))
      // Without a window manager (xvfb in CI) AWT cannot maximize a frame and leaves its state unchanged
      if (Toolkit.getDefaultToolkit().isFrameStateSupported(Frame.MAXIMIZED_BOTH)) {
        assertEquals(Frame.MAXIMIZED_BOTH, frame.extendedState and Frame.MAXIMIZED_BOTH)
      }
      assertEquals(Rectangle(120, 130, 640, 480), frame.bounds)
    }
  }

  // finding 2
  @Test
  fun `bounds are set only after the frame has left full screen`() {
    SwingUtilities.invokeAndWait {
      val frame = frame(Rectangle(0, 0, 1920, 1080))
      val control = object : FullScreenControl {
        override var isInFullScreen = true
        var done: (() -> Unit)? = null
        override fun toggle(state: Boolean, done: () -> Unit) {
          this.done = done
        }
      }
      val window = FrameWindow(frame, control)
      window.apply(WindowState(Rectangle(100, 100, 800, 600), maximized = false, fullScreen = false))
      assertTrue(window.isInTransition)
      assertEquals(Rectangle(0, 0, 1920, 1080), frame.bounds)

      control.isInFullScreen = false
      control.done!!()
      assertFalse(window.isInTransition)
      assertEquals(Rectangle(100, 100, 800, 600), frame.bounds)
    }
  }

  // finding 6
  @Test
  fun `a strip leaving its frame removes its listeners even when the IDE is gone`() {
    SwingUtilities.invokeAndWait {
      val frame = frame(Rectangle(0, 0, 400, 300))
      val listeners = Triple(frame.windowListeners.size, frame.windowStateListeners.size, frame.componentListeners.size)
      val project = Proxy.newProxyInstance(Project::class.java.classLoader, arrayOf(Project::class.java)) { _, _, _ -> null } as Project
      val strip = ProjectTabStrip(project)
      strip.watch(frame)
      runCatching { strip.detach() }
      assertEquals(listeners, Triple(frame.windowListeners.size, frame.windowStateListeners.size, frame.componentListeners.size))
      strip.detach() // and does not fail
    }
  }
}
