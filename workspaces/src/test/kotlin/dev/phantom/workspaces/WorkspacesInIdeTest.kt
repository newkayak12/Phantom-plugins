// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.workspaces

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.options.advanced.AdvancedSettings
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectNameListener
import com.intellij.openapi.project.ex.ProjectEx
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.wm.IdeRootPaneNorthExtension
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.workspaces.core.ProjectTabStrip
import dev.phantom.workspaces.core.ProjectTabs
import dev.phantom.workspaces.core.ProjectTabsService

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
