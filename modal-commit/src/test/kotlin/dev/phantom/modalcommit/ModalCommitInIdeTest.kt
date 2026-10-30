// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.modalcommit

import com.intellij.openapi.options.advanced.AdvancedSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.vcs.commit.CommitMode

class ModalCommitInIdeTest : BasePlatformTestCase() {
  fun `test the advanced setting switches the commit mode`() {
    val before = AdvancedSettings.getBoolean(ModalCommitModeProvider.SETTING_ID)
    try {
      AdvancedSettings.setBoolean(ModalCommitModeProvider.SETTING_ID, true)
      assertEquals(CommitMode.ModalCommitMode, ModalCommitModeProvider().getCommitMode())
      AdvancedSettings.setBoolean(ModalCommitModeProvider.SETTING_ID, false)
      assertNull(ModalCommitModeProvider().getCommitMode())
    }
    finally {
      AdvancedSettings.setBoolean(ModalCommitModeProvider.SETTING_ID, before)
    }
  }
}
