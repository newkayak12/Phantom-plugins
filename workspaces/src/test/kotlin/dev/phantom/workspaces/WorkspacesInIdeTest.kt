// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.workspaces

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.options.advanced.AdvancedSettings
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectNameListener
import com.intellij.openapi.project.ex.ProjectEx
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.wm.IdeFrame
import com.intellij.openapi.wm.IdeRootPaneNorthExtension
import com.intellij.openapi.wm.StatusBar
import com.intellij.ui.BalloonLayout
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.workspaces.core.ProjectTab
import dev.phantom.workspaces.core.ProjectTabStrip
import com.intellij.ui.scale.JBUIScale
import dev.phantom.workspaces.core.ProjectTabs
import dev.phantom.workspaces.core.ProjectTabsService
import dev.phantom.workspaces.core.Tiling
import dev.phantom.workspaces.core.WindowStack
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.event.HierarchyEvent
import javax.swing.JComponent
import javax.swing.JFrame

class WorkspacesInIdeTest : BasePlatformTestCase() {
  private val strips = mutableListOf<ProjectTabStrip>()

  override fun tearDown() {
    try {
      strips.forEach(ProjectTabStrip::detach)
    }
    catch (e: Throwable) {
      addSuppressedException(e)
    }
    finally {
      super.tearDown()
    }
  }

  private fun extension(): IdeRootPaneNorthExtension =
    IdeRootPaneNorthExtension.EP_NAME.extensionList.single { it.key == ProjectTabs.EXTENSION_KEY }

  private fun stripOf(project: Project): ProjectTabStrip {
    val strip = extension().createComponent(project, isDocked = false) as ProjectTabStrip
    strip.attach() // what adding it to the frame does
    strips += strip
    return strip
  }

  private fun openSecondProject(name: String): Project {
    val dir = FileUtil.createTempDirectory(name, null, true).toPath()
    return PlatformTestUtil.loadAndOpenProject(dir, testRootDisposable)
  }

  private fun settle() = PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

  fun testOnByDefault() {
    assertTrue(AdvancedSettings.getBoolean(ProjectTabs.SETTING_ID))
  }

  fun testTheStripIsAddedAboveTheEditorAreaOfEveryProjectFrame() {
    assertInstanceOf(extension().createComponent(project, isDocked = false), ProjectTabStrip::class.java)
  }

  fun testTabsThatDoNotFitShrinkThenScrollWithTheCurrentOneInView() {
    val strip = extension().createComponent(project, isDocked = false) as ProjectTabStrip
    val tabs = (0 until 12).map { ProjectTab("p$it", "~/orca/workspaces/colo-backend/refactor-CL-55$it", null, isCurrent = it == 11) }
    strip.display(tabs, visible = true)
    strip.setSize(600, 30)
    strip.doLayout()
    val bounds = strip.components.map { it.bounds }
    assertTrue("every tab at least the minimum width", bounds.all { it.width >= JBUIScale.scale(96) - 1 })
    val last = bounds.last()
    assertTrue("the current (last) tab is in view: $last", last.x >= 0 && last.x + last.width <= 600)
    // the wheel scrolls back to the first tab
    repeat(50) { strip.dispatchEvent(java.awt.event.MouseWheelEvent(strip, java.awt.event.MouseEvent.MOUSE_WHEEL, 0L, 0, 10, 10, 0, false, java.awt.event.MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, -1)) }
    assertEquals(0, strip.components.first().x)
  }

  fun testNoStripInDetachedEditorWindows() {
    assertNull(extension().createComponent(project, isDocked = true))
  }

  fun testWithOneProjectTheStripHasOneTabAndStaysHidden() {
    val strip = stripOf(project)
    assertEquals(listOf(project.name), strip.tabs.map { it.title })
    assertTrue(strip.tabs.single().isCurrent)
    assertFalse(strip.isVisible)
  }

  fun testEveryFrameListsEveryOpenProjectAndHighlightsItsOwn() {
    val first = stripOf(project)
    val other = openSecondProject("second")
    val second = stripOf(other)
    settle()

    assertEquals(listOf(project.name, other.name), first.tabs.map { it.title })
    assertEquals(listOf(project.name, other.name), second.tabs.map { it.title })
    assertEquals(listOf(true, false), first.tabs.map { it.isCurrent })
    assertEquals(listOf(false, true), second.tabs.map { it.isCurrent })
    assertTrue(first.isVisible)
    assertTrue(second.isVisible)
  }

