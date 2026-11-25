// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.workspaces

import com.intellij.openapi.options.advanced.AdvancedSettings
import com.intellij.openapi.wm.ex.WelcomeScreenProjectProvider
import com.intellij.openapi.wm.ex.getWelcomeScreenProjectProvider
import com.intellij.platform.ide.nonModalWelcomeScreen.isNonModalWelcomeScreenEnabled
import com.intellij.platform.ide.nonModalWelcomeScreen.rightTab.WelcomeRightTabContentProvider
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.workspaces.core.ProjectTabs
import dev.phantom.workspaces.home.HomeProjectProvider
import dev.phantom.workspaces.home.HomeTabContentProvider

class HomeTest : BasePlatformTestCase() {
  fun testHomeIsTheWelcomeScreenProject() {
    assertInstanceOf(getWelcomeScreenProjectProvider(), HomeProjectProvider::class.java)
    assertEquals(HomeProjectProvider.path(), WelcomeScreenProjectProvider.getWelcomeScreenProjectPath())
    assertFalse("an ordinary project is not Home", WelcomeScreenProjectProvider.isWelcomeScreenProject(project))
  }

  fun testHomeHasItsOwnTabAndNoOther() {
    assertInstanceOf(WelcomeRightTabContentProvider.getSingleExtension(), HomeTabContentProvider::class.java)
  }

  fun testTurningProjectTabsOffBringsBackTheModalWelcomeFrame() {
    assertTrue(isNonModalWelcomeScreenEnabled)
    AdvancedSettings.setBoolean(ProjectTabs.SETTING_ID, false)
    try {
      assertFalse(isNonModalWelcomeScreenEnabled)
    }
    finally {
      AdvancedSettings.setBoolean(ProjectTabs.SETTING_ID, true)
    }
  }
}
