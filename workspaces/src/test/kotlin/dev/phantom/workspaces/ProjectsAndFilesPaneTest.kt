// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.workspaces

import com.intellij.openapi.options.advanced.AdvancedSettings
import com.intellij.openapi.util.Disposer
import com.intellij.platform.ide.nonModalWelcomeScreen.leftPanel.WelcomeScreenLeftPanel
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.workspaces.core.ProjectTabs

class ProjectsAndFilesPaneTest : BasePlatformTestCase() {
  private fun pane() = WelcomeScreenLeftPanel(project).also { Disposer.register(testRootDisposable, it) }

  fun testSelectableInAnOrdinaryProjectButNotItsDefault() {
    val pane = pane()
    assertTrue(pane.isInitiallyVisible)
    assertFalse(pane.isDefaultPane(project))
  }

  fun testGoneWithProjectTabsOff() {
    AdvancedSettings.setBoolean(ProjectTabs.SETTING_ID, false)
    try {
      assertFalse(pane().isInitiallyVisible)
    }
    finally {
      AdvancedSettings.setBoolean(ProjectTabs.SETTING_ID, true)
    }
  }
}
