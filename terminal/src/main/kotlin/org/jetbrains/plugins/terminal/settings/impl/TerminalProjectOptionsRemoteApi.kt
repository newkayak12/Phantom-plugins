// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.
package org.jetbrains.plugins.terminal.settings.impl

import com.intellij.platform.project.ProjectId
import com.intellij.openapi.components.service
import kotlinx.serialization.Serializable
import org.jetbrains.annotations.ApiStatus

// An application service of intellij.terminal.backend: Phantom runs frontend and backend in one process, without RPC
/**
 * Frontend → backend migration RPC for [TerminalProjectOptionsMigration].
 *
 * In the new frontend-only terminal mode, the project options provider is stored on the frontend.
 * Custom values previously persisted by the user on the backend would be lost without migration.
 * This RPC fetches the user-customized values from the backend so the frontend can apply them at once.
 */
@ApiStatus.Internal
interface TerminalProjectOptionsRemoteApi {
  /**
   * Returns custom project options stored on the backend. Fields are `null` when the user has not
   * set a custom value (i.e. the backend would fall back to a default).
   */
  suspend fun getProjectOptions(projectId: ProjectId): TerminalProjectOptionsDto

  companion object {
    suspend fun getInstance(): TerminalProjectOptionsRemoteApi {
      return service<TerminalProjectOptionsRemoteApi>()
    }
  }
}

@ApiStatus.Internal
@Serializable
data class TerminalProjectOptionsDto(
  val startingDirectory: String?,
  val shellPath: String?,
  val envData: EnvironmentVariablesDataDto?,
)

@ApiStatus.Internal
@Serializable
data class EnvironmentVariablesDataDto(
  val envs: Map<String, String>,
  val isPassParentEnvs: Boolean,
)