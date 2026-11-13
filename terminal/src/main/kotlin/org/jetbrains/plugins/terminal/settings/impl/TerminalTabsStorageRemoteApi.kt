// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.terminal.settings.impl

import com.intellij.platform.project.ProjectId
import com.intellij.openapi.components.service
import kotlinx.serialization.Serializable
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.plugins.terminal.startup.TerminalProcessType

// An application service of intellij.terminal.backend: Phantom runs frontend and backend in one process, without RPC
/**
 * Frontend → backend migration RPC for [TerminalTabsStorage].
 *
 * In the new frontend-only terminal mode, persisted terminal tabs are stored on the frontend.
 * Tabs previously persisted on the backend would be lost without migration.
 * This RPC fetches the backend's currently stored tabs so the frontend can apply them at once.
 */
@ApiStatus.Internal
interface TerminalTabsStorageRemoteApi {
  /**
   * Returns persisted terminal tabs stored on the backend, or an empty list if there are none.
   */
  suspend fun getStoredTabs(projectId: ProjectId): List<TerminalSessionPersistedTabDto>

  companion object {
    suspend fun getInstance(): TerminalTabsStorageRemoteApi {
      return service<TerminalTabsStorageRemoteApi>()
    }
  }
}

@ApiStatus.Internal
@Serializable
data class TerminalSessionPersistedTabDto(
  val name: String?,
  val isUserDefinedName: Boolean,
  val shellCommand: List<String>?,
  val workingDirectory: String?,
  val envVariables: Map<String, String>?,
  val processType: TerminalProcessType?,
)