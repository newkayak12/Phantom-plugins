// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.AgentStatus
import dev.phantom.ai.hooks.HookEventMapper
import dev.phantom.ai.hooks.SubagentEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
  @Test fun garbageIsIgnored() { assertNull(HookEventMapper.map(claude, "not json")); assertNull(HookEventMapper.map(claude, "{}")) }
}
