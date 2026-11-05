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
  /** The subagent's agent_id: on SubagentStart/SubagentStop, and on the tool events Claude sends from inside a subagent. */
  val subagentId: String? = null,
  val subagentName: String? = null,
  /** SessionStart only: startup, resume, clear or compact. */
  val source: String? = null,
  /** What the agent does now, short ("Edit Foo.kt", "Bash: ./gradlew test", "답 기다림"); null = no change. */
  val activity: String? = null,
  /** Claude Notification idle_prompt: a WAITING that means idle, not a prompt to answer. */
  val idle: Boolean = false,
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
      subagentId = str(o, "agent_id") ?: if (sub != null) str(o, "subagent_id") else null,
      subagentName = if (sub != null) str(o, "agent_type") ?: str(o, "subagent_type") else null,
      source = if (name == "SessionStart") str(o, "source") else null,
      activity = activity(kind, name, o),
      idle = name == "Notification" && kind == AgentKind.CLAUDE && str(o, "notification_type") == "idle_prompt",
    )
  }

  /** Longest activity text; a longer one is cut with an ellipsis. */
  const val MAX_ACTIVITY = 28

  private fun activity(kind: AgentKind, name: String, o: JsonObject): String? = when (name) {
    "UserPromptSubmit" -> "생각 중"
    "PreToolUse", "PostToolUse", "PostToolUseFailure" ->
      str(o, "tool_name")?.let { describeTool(it, o.get("tool_input")?.takeIf { i -> i.isJsonObject }?.asJsonObject) }
    "PermissionRequest" -> truncate("권한 요청" + (str(o, "tool_name")?.let { ": " + toolName(it) } ?: ""))
    "Notification" -> if (kind != AgentKind.CLAUDE) null else when (str(o, "notification_type")) {
      "permission_prompt" -> "권한 요청"
      "idle_prompt" -> "답 기다림"
      "elicitation_dialog" -> "질문에 답 기다림"
      else -> null
    }
    else -> null
  }

  /** A tool call as a short line: a file tool's file name, a shell's command, a search's pattern, else the tool's name. */
  fun describeTool(tool: String, input: JsonObject?): String {
    fun s(key: String) = input?.let { str(it, key) }
    val text = when (tool) {
      "Read", "Edit", "MultiEdit", "Write", "NotebookEdit" -> (s("file_path") ?: s("notebook_path"))?.let { "$tool ${fileName(it)}" }
      "Bash", "shell", "local_shell", "exec_command" -> command(input)?.let { "Bash: $it" }
      "Grep" -> s("pattern")?.let { "Grep \"$it\"" }
      "Glob" -> s("pattern")?.let { "Glob $it" }
      "WebFetch" -> s("url")?.let { "WebFetch " + it.substringAfter("://").substringBefore('/') }
      "WebSearch" -> s("query")?.let { "WebSearch $it" }
      "Task", "Agent" -> (s("subagent_type") ?: s("description"))?.let { "Task: $it" }
      "apply_patch" -> input?.toString()?.let { PATCH_FILE.find(it) }?.let { "Edit ${fileName(it.groupValues[1])}" }
      else -> null
    }
    return truncate(text ?: toolName(tool))
  }

  /** An MCP tool (mcp__server__tool) by its own name. */
  private fun toolName(tool: String) = tool.substringAfterLast("__")

  private fun fileName(path: String) = path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')

  /** A shell call's command, a string or an argv (`bash -lc "..."` gives the script), first line only. */
  private fun command(input: JsonObject?): String? {
    val c = input?.get("command") ?: return null
    val text = when {
      c.isJsonPrimitive -> c.asString
      c.isJsonArray -> c.asJsonArray.mapNotNull { e -> e.takeIf { it.isJsonPrimitive }?.asString }.let { argv ->
        if (argv.size >= 3 && argv[argv.size - 2] in setOf("-c", "-lc")) argv.last() else argv.joinToString(" ")
      }
      else -> null
    }
    return text?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()
  }

  private val PATCH_FILE = Regex("""\*\*\* (?:Update|Add|Delete) File: ([^"\\\n]+)""")

  private fun truncate(s: String): String {
    val one = s.replace(Regex("\\s+"), " ").trim()
    return if (one.length <= MAX_ACTIVITY) one else one.take(MAX_ACTIVITY - 1) + "\u2026"
  }

  private val WAITING_NOTIFICATIONS = setOf("permission_prompt", "idle_prompt", "elicitation_dialog")

  private fun str(o: JsonObject, key: String): String? =
    o.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
}
