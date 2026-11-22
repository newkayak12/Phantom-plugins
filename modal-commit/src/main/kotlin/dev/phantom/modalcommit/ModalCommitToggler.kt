// Copyright 2000-2025 JetBrains s.r.o. and contributors, 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
// Ported from the IntelliJ Community Modal Commit Interface plugin (plugins/git-modal-commit) into a Phantom plugin.
// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.
package dev.phantom.modalcommit

import com.intellij.ide.util.runOnceForApp
import com.intellij.openapi.options.advanced.AdvancedSettings
import com.intellij.openapi.options.advanced.AdvancedSettingsImpl
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsApplicationSettings
import com.intellij.openapi.vcs.impl.VcsInitObject
import com.intellij.openapi.vcs.impl.VcsStartupActivity

internal class ModalCommitToggler : VcsStartupActivity {
  override suspend fun execute(project: Project) {
    runOnceForApp("phantom.modal.commit.toggle") {
      AdvancedSettings.setBoolean(ModalCommitModeProvider.SETTING_ID, initialModalCommit())
    }
  }

  override val order: Int
    get() = VcsInitObject.MAPPINGS.order + 1
}

/** The setting of the bundled Modal Commit Interface plugin this one replaces. */
internal const val BUNDLED_SETTING_ID = "git.non.modal.commit"

/**
 * The choice the user had: the bundled plugin's setting if it was ever changed (it is kept in the advanced settings state
 * even when that plugin is off), otherwise the old "Use non-modal commit interface" option.
 */
internal fun initialModalCommit(): Boolean {
  val bundled = (AdvancedSettings.getInstance() as? AdvancedSettingsImpl)?.state?.settings?.get(BUNDLED_SETTING_ID)
  return bundled?.toBooleanStrictOrNull() ?: !VcsApplicationSettings.getInstance().COMMIT_FROM_LOCAL_CHANGES
}
