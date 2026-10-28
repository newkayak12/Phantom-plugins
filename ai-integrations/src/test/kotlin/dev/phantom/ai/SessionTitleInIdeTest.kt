// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.AgentSession
import dev.phantom.ai.core.AgentStatus
import dev.phantom.ai.core.AiState
import dev.phantom.ai.core.SessionStore
import dev.phantom.ai.hooks.HookEvent
import dev.phantom.ai.hooks.HookIngest

/** SessionStart carries no prompt; the title is taken from the later UserPromptSubmit and stays. */
class SessionTitleInIdeTest : BasePlatformTestCase() {
  private val wt = "/tmp/phantom-title-test-wt"
  private val tab = "titleTab"

  override fun setUp() {
    super.setUp()
    SessionStore.getInstance().put(AgentSession(tab, wt, AgentKind.CLAUDE))
  }

  override fun tearDown() {
    try {
      SessionStore.getInstance().remove(tab)
      AiState.getInstance().state.sessions.removeAll { it.worktree == wt }
    } finally { super.tearDown() }
  }

  fun testTitleComesFromTheFirstEventWithAPrompt() {
    val start = HookEvent("SessionStart", AgentStatus.WAITING, "s1", null, null)
    val prompt = HookEvent("UserPromptSubmit", AgentStatus.WORKING, "s1", "fix the bug", null)
    assertTrue(HookIngest.apply(tab, AgentKind.CLAUDE, start))
    assertNull(AiState.getInstance().sessionsOf(wt).single().firstPrompt)
    assertTrue(HookIngest.apply(tab, AgentKind.CLAUDE, prompt))
    assertTrue(HookIngest.apply(tab, AgentKind.CLAUDE, HookEvent("UserPromptSubmit", AgentStatus.WORKING, "s1", "later", null)))
    assertEquals("fix the bug", AiState.getInstance().sessionsOf(wt).single().firstPrompt)
  }
}
