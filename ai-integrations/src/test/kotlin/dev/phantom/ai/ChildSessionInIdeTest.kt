// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.AgentSession
import dev.phantom.ai.core.AgentStatus
import dev.phantom.ai.core.AiState
import dev.phantom.ai.core.SessionStore
import dev.phantom.ai.hooks.HookEvent
import dev.phantom.ai.hooks.HookIngest

/**
 * A headless `claude -p` started inside the tab (a plugin's MCP server or a subprocess) inherits the tab's hook env, so its
 * hooks post to the same tab with its own session_id. They must not take over the tab's session.
 */
class ChildSessionInIdeTest : BasePlatformTestCase() {
  private val wt = "/tmp/phantom-child-test-wt"
  private val tab = "childTab"
  private val store get() = SessionStore.getInstance()

  override fun setUp() {
    super.setUp()
    store.put(AgentSession(tab, wt, AgentKind.CLAUDE))
    HookIngest.apply(tab, AgentKind.CLAUDE, HookEvent("SessionStart", null, "parent", null, null, source = "startup"))
    HookIngest.apply(tab, AgentKind.CLAUDE, HookEvent("UserPromptSubmit", AgentStatus.WORKING, "parent", "run the graph", null))
  }

  override fun tearDown() {
    try {
      store.remove(tab)
      AiState.getInstance().state.sessions.removeAll { it.worktree == wt }
    } finally { super.tearDown() }
  }

  fun testAChildSessionLeavesTheTabAlone() {
    assertFalse(HookIngest.apply(tab, AgentKind.CLAUDE, HookEvent("SessionStart", null, "child", null, null, source = "startup")))
    assertFalse(HookIngest.apply(tab, AgentKind.CLAUDE, HookEvent("UserPromptSubmit", AgentStatus.WORKING, "child", "node prompt", null)))
    assertFalse(HookIngest.apply(tab, AgentKind.CLAUDE, HookEvent("Stop", AgentStatus.DONE, "child", null, "node answer")))
    val s = store.get(tab)!!
    assertEquals("parent", s.sessionId)
    assertEquals(AgentStatus.WORKING, s.status)
    assertEquals("run the graph", s.lastPrompt)
    assertNull(s.lastAnswer)
    assertEquals(listOf("parent"), AiState.getInstance().sessionsOf(wt).map { it.sessionId })
  }

  fun testClearMovesTheTabToTheNewSession() {
    assertTrue(HookIngest.apply(tab, AgentKind.CLAUDE, HookEvent("SessionStart", null, "next", null, null, source = "clear")))
    assertTrue(HookIngest.apply(tab, AgentKind.CLAUDE, HookEvent("Stop", AgentStatus.DONE, "next", null, "ok")))
    assertEquals("next", store.get(tab)!!.sessionId)
    assertEquals(AgentStatus.DONE, store.get(tab)!!.status)
  }

  fun testEventsWithoutASessionIdStillApply() {
    assertTrue(HookIngest.apply(tab, AgentKind.CLAUDE, HookEvent("Stop", AgentStatus.DONE, null, null, "ok")))
    assertEquals("parent", store.get(tab)!!.sessionId)
  }
}
