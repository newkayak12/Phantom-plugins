// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.modalcommit

import com.intellij.openapi.options.advanced.AdvancedSettings
import com.intellij.openapi.options.advanced.AdvancedSettingsImpl
import com.intellij.openapi.vcs.VcsApplicationSettings
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

  fun `test the first start follows the bundled plugin's modal commit setting`() {
    val advanced = AdvancedSettings.getInstance() as AdvancedSettingsImpl
    val vcsSettings = VcsApplicationSettings.getInstance()
    val stateBefore = advanced.state
    val commitFromLocalChangesBefore = vcsSettings.COMMIT_FROM_LOCAL_CHANGES
    try {
      // never set in the bundled plugin: the old non-modal option decides
      advanced.loadState(AdvancedSettingsImpl.AdvancedSettingsState())
      vcsSettings.COMMIT_FROM_LOCAL_CHANGES = false
      assertTrue(initialModalCommit())
      vcsSettings.COMMIT_FROM_LOCAL_CHANGES = true
      assertFalse(initialModalCommit())

      // modal commit was on in the bundled plugin; the old non-modal option still has its default
      advanced.loadState(AdvancedSettingsImpl.AdvancedSettingsState().apply { settings = mapOf(BUNDLED_SETTING_ID to "true") })
      assertTrue(initialModalCommit())

      vcsSettings.COMMIT_FROM_LOCAL_CHANGES = false
      advanced.loadState(AdvancedSettingsImpl.AdvancedSettingsState().apply { settings = mapOf(BUNDLED_SETTING_ID to "false") })
      assertFalse(initialModalCommit())
    }
    finally {
      advanced.loadState(stateBefore)
      vcsSettings.COMMIT_FROM_LOCAL_CHANGES = commitFromLocalChangesBefore
    }
  }
}
