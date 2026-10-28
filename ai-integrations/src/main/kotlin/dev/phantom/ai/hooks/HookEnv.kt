// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.hooks

/** Env passed to agent terminals so the installed hook scripts can reach the hook server. */
object HookEnv {
  const val PORT = "PHANTOM_AGENT_HOOK_PORT"
  const val TOKEN = "PHANTOM_AGENT_HOOK_TOKEN"
  const val WORKTREE = "PHANTOM_WORKTREE"
  const val TAB_ID = "PHANTOM_TAB_ID"
  const val TOKEN_HEADER = "X-Phantom-Agent-Hook-Token"

  /** Starts the server if needed; the map is ready to merge into a terminal's env. */
  fun forTab(worktree: String, tabId: String, server: HookServer = HookServer.getInstance()): Map<String, String> {
    server.ensureStarted()
    return mapOf(PORT to server.port.toString(), TOKEN to server.token, WORKTREE to worktree, TAB_ID to tabId)
  }
}