  fun testAClosedProjectLeavesTheStrip() {
    val first = stripOf(project)
    val other = openSecondProject("closing")
    stripOf(other)
    settle()
    assertEquals(2, first.tabs.size)

    PlatformTestUtil.forceCloseProjectWithoutSaving(other)
    settle()
    assertEquals(listOf(project.name), first.tabs.map { it.title })
    assertFalse(first.isVisible)
  }

  fun testAMovedTabMovesInEveryFrame() {
    val first = stripOf(project)
    val other = openSecondProject("moving")
    val second = stripOf(other)
    settle()

    ProjectTabsService.getInstance().move(other, 0)
    assertEquals(listOf(other.name, project.name), first.tabs.map { it.title })
    assertEquals(listOf(other.name, project.name), second.tabs.map { it.title })
    assertEquals(listOf(false, true), first.tabs.map { it.isCurrent })
  }

  fun testARenamedProjectIsRenamedInTheStrip() {
    val first = stripOf(project)
    val other = openSecondProject("renaming")
    stripOf(other)
    settle()

    (other as ProjectEx).setProjectName("renamed")
    // ProjectImpl does not publish the rename in unit test mode; publish it as it does in the IDE
    other.messageBus.syncPublisher(ProjectNameListener.TOPIC).nameChanged("renamed")
    settle()
    assertEquals(listOf(project.name, "renamed"), first.tabs.map { it.title })
  }

  fun testADetachedProjectLeavesTheTabsOfEveryOtherWindow() {
    val first = stripOf(project)
    val two = openSecondProject("two")
    val second = stripOf(two)
    val three = openSecondProject("three")
    val third = stripOf(three)
    settle()

    ProjectTabsService.getInstance().detachProject(two)
    assertTrue(ProjectTabsService.getInstance().isDetached(two))
    assertEquals(listOf(project.name, three.name), first.tabs.map { it.title })
    assertEquals(listOf(project.name, three.name), third.tabs.map { it.title })
    assertTrue(first.isVisible)
    assertTrue(third.isVisible)
    // its own window has no strip
    assertFalse(second.isVisible)
  }

  fun testWithOneTabLeftAfterDetachingTheStripHides() {
    val first = stripOf(project)
    val other = openSecondProject("alone")
    stripOf(other)
    settle()

    val service = ProjectTabsService.getInstance()
    service.detachProject(other)
    assertEquals(listOf(project.name), first.tabs.map { it.title })
    assertFalse(first.isVisible)
    // the last tab stays: nothing to detach it from
    assertFalse(service.canDetach(project))
    service.detachProject(project)
    assertFalse(service.isDetached(project))
  }

  fun testAnAttachedProjectComesBackAsTheLastTabInEveryWindow() {
    val first = stripOf(project)
    val two = openSecondProject("back")
    val second = stripOf(two)
    val three = openSecondProject("third")
    val third = stripOf(three)
    settle()

    val service = ProjectTabsService.getInstance()
    service.detachProject(project)
    service.attachProject(project)
    assertFalse(service.isDetached(project))
    val order = listOf(two.name, three.name, project.name)
    assertEquals(order, first.tabs.map { it.title })
    assertEquals(order, second.tabs.map { it.title })
    assertEquals(order, third.tabs.map { it.title })
    assertEquals(listOf(false, false, true), first.tabs.map { it.isCurrent })
    assertTrue(first.isVisible)
  }

  fun testClosingADetachedProjectWorksAsUsual() {
    val first = stripOf(project)
    val other = openSecondProject("closed")
    stripOf(other)
    val third = openSecondProject("stays")
    stripOf(third)
    settle()

    val service = ProjectTabsService.getInstance()
    service.detachProject(other)
    PlatformTestUtil.forceCloseProjectWithoutSaving(other)
    settle()
    assertFalse(service.isDetached(other))
    assertEquals(listOf(project.name, third.name), first.tabs.map { it.title })
  }

