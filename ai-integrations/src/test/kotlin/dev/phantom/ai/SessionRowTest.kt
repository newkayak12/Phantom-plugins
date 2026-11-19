// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.AgentSession
import dev.phantom.ai.core.AgentStatus
import dev.phantom.ai.sidebar.SessionRow
import junit.framework.TestCase

class SessionRowTest : TestCase() {
  private fun s(kind: AgentKind = AgentKind.CLAUDE, status: AgentStatus? = null, user: String? = null, first: String? = null) =
    AgentSession("t", "/w", kind, status = status, userTitle = user, firstPrompt = first, startedAt = 1_000)

  fun testTitleUserThenFirstPromptThenAgentName() {
    assertEquals("mine", SessionRow.of(s(user = "mine", first = "hello"), 2_000).title)
    assertEquals("hello", SessionRow.of(s(first = "hello\nsecond line"), 2_000).title)
    assertEquals("Codex", SessionRow.of(s(kind = AgentKind.CODEX), 2_000).title)
  }

  fun testStatusAndAgentIconKeys() {
    assertEquals("launched", SessionRow.of(s(), 2_000).statusKey)
    assertEquals("working", SessionRow.of(s(status = AgentStatus.WORKING), 2_000).statusKey)
    assertEquals("waiting", SessionRow.of(s(status = AgentStatus.WAITING), 2_000).statusKey)
    assertEquals("done", SessionRow.of(s(status = AgentStatus.DONE), 2_000).statusKey)
    assertEquals("claude", SessionRow.of(s(), 2_000).agentIconKey)
    assertEquals("codex", SessionRow.of(s(kind = AgentKind.CODEX), 2_000).agentIconKey)
  }

  fun testElapsed() {
    assertEquals("45s", SessionRow.elapsed(1_000, 46_000))
    assertEquals("12m", SessionRow.elapsed(1_000, 1_000 + 12 * 60_000L))
    assertEquals("3h 5m", SessionRow.elapsed(1_000, 1_000 + (3 * 3600 + 5 * 60) * 1000L))
    assertEquals("2d", SessionRow.elapsed(1_000, 1_000 + 2 * 86_400_000L))
    assertNull(SessionRow.elapsed(0, 5_000))
  }
}
