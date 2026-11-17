// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.accounts

import dev.phantom.ai.core.AgentKind

/** An added account: a config dir of the CLI (CLAUDE_CONFIG_DIR / CODEX_HOME). No tokens here. System default = no account. */
data class AgentAccount(val provider: AgentKind, val id: String, val label: String, val dir: String)
