// Copyright 2000-2025 JetBrains s.r.o. and contributors, 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
// Ported from the IntelliJ Community Modal Commit Interface plugin (plugins/git-modal-commit) into a Phantom plugin.
package dev.phantom.modalcommit

import com.intellij.ide.util.runOnceForApp
import com.intellij.openapi.options.advanced.AdvancedSettings
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsApplicationSettings
import com.intellij.openapi.vcs.impl.VcsInitObject
import com.intellij.openapi.vcs.impl.VcsStartupActivity

internal class ModalCommitToggler : VcsStartupActivity {
  override suspend fun execute(project: Project) {
    runOnceForApp("phantom.modal.commit.toggle") {
      AdvancedSettings.setBoolean(ModalCommitModeProvider.SETTING_ID,
                                  !VcsApplicationSettings.getInstance().COMMIT_FROM_LOCAL_CHANGES)
    }
  }

  override val order: Int
    get() = VcsInitObject.MAPPINGS.order + 1
}
