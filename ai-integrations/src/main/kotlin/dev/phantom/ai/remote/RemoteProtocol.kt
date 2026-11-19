// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.remote

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Client to server websocket messages. */
sealed interface ClientMessage {
  data class Auth(val token: String) : ClientMessage
  data class Input(val data: String) : ClientMessage
}

/** Server to client websocket messages. */
sealed interface ServerMessage {
  data class Screen(val cols: Int, val rows: Int, val cursorX: Int, val cursorY: Int, val data: String) : ServerMessage
  data object Closed : ServerMessage
  data class Error(val message: String) : ServerMessage
}

data class SessionInfo(val tabId: String, val title: String, val kind: String, val status: String, val live: Boolean)
data class WorktreeInfo(val path: String, val title: String, val branch: String?, val sessions: List<SessionInfo>)
data class RepoInfo(val name: String, val path: String, val worktrees: List<WorktreeInfo>)
data class ProjectInfo(val name: String, val basePath: String?, val repos: List<RepoInfo>)
data class RemoteState(val projects: List<ProjectInfo>)
data class LaunchRequest(val worktree: String, val kind: String)
data class ResumeRequest(val tabId: String)

/** JSON encode and decode; every decode returns null on malformed input and never throws. */
object RemoteProtocol {
  fun encode(m: ClientMessage): String = obj().apply {
    when (m) {
      is ClientMessage.Auth -> { addProperty("t", "auth"); addProperty("token", m.token) }
      is ClientMessage.Input -> { addProperty("t", "input"); addProperty("data", m.data) }
    }
  }.toString()

  fun encode(m: ServerMessage): String = obj().apply {
    when (m) {
      is ServerMessage.Screen -> {
        addProperty("t", "screen"); addProperty("cols", m.cols); addProperty("rows", m.rows)
        addProperty("cursorX", m.cursorX); addProperty("cursorY", m.cursorY); addProperty("data", m.data)
      }
      ServerMessage.Closed -> addProperty("t", "closed")
      is ServerMessage.Error -> { addProperty("t", "error"); addProperty("message", m.message) }
    }
  }.toString()

  fun decodeClient(json: String?): ClientMessage? = parse(json) { o ->
    when (o.str("t")) {
      "auth" -> o.str("token")?.let { ClientMessage.Auth(it) }
      "input" -> o.str("data")?.let { ClientMessage.Input(it) }
      else -> null
    }
  }

  fun decodeServer(json: String?): ServerMessage? = parse(json) { o ->
    when (o.str("t")) {
      "screen" -> {
        val cols = o.int("cols"); val rows = o.int("rows"); val cx = o.int("cursorX"); val cy = o.int("cursorY"); val d = o.str("data")
        if (cols == null || rows == null || cx == null || cy == null || d == null) null else ServerMessage.Screen(cols, rows, cx, cy, d)
      }
      "closed" -> ServerMessage.Closed
      "error" -> o.str("message")?.let { ServerMessage.Error(it) }
      else -> null
    }
  }

  fun encode(s: RemoteState): String = obj().apply {
    add("projects", JsonArray().also { a -> s.projects.forEach { p -> a.add(project(p)) } })
  }.toString()

  fun decodeState(json: String?): RemoteState? = parse(json) { o ->
    val projects = o.arr("projects")?.map { e -> decodeProject(e.asObjOrNull() ?: return@parse null) ?: return@parse null } ?: return@parse null
    RemoteState(projects)
  }

  fun encode(r: LaunchRequest): String = obj().apply { addProperty("worktree", r.worktree); addProperty("kind", r.kind) }.toString()
  fun encode(r: ResumeRequest): String = obj().apply { addProperty("tabId", r.tabId) }.toString()

  fun decodeLaunch(json: String?): LaunchRequest? = parse(json) { o ->
    val w = o.str("worktree"); val k = o.str("kind")
    if (w == null || k == null) null else LaunchRequest(w, k)
  }

  fun decodeResume(json: String?): ResumeRequest? = parse(json) { o -> o.str("tabId")?.let { ResumeRequest(it) } }

  // ===== helpers =====

  private fun obj() = JsonObject()

  private fun project(p: ProjectInfo) = obj().apply {
    addProperty("name", p.name); addProperty("basePath", p.basePath)
    add("repos", JsonArray().also { a -> p.repos.forEach { r -> a.add(repo(r)) } })
  }

  private fun repo(r: RepoInfo) = obj().apply {
    addProperty("name", r.name); addProperty("path", r.path)
    add("worktrees", JsonArray().also { a -> r.worktrees.forEach { w -> a.add(worktree(w)) } })
  }

  private fun worktree(w: WorktreeInfo) = obj().apply {
    addProperty("path", w.path); addProperty("title", w.title); addProperty("branch", w.branch)
    add("sessions", JsonArray().also { a -> w.sessions.forEach { s -> a.add(session(s)) } })
  }

  private fun session(s: SessionInfo) = obj().apply {
    addProperty("tabId", s.tabId); addProperty("title", s.title); addProperty("kind", s.kind)
    addProperty("status", s.status); addProperty("live", s.live)
  }

  private fun decodeProject(o: JsonObject): ProjectInfo? {
    val name = o.str("name") ?: return null
    val repos = o.arr("repos")?.map { decodeRepo(it.asObjOrNull() ?: return null) ?: return null } ?: return null
    return ProjectInfo(name, o.str("basePath"), repos)
  }

  private fun decodeRepo(o: JsonObject): RepoInfo? {
    val name = o.str("name") ?: return null
    val path = o.str("path") ?: return null
    val wts = o.arr("worktrees")?.map { decodeWorktree(it.asObjOrNull() ?: return null) ?: return null } ?: return null
    return RepoInfo(name, path, wts)
  }

  private fun decodeWorktree(o: JsonObject): WorktreeInfo? {
    val path = o.str("path") ?: return null
    val title = o.str("title") ?: return null
    val sessions = o.arr("sessions")?.map { decodeSession(it.asObjOrNull() ?: return null) ?: return null } ?: return null
    return WorktreeInfo(path, title, o.str("branch"), sessions)
  }

  private fun decodeSession(o: JsonObject): SessionInfo? {
    val live = o.get("live")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: return null
    return SessionInfo(o.str("tabId") ?: return null, o.str("title") ?: return null, o.str("kind") ?: return null, o.str("status") ?: return null, live)
  }

  private fun JsonElement.asObjOrNull(): JsonObject? = if (isJsonObject) asJsonObject else null

  private fun JsonObject.str(k: String): String? =
    get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

  private fun JsonObject.int(k: String): Int? =
    get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.let { runCatching { it.asInt }.getOrNull() }

  private fun JsonObject.arr(k: String): JsonArray? = get(k)?.takeIf { it.isJsonArray }?.asJsonArray

  private inline fun <T : Any> parse(json: String?, f: (JsonObject) -> T?): T? {
    if (json == null) return null
    return try {
      val e = JsonParser.parseString(json)
      if (e.isJsonObject) f(e.asJsonObject) else null
    } catch (_: RuntimeException) {
      null
    }
  }
}
