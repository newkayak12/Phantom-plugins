// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.AgentSession
import dev.phantom.ai.core.AiState
import dev.phantom.ai.core.SessionStore
import dev.phantom.ai.core.Subagent
import dev.phantom.ai.hooks.HookEventMapper
import dev.phantom.ai.hooks.HookIngest

/** The hooks keep a short line of what the session (and each subagent) does now, cleared when it stops. */
class SessionActivityInIdeTest : BasePlatformTestCase() {
  private val wt = "/tmp/phantom-activity-test-wt"
  private val tab = "activityTab"
  private val store get() = SessionStore.getInstance()

  override fun setUp() {
    super.setUp()
    store.put(AgentSession(tab, wt, AgentKind.CLAUDE))
  }

  override fun tearDown() {
    try {
      store.remove(tab)
      AiState.getInstance().state.sessions.removeAll { it.worktree == wt }
    } finally { super.tearDown() }
  }

  private fun post(name: String, extra: String = "") =
    assertTrue(HookIngest.apply(tab, AgentKind.CLAUDE, HookEventMapper.map(AgentKind.CLAUDE, """{"hook_event_name":"$name","session_id":"s1"$extra}""")!!))

  private val activity get() = store.get(tab)!!.activity
  private val subagents get() = store.get(tab)!!.subagents

  fun testToolCallsSetTheActivityAndStopClearsIt() {
    post("SessionStart", ""","source":"startup"""")
    assertNull(activity)
    post("UserPromptSubmit", ""","prompt":"fix it"""")
    assertEquals("생각 중", activity)
    post("PreToolUse", ""","tool_name":"Edit","tool_input":{"file_path":"/r/src/Foo.kt"}""")
    assertEquals("Edit Foo.kt", activity)
    post("PermissionRequest", ""","tool_name":"Bash"""")
    assertEquals("권한 요청: Bash", activity)
    post("PostToolUse", ""","tool_name":"Bash","tool_input":{"command":"ls"}""")
    assertEquals("Bash: ls", activity)
    post("Notification", ""","notification_type":"auth_success"""")
    assertEquals("Bash: ls", activity)
    post("Stop")
    assertNull(activity)
  }

  fun testASubagentsToolCallsAreItsOwnActivity() {
    post("PreToolUse", ""","tool_name":"Task","tool_input":{"subagent_type":"Explore"}""")
    post("SubagentStart", ""","agent_id":"a1","agent_type":"Explore"""")
    post("PreToolUse", ""","agent_id":"a1","agent_type":"Explore","tool_name":"Grep","tool_input":{"pattern":"foo"}""")
    assertEquals("Task: Explore", activity)
    assertEquals(listOf(Subagent("a1", "Explore", activity = "Grep \"foo\"")), subagents)
    // an unknown subagent's tool call is dropped, not shown as the session's
    post("PreToolUse", ""","agent_id":"zz","tool_name":"Read","tool_input":{"file_path":"/x.md"}""")
    assertEquals("Task: Explore", activity)
    post("SubagentStop", ""","agent_id":"a1"""")
    assertEquals(listOf(Subagent("a1", "Explore", running = false)), subagents)
  }

  fun testATeammatesToolCallsReachItsPrefixedEntry() {
    store.update(tab) { it.copy(subagents = listOf(Subagent("team:rev@t", "rev"))) }
    post("PreToolUse", ""","agent_id":"rev@t","tool_name":"Read","tool_input":{"file_path":"/r/A.kt"}""")
    assertEquals("Read A.kt", subagents.single().activity)
  }
}
