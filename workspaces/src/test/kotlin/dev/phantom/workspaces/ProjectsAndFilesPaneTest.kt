// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.workspaces

import com.intellij.openapi.options.advanced.AdvancedSettings
import com.intellij.openapi.util.Disposer
import com.intellij.platform.ide.nonModalWelcomeScreen.leftPanel.WelcomeScreenLeftPanel
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.workspaces.core.ProjectTabs
import dev.phantom.workspaces.core.ProjectsToolWindowFactory
import kotlinx.coroutines.runBlocking

class ProjectsAndFilesPaneTest : BasePlatformTestCase() {
  private fun pane() = WelcomeScreenLeftPanel(project).also { Disposer.register(testRootDisposable, it) }

  fun testNotAPaneOfTheProjectViewInAnOrdinaryProject() {
    val pane = pane()
    assertFalse(pane.isInitiallyVisible)
    assertFalse(pane.isDefaultPane(project))
  }

  fun testAToolWindowOfItsOwnInAnOrdinaryProject() {
    assertTrue(runBlocking { ProjectsToolWindowFactory().isApplicableAsync(project) })
  }

  fun testGoneWithProjectTabsOff() {
    AdvancedSettings.setBoolean(ProjectTabs.SETTING_ID, false)
    try {
      assertFalse(runBlocking { ProjectsToolWindowFactory().isApplicableAsync(project) })
    }
    finally {
      AdvancedSettings.setBoolean(ProjectTabs.SETTING_ID, true)
    }
  }
}
