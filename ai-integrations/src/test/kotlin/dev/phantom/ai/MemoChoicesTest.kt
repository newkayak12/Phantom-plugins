// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.AgentSession
import dev.phantom.ai.memo.MemoChoices
import dev.phantom.ai.memo.MemoTarget
import junit.framework.TestCase

class MemoChoicesTest : TestCase() {
  private val a = AgentSession("a", "/w", AgentKind.CLAUDE)
  private val b = AgentSession("b", "/w", AgentKind.CODEX)

  private fun tabs(l: List<MemoTarget>) = l.map { (it as? MemoTarget.Session)?.tabId ?: (it as MemoTarget.New).kind.name }

  fun testDefaultIsLastUsed() {
    val (list, index) = MemoChoices.of(listOf(a, b), "b")
    assertEquals(listOf("a", "b", "CLAUDE", "CODEX"), tabs(list))
    assertEquals(1, index)
  }

  fun testDefaultFallsBackToFirstSession() {
    assertEquals(0, MemoChoices.of(listOf(a, b), "gone").second)
    assertEquals(0, MemoChoices.of(listOf(a, b), null).second)
  }

  fun testNoSessionsDefaultsToNewClaude() {
    val (list, index) = MemoChoices.of(emptyList(), "x")
    assertEquals(listOf("CLAUDE", "CODEX"), tabs(list))
    assertEquals(0, index)
    assertEquals(MemoTarget.New(AgentKind.CLAUDE), list[index])
  }
}
