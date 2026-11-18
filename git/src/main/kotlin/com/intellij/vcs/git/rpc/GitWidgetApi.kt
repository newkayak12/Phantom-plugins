// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.vcs.git.rpc

import com.intellij.ide.vfs.VirtualFileId
import com.intellij.openapi.components.service
import com.intellij.platform.project.ProjectId
import kotlinx.coroutines.flow.Flow
import org.jetbrains.annotations.ApiStatus

// An application service of intellij.vcs.git.backend: Phantom runs frontend and backend in one process, without RPC
@ApiStatus.Internal
interface GitWidgetApi {
  suspend fun getWidgetState(projectId: ProjectId, selectedFile: VirtualFileId?): Flow<GitWidgetState>

  companion object {
    suspend fun getInstance(): GitWidgetApi = service<GitWidgetApi>()
  }
}