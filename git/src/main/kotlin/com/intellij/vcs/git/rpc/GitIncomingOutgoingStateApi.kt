// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.vcs.git.rpc

import com.intellij.openapi.components.service
import com.intellij.platform.project.ProjectId
import com.intellij.vcs.git.branch.GitInOutProjectState
import kotlinx.coroutines.flow.Flow
import org.jetbrains.annotations.ApiStatus

// An application service of intellij.vcs.git.backend: Phantom runs frontend and backend in one process, without RPC
@ApiStatus.Internal
interface GitIncomingOutgoingStateApi {
  suspend fun syncState(projectId: ProjectId): Flow<GitInOutProjectState>

  companion object {
    suspend fun getInstance(): GitIncomingOutgoingStateApi =
      service<GitIncomingOutgoingStateApi>()
  }
}
