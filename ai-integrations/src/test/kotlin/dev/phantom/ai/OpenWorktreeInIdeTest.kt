// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.ai.sidebar.ProjectOpener
import dev.phantom.ai.sidebar.ProjectSwitcher
import dev.phantom.ai.sidebar.SidebarActions
import dev.phantom.workspaces.core.ProjectTabsService

class OpenWorktreeInIdeTest : BasePlatformTestCase() {
  fun testAlreadyOpenPathSwitchesAndDoesNotOpen() {
    val switched = mutableListOf<String>()
    val opened = mutableListOf<String>()
    val actions = SidebarActions(project, ProjectSwitcher { switched.add(it); true }, ProjectOpener { opened.add(it) })
    val before = SidebarActions.openProjectCount()
    actions.openInPhantom("/w/open")
    assertEquals(listOf("/w/open"), switched)
    assertTrue(opened.isEmpty())
    assertEquals(before, SidebarActions.openProjectCount())
  }

  fun testNotOpenPathCallsOpenerOnce() {
    val opened = mutableListOf<String>()
    val actions = SidebarActions(project, ProjectSwitcher { false }, ProjectOpener { opened.add(it) })
    actions.openInPhantom("/w/closed")
    assertEquals(listOf("/w/closed"), opened)
  }

  fun testDefaultSwitcherIsWorkspacesAndKnowsNoUnopenedPath() {
    val opened = mutableListOf<String>()
    SidebarActions(project, opener = ProjectOpener { opened.add(it) }).openInPhantom("/definitely/not/open/${System.nanoTime()}")
    assertEquals(1, opened.size)
    assertFalse(ProjectTabsService.getInstance().switchTo("/definitely/not/open"))
  }
}
