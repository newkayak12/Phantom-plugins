// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.vcs.git.rpc

import com.intellij.openapi.components.service
import com.intellij.platform.project.ProjectId
import org.jetbrains.annotations.ApiStatus

// An application service of intellij.vcs.git.backend: Phantom runs frontend and backend in one process, without RPC
@ApiStatus.Internal
interface GitUiSettingsApi {
  suspend fun setGroupingByPrefix(projectId: ProjectId, groupByPrefix: Boolean)

  suspend fun initBranchSyncPolicyIfNotInitialized(projectId: ProjectId)

  suspend fun setShowTags(projectId: ProjectId, showTags: Boolean)

  companion object {
    suspend fun getInstance(): GitUiSettingsApi = service<GitUiSettingsApi>()
  }
}