  fun testADetachedProjectStaysDetachedWhenTheSettingIsTurnedOffAndOn() {
    val first = stripOf(project)
    val other = openSecondProject("free")
    val second = stripOf(other)
    val third = openSecondProject("tabbed")
    stripOf(third)
    settle()

    val service = ProjectTabsService.getInstance()
    service.detachProject(other)
    AdvancedSettings.setBoolean(ProjectTabs.SETTING_ID, false)
    try {
      settle()
      assertFalse(first.isVisible)
      assertFalse(service.canDetach(project))
    }
    finally {
      AdvancedSettings.setBoolean(ProjectTabs.SETTING_ID, true)
    }
    settle()
    assertTrue(service.isDetached(other))
    assertEquals(listOf(project.name, third.name), first.tabs.map { it.title })
    assertTrue(first.isVisible)
    assertFalse(second.isVisible)
  }

  fun testDetachAndAttachAreInTheWindowMenu() {
    val actions = ActionManager.getInstance()
    val window = actions.getAction("WindowMenu") as com.intellij.openapi.actionSystem.ActionGroup
    val ids = (window as com.intellij.openapi.actionSystem.DefaultActionGroup).childActionsOrStubs.mapNotNull { actions.getId(it) }
    assertTrue(ids.toString(), "PhantomWorkspaces.DetachProject" in ids)
    assertTrue(ids.toString(), "PhantomWorkspaces.AttachProject" in ids)
  }

  fun testTurningItOffHidesTheStrip() {
    val first = stripOf(project)
    stripOf(openSecondProject("off"))
    settle()
    assertTrue(first.isVisible)

    AdvancedSettings.setBoolean(ProjectTabs.SETTING_ID, false)
    try {
      settle()
      assertFalse(first.isVisible)
    }
    finally {
      AdvancedSettings.setBoolean(ProjectTabs.SETTING_ID, true)
    }
    settle()
    assertTrue(first.isVisible)
  }

  /** A project frame as the platform makes one: an [IdeFrame] that gets its project only after it is shown. */
  private class ProjectFrame(bounds: Rectangle) : JFrame(), IdeFrame {
    init {
      this.bounds = bounds
    }

    override fun getStatusBar(): StatusBar? = null
    override fun suggestChildFrameBounds(): Rectangle = bounds
    override fun getProject(): Project? = null
    override fun setFrameTitle(title: String) {}
    override fun getComponent(): JComponent = rootPane
    override fun getBalloonLayout(): BalloonLayout? = null
  }

  // W10 (real IDE, S3): the platform shows a new project frame at its own saved bounds and adds the north components
  // (the strip) only afterwards, so the frame was seen at its saved bounds and then jumped onto the stack
  fun testANewProjectFrameIsShownWhereTheOtherProjectFramesAre() {
    if (GraphicsEnvironment.isHeadless()) return // needs a screen, like FrameWindowTest
    val stacked = Rectangle(100, 100, 800, 600)
    val first = ProjectFrame(stacked)
    val newFrame = ProjectFrame(Rectangle(300, 200, 500, 400))
    try {
      val strip = extension().createComponent(project, isDocked = false) as ProjectTabStrip
      strips += strip
      first.contentPane.add(strip)
      first.addNotify() // the strip's addNotify joins the stack
      assertNotNull(strip.window)

      var shownAt: Rectangle? = null
      newFrame.addHierarchyListener { e ->
        if (e.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && newFrame.isShowing && shownAt == null) shownAt = newFrame.bounds
      }
      newFrame.isVisible = true
      assertEquals(stacked, shownAt)
    }
    finally {
      newFrame.dispose()
      first.dispose()
    }
  }

  /** [project]'s strip in a frame at [bounds], joined to the stack as adding it to a project frame does. */
  private fun framed(project: Project, bounds: Rectangle, frames: MutableList<JFrame>): ProjectTabStrip {
    val frame = ProjectFrame(bounds)
    frames += frame
    val strip = extension().createComponent(project, isDocked = false) as ProjectTabStrip
    strips += strip
    frame.contentPane.add(strip)
    frame.addNotify()
    return strip
  }

