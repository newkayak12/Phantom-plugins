// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.remote

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

class RemoteControlState : BaseState() {
  var enabled by property(false)
  var address by string("127.0.0.1")
  var port by property(47860)
  /** SHA-256 hex of the pairing token; the token itself is never stored. */
  var tokenHash by string()
}

@Service(Service.Level.APP)
@State(name = "PhantomAiRemoteControl", storages = [Storage("phantom-ai.xml")])
class RemoteControlSettings : SimplePersistentStateComponent<RemoteControlState>(RemoteControlState()) {
  companion object {
    fun getInstance(): RemoteControlSettings =
      com.intellij.openapi.application.ApplicationManager.getApplication().getService(RemoteControlSettings::class.java)
  }
}
