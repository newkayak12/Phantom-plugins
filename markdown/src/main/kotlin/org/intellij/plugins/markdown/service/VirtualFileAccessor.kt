// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.
package org.intellij.plugins.markdown.service

import com.intellij.ide.vfs.VirtualFileId
import com.intellij.platform.project.ProjectId
import com.intellij.openapi.components.service
import org.jetbrains.annotations.ApiStatus

// An application service of intellij.markdown.backend: Phantom runs frontend and backend in one process, without RPC
@ApiStatus.Internal
interface VirtualFileAccessor {
  suspend fun tryToLoadFileContent(resourceName: String, virtualFileId: VirtualFileId, projectId: ProjectId): ByteArray?

  companion object {
    @JvmStatic
    suspend fun getInstance(): VirtualFileAccessor {
      return service<VirtualFileAccessor>()
    }
  }
}