  fun testADetachedWindowIsFreedAndPutApartThenAttachedBackOntoTheStack() {
    if (GraphicsEnvironment.isHeadless()) return
    val stacked = Rectangle(100, 100, 800, 600)
    val frames = mutableListOf<JFrame>()
    try {
      val first = framed(project, stacked, frames)
      val other = openSecondProject("framed")
      val second = framed(other, Rectangle(300, 200, 500, 400), frames)
      settle()
      // the stack keeps its place across tests: wherever it is, 40 px off it
      val area = first.window!!.frame.bounds
      assertEquals(area, second.window!!.frame.bounds)

      val service = ProjectTabsService.getInstance()
      service.detachProject(other)
      val free = second.window!!.frame
      val apart = WindowStack.cascaded(area, WindowStack.DETACH_STEP, free.graphicsConfiguration.bounds)
      assertTrue(free.isVisible)
      assertEquals(apart, free.bounds)
      assertEquals(listOf(project.name), first.tabs.map { it.title })

      // the stack no longer moves it: a move of the front window leaves it where it is
      service.switchTo(project)
      first.window!!.frame.bounds = Rectangle(50, 50, 700, 500)
      service.windowMoved(first.window!!)
      service.flushMoves()
      assertEquals(apart, free.bounds)

      service.attachProject(other)
      assertEquals(Rectangle(50, 50, 700, 500), free.bounds)
      assertEquals(listOf(project.name, other.name), first.tabs.map { it.title })
    }
    finally {
      frames.forEach(JFrame::dispose)
    }
  }

  fun testSideBySideTilesTheStackAndMarksBothTabsInEveryStrip() {
    if (GraphicsEnvironment.isHeadless()) return
    val stacked = Rectangle(100, 100, 800, 600)
    val frames = mutableListOf<JFrame>()
    try {
      val first = framed(project, stacked, frames)
      val other = openSecondProject("beside")
      val second = framed(other, stacked, frames)
      val third = openSecondProject("behind")
      framed(third, stacked, frames)
      settle()
      val service = ProjectTabsService.getInstance()
      service.switchTo(project)
      assertFalse(service.canOpenSideBySide(project, project))

      // the stack keeps its place across tests: wherever it is, its halves
      val left = first.window!!.frame
      val right = second.window!!.frame
      val area = left.bounds
      service.openSideBySide(project, other)
      val halves = Tiling.halves(area)
      assertEquals(halves.left, left.bounds)
      assertEquals(halves.right, right.bounds)
      assertTrue(left.isVisible && right.isVisible)
      assertEquals(listOf(project, other), service.sideBySide())
      for (strip in listOf(first, second)) assertEquals(listOf(true, true, false), strip.tabs.map { it.isSideBySide })
      assertEquals(listOf(false, true, false), second.tabs.map { it.isCurrent })

      // the left one made wider from its inner edge: the right one takes the rest
      left.bounds = Rectangle(halves.left).apply { width += 50 }
      service.windowMoved(first.window!!)
      service.flushMoves()
      assertEquals(Rectangle(halves.right).apply { x += 50; width -= 50 }, right.bounds)

      service.exitSideBySide()
      assertTrue(service.sideBySide().isEmpty())
      assertEquals(area, left.bounds)
      assertFalse(right.isVisible)
      assertEquals(listOf(false, false, false), first.tabs.map { it.isSideBySide })
    }
    finally {
      frames.forEach(JFrame::dispose)
    }
  }

  fun testSwitchingToAProjectWithoutAWindowDoesNothing() {
    assertFalse(ProjectTabsService.getInstance().switchTo(project))
  }

  // Closing from the strip runs the platform's "Close Project"; next/previous tab are the platform's project window actions.
  fun testPlatformActionsTheStripReliesOnExist() {
    val actions = ActionManager.getInstance()
    assertNotNull(actions.getAction(ProjectTabs.CLOSE_PROJECT_ACTION_ID))
    assertNotNull(actions.getAction("NextProjectWindow"))
    assertNotNull(actions.getAction("PreviousProjectWindow"))
  }

  // macOS native window tabs were dropped (buggy); nothing may merge project windows natively anymore.
  fun testNoNativeWindowMerging() {
    val activities = ExtensionPointName.create<Any>("com.intellij.postStartupActivity").extensionList
    assertFalse(activities.any { it.javaClass.name.startsWith("dev.phantom.workspaces") })
  }
}
