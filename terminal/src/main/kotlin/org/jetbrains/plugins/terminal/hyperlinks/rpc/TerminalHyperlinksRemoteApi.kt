// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.terminal.hyperlinks.rpc

import com.intellij.platform.eel.EelDescriptor
import com.intellij.platform.project.ProjectId
import com.intellij.openapi.components.service
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.plugins.terminal.hyperlinks.session.TerminalHyperlinksSessionId

// An application service of intellij.terminal.backend: Phantom runs frontend and backend in one process, without RPC
@ApiStatus.Internal
interface TerminalHyperlinksRemoteApi {
  suspend fun createNewSession(request: TerminalCreateHyperlinksSessionRequest): TerminalHyperlinksSessionId

  suspend fun closeSession(sessionId: TerminalHyperlinksSessionId)

  companion object {
    suspend fun getInstance(): TerminalHyperlinksRemoteApi {
      return service<TerminalHyperlinksRemoteApi>()
    }
  }
}

@Serializable
@ApiStatus.Internal
data class TerminalCreateHyperlinksSessionRequest(
  val projectId: ProjectId,
  /**
   * The descriptor of the environment where the terminal process is running.
   * Required in the monolith scenario where the process can be started in different environments.
   * In the RemDev scenario it will be null because [EelDescriptor] is not serializable.
   * Though, it is expected that in RemDev backend, it is always an environment of the backend project.
   */
  @Transient val eelDescriptor: EelDescriptor? = null,
)