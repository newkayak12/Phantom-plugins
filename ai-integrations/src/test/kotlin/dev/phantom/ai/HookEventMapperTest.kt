// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.AgentStatus
import dev.phantom.ai.hooks.HookEventMapper
import dev.phantom.ai.hooks.SubagentEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HookEventMapperTest {
  private fun m(kind: AgentKind, name: String, extra: String = "") =
    HookEventMapper.map(kind, """{"hook_event_name":"$name","session_id":"s1"$extra}""")!!

  private val claude = AgentKind.CLAUDE
  private val codex = AgentKind.CODEX

  @Test fun claudeUserPromptSubmitIsWorkingAndCapturesPrompt() {
    val e = m(claude, "UserPromptSubmit", ""","prompt":"fix it"""")
    assertEquals(AgentStatus.WORKING, e.status); assertEquals("fix it", e.prompt); assertEquals("s1", e.sessionId)
  }
  @Test fun claudePreToolUseIsWorking() = assertEquals(AgentStatus.WORKING, m(claude, "PreToolUse").status)
  @Test fun claudePostToolUseIsWorking() = assertEquals(AgentStatus.WORKING, m(claude, "PostToolUse").status)
  @Test fun claudePermissionRequestIsWaiting() = assertEquals(AgentStatus.WAITING, m(claude, "PermissionRequest").status)
  @Test fun claudePermissionNotificationIsWaiting() =
    assertEquals(AgentStatus.WAITING, m(claude, "Notification", ""","notification_type":"permission_prompt"""").status)
  @Test fun claudeIdleNotificationIsWaiting() =
    assertEquals(AgentStatus.WAITING, m(claude, "Notification", ""","notification_type":"idle_prompt"""").status)
  @Test fun claudeOtherNotificationKeepsStatus() =
    assertNull(m(claude, "Notification", ""","notification_type":"auth_success"""").status)
  @Test fun claudeStopIsDoneWithAnswer() {
    val e = m(claude, "Stop", ""","last_assistant_message":"done!"""")
    assertEquals(AgentStatus.DONE, e.status); assertEquals("done!", e.lastAnswer)
  }
  @Test fun claudeStopFailureIsDone() = assertEquals(AgentStatus.DONE, m(claude, "StopFailure").status)
  @Test fun claudeSubagentStartAndStopAreCaptured() {
    assertEquals(SubagentEvent.START, m(claude, "SubagentStart").subagent)
    assertEquals(SubagentEvent.STOP, m(claude, "SubagentStop").subagent)
    assertNull(m(claude, "SubagentStart").status)
  }
  @Test fun claudeSessionStartRegistersWithoutStatus() {
    val e = m(claude, "SessionStart"); assertNull(e.status); assertEquals("s1", e.sessionId)
  }
  @Test fun sessionStartCarriesItsSource() {
    assertEquals("clear", m(claude, "SessionStart", ""","source":"clear"""").source)
    assertEquals("startup", m(codex, "SessionStart", ""","source":"startup"""").source)
    assertNull(m(claude, "Stop", ""","source":"clear"""").source)
  }

  @Test fun codexSessionStartCapturesSessionIdWithoutStatus() {
    val e = m(codex, "SessionStart"); assertNull(e.status); assertEquals("s1", e.sessionId)
  }
  @Test fun codexUserPromptSubmitIsWorkingWithPrompt() {
    val e = m(codex, "UserPromptSubmit", ""","prompt":"hi"""")
    assertEquals(AgentStatus.WORKING, e.status); assertEquals("hi", e.prompt)
  }
  @Test fun codexPreAndPostToolUseAreWorking() {
    assertEquals(AgentStatus.WORKING, m(codex, "PreToolUse").status)
    assertEquals(AgentStatus.WORKING, m(codex, "PostToolUse").status)
  }
  @Test fun codexPermissionRequestIsWaiting() = assertEquals(AgentStatus.WAITING, m(codex, "PermissionRequest").status)
  @Test fun codexStopIsDoneWithAnswer() {
    val e = m(codex, "Stop", ""","last_assistant_message":"ok"""")
    assertEquals(AgentStatus.DONE, e.status); assertEquals("ok", e.lastAnswer)
  }
  @Test fun codexInterruptIsDone() = assertEquals(AgentStatus.DONE, m(codex, "Interrupt").status)
  @Test fun codexSubagentEventsCaptured() {
    assertEquals(SubagentEvent.START, m(codex, "SubagentStart").subagent)
    assertEquals(SubagentEvent.STOP, m(codex, "SubagentStop").subagent)
  }
  private fun tool(kind: AgentKind, tool: String, input: String, name: String = "PreToolUse") =
    m(kind, name, ""","tool_name":"$tool","tool_input":$input""").activity

  @Test fun toolCallsBecomeShortActivities() {
    assertEquals("Edit Foo.kt", tool(claude, "Edit", """{"file_path":"/repo/src/Foo.kt","old_string":"a"}"""))
    assertEquals("Read README.md", tool(claude, "Read", """{"file_path":"/repo/README.md"}"""))
    assertEquals("Write a.txt", tool(claude, "Write", """{"file_path":"C:\\w\\a.txt"}"""))
    assertEquals("Bash: ./gradlew test", tool(claude, "Bash", """{"command":"./gradlew test\nmore"}"""))
    assertEquals("Grep \"foo\"", tool(claude, "Grep", """{"pattern":"foo"}"""))
    assertEquals("Task: Explore", tool(claude, "Task", """{"subagent_type":"Explore","prompt":"x"}"""))
    assertEquals("Task: Plan", tool(claude, "Agent", """{"subagent_type":"Plan"}"""))
    assertEquals("WebFetch example.com", tool(claude, "WebFetch", """{"url":"https://example.com/a/b"}"""))
    assertEquals("open_in_editor", tool(claude, "mcp__phantom__open_in_editor", "{}"))
    assertEquals("TodoWrite", tool(claude, "TodoWrite", "{}"))
    assertEquals("Edit Foo.kt", tool(claude, "Edit", """{"file_path":"/Foo.kt"}""", name = "PostToolUse"))
    assertEquals("Bash: ls -la", tool(codex, "Bash", """{"command":["bash","-lc","ls -la"]}"""))
  }

  @Test fun longActivitiesAreCutTo28Characters() {
    val a = tool(claude, "Bash", """{"command":"./gradlew :ai-integrations:test :ai-visualization:test"}""")!!
    assertEquals(HookEventMapper.MAX_ACTIVITY, a.length)
    assertTrue(a.startsWith("Bash: ./gradlew") && a.endsWith("\u2026"))
  }

  @Test fun waitingAndThinkingHaveTheirOwnActivity() {
    assertEquals("권한 요청: Bash", m(claude, "PermissionRequest", ""","tool_name":"Bash"""").activity)
    assertEquals("권한 요청", m(claude, "Notification", ""","notification_type":"permission_prompt"""").activity)
    assertEquals("답 기다림", m(claude, "Notification", ""","notification_type":"idle_prompt"""").activity)
    assertEquals("생각 중", m(claude, "UserPromptSubmit", ""","prompt":"hi"""").activity)
    assertNull(m(claude, "Stop").activity)
    assertNull(m(claude, "Notification", ""","notification_type":"auth_success"""").activity)
  }

  @Test fun toolCallsInsideASubagentCarryItsId() {
    assertEquals("a1", m(claude, "PreToolUse", ""","agent_id":"a1","agent_type":"Explore","tool_name":"Read"""").subagentId)
    assertNull(m(claude, "PreToolUse", ""","tool_name":"Read"""").subagentId)
    assertNull(m(claude, "PreToolUse", ""","agent_type":"Explore"""").subagentName)
  }

  @Test fun garbageIsIgnored() { assertNull(HookEventMapper.map(claude, "not json")); assertNull(HookEventMapper.map(claude, "{}")) }
}
