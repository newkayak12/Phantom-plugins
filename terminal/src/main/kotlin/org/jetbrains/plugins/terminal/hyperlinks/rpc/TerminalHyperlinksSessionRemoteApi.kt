// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.terminal.hyperlinks.rpc

import com.intellij.openapi.components.service
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.plugins.terminal.hyperlinks.session.TerminalHyperlinkClickedEvent
import org.jetbrains.plugins.terminal.hyperlinks.session.TerminalHyperlinksInputEvent
import org.jetbrains.plugins.terminal.hyperlinks.session.TerminalHyperlinksOutputEvent
import org.jetbrains.plugins.terminal.hyperlinks.session.TerminalHyperlinksSessionId

// An application service of intellij.terminal.backend: Phantom runs frontend and backend in one process, without RPC
@ApiStatus.Internal
interface TerminalHyperlinksSessionRemoteApi {
  suspend fun getInputEventsSink(sessionId: TerminalHyperlinksSessionId): SendChannel<TerminalHyperlinksInputEvent>

  suspend fun getHyperlinkUpdatesChannel(sessionId: TerminalHyperlinksSessionId): ReceiveChannel<TerminalHyperlinksOutputEvent>

  suspend fun handleHyperlinkClick(sessionId: TerminalHyperlinksSessionId, event: TerminalHyperlinkClickedEvent)

  companion object {
    suspend fun getInstance(): TerminalHyperlinksSessionRemoteApi {
      return service<TerminalHyperlinksSessionRemoteApi>()
    }
  }
}