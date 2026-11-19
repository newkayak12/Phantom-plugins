// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.remoteApi

import com.intellij.openapi.client.currentSession
import com.intellij.util.application

// Phantom: no GitApiProvider. The remote APIs are application services (intellij.vcs.git.backend.xml), not a fleet RPC
// remoteApiProvider: Phantom runs frontend and backend in one process, and the RPC compiler plugin that generates their
// descriptors is not in this build

internal fun requireOwner() {
  check(application.currentSession.isOwner) { "Git API is available only for owner session" }
}
