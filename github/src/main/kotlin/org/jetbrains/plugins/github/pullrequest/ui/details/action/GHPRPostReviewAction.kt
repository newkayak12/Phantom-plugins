// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.
package org.jetbrains.plugins.github.pullrequest.ui.details.action

import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.plugins.github.i18n.GithubBundle
import org.jetbrains.plugins.github.pullrequest.ui.details.model.GHPRReviewFlowViewModel
import java.awt.event.ActionEvent
import javax.swing.AbstractAction

internal class GHPRPostReviewAction(scope: CoroutineScope, private val project: Project, private val reviewFlowVm: GHPRReviewFlowViewModel)
  : AbstractAction(GithubBundle.message("pull.request.post.action")) {

  init {
    scope.launch {
      reviewFlowVm.isBusy.collect { isBusy ->
        isEnabled = !isBusy && reviewFlowVm.userCanManageReview
      }
    }
  }

  override fun actionPerformed(e: ActionEvent?) {
    reviewFlowVm.postDraftedReview()
  }
}