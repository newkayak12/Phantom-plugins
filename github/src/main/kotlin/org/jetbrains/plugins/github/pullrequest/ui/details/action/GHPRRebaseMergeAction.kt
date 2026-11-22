// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.
package org.jetbrains.plugins.github.pullrequest.ui.details.action

import com.intellij.collaboration.async.combineAndCollect
import com.intellij.collaboration.messages.CollaborationToolsBundle
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.plugins.github.pullrequest.ui.details.model.GHPRReviewFlowViewModel
import java.awt.event.ActionEvent
import javax.swing.AbstractAction

internal class GHPRRebaseMergeAction(scope: CoroutineScope, private val project: Project, private val reviewFlowVm: GHPRReviewFlowViewModel)
  : AbstractAction(CollaborationToolsBundle.message("review.details.action.rebase")) {

  init {
    scope.launch {
      combineAndCollect(reviewFlowVm.isBusy, reviewFlowVm.isRebaseEnabled) { isBusy, isRebaseEnabled ->
        isEnabled = !isBusy && isRebaseEnabled
      }
    }
  }

  override fun actionPerformed(e: ActionEvent?) {
    reviewFlowVm.rebaseReview()
  }
}