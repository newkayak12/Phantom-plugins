// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.version.catalog

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class CheckNewerVersionsActionTest : BasePlatformTestCase() {
  private fun isShownFor(fileName: String): Boolean {
    myFixture.configureByText(fileName, "[libraries]\n")
    val action = ActionManager.getInstance().getAction("dev.phantom.version.catalog.CheckNewerVersions")
    val event = TestActionEvent.createTestEvent(
      action,
      SimpleDataContext.builder()
        .add(CommonDataKeys.PROJECT, project)
        .add(CommonDataKeys.VIRTUAL_FILE, myFixture.file.virtualFile)
        .build(),
    )
    action.update(event)
    return event.presentation.isEnabledAndVisible
  }

  fun testShownOnlyForVersionCatalogs() {
    assertTrue(isShownFor("libs.versions.toml"))
    assertFalse(isShownFor("Cargo.toml"))
  }
}
