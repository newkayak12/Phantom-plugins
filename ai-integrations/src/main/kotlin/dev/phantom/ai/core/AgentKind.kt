// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.core

enum class AgentKind(val displayName: String, val command: String) {
  CLAUDE("Claude", "claude"),
  CODEX("Codex", "codex"),
}

/** working | waiting | done; a session that has sent no event yet has no status (it is only launched). */
enum class AgentStatus { WORKING, WAITING, DONE }
