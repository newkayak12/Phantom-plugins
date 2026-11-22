// Copyright 2000-2025 JetBrains s.r.o. and contributors, 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
// Ported from the IntelliJ Community Modal Commit Interface plugin (plugins/git-modal-commit) into a Phantom plugin.
// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.
package dev.phantom.modalcommit

import com.intellij.dvcs.commit.DvcsCommitModeProvider
import com.intellij.openapi.options.advanced.AdvancedSettings
import com.intellij.vcs.commit.CommitMode

internal class ModalCommitModeProvider : DvcsCommitModeProvider {
  override fun getCommitMode(): CommitMode? {
    return if (AdvancedSettings.getBoolean(SETTING_ID)) CommitMode.ModalCommitMode else null
  }

  companion object {
    const val SETTING_ID = "phantom.modal.commit"
  }
}
