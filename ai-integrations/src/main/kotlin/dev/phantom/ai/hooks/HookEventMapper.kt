// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.hooks

import dev.phantom.ai.core.bestEffort
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.AgentStatus

/** A provider hook payload reduced to what Phantom uses. [status] null = no status change. */
data class HookEvent(
  val name: String,
  val status: AgentStatus?,
  val sessionId: String?,
  val prompt: String?,
  val lastAnswer: String?,
  val subagent: SubagentEvent? = null,
  val subagentId: String? = null,
  val subagentName: String? = null,
)

enum class SubagentEvent { START, STOP }

/** Pure mapping of Claude / Codex hook payloads (JSON with hook_event_name, session_id, prompt, ...) to [HookEvent]. */
object HookEventMapper {
  val CLAUDE_EVENTS = listOf(
    "SessionStart", "UserPromptSubmit", "PreToolUse", "PostToolUse", "PermissionRequest", "Notification",
    "Stop", "StopFailure", "SubagentStart", "SubagentStop",
  )
  val CODEX_EVENTS = listOf(
    "SessionStart", "UserPromptSubmit", "PreToolUse", "PermissionRequest", "PostToolUse",
    "SubagentStart", "SubagentStop", "Stop", "Interrupt",
  )

  fun map(kind: AgentKind, json: String): HookEvent? {
    val o = bestEffort { JsonParser.parseString(json) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
    return map(kind, o)
  }

  fun map(kind: AgentKind, o: JsonObject): HookEvent? {
    val name = str(o, "hook_event_name") ?: return null
    val status: AgentStatus? = when (name) {
      "UserPromptSubmit", "PreToolUse", "PostToolUse", "PostToolUseFailure" -> AgentStatus.WORKING
      "PermissionRequest" -> AgentStatus.WAITING
      "Notification" ->
        if (kind == AgentKind.CLAUDE && str(o, "notification_type") in WAITING_NOTIFICATIONS) AgentStatus.WAITING else null
      "Stop", "StopFailure" -> AgentStatus.DONE
      "Interrupt" -> if (kind == AgentKind.CODEX) AgentStatus.DONE else null
      else -> null
    }
    val sub = when (name) {
      "SubagentStart" -> SubagentEvent.START
      "SubagentStop" -> SubagentEvent.STOP
      else -> null
    }
    return HookEvent(
      name = name,
      status = status,
      sessionId = str(o, "session_id"),
      prompt = if (name == "UserPromptSubmit") str(o, "prompt") else null,
      lastAnswer = str(o, "last_assistant_message"),
      subagent = sub,
      subagentId = if (sub != null) str(o, "agent_id") ?: str(o, "subagent_id") else null,
      subagentName = if (sub != null) str(o, "agent_type") ?: str(o, "subagent_type") else null,
    )
  }

  private val WAITING_NOTIFICATIONS = setOf("permission_prompt", "idle_prompt", "elicitation_dialog")

  private fun str(o: JsonObject, key: String): String? =
    o.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
}
