// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.accounts

import com.google.gson.JsonObject
import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.SystemDirs
import dev.phantom.ai.hooks.HookJson
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Makes an account dir behave like the user's own setup, with only the login different: skills, plugins, memory and
 * conversation history are symlinks to the System default dir. Only adds what is missing, never overwrites, and is safe
 * to repeat, so an account also gets what the user installs later. Delete such a dir without following links.
 */
internal object AccountHome {
  /** Tied to one login or one running CLI; dot files (credentials, .claude.json) are always left out too. */
  private val CLAUDE_OWN = setOf(
    "settings.json", "settings.local.json", "sessions", "state", "cache", "backups", "statsig", "telemetry", "debug", "ide",
    "mcp-needs-auth-cache.json",
  )
  private val CLAUDE_FILE_KEYS = listOf("mcpServers", "hasCompletedOnboarding", "theme")
  private val CODEX_SHARED = listOf("AGENTS.md", "skills", "prompts", "rules", "plugins", "sessions", "archived_sessions", "history.jsonl")
  private val KEPT_SETTINGS = setOf("hooks", "statusLine")

  fun inherit(kind: AgentKind, dir: Path, systemDir: Path = SystemDirs.dir(kind), systemClaudeFile: Path = defaultClaudeFile()) {
    if (systemDir.normalize() == dir.normalize() || !Files.isDirectory(systemDir)) return
    when (kind) {
      AgentKind.CLAUDE -> {
        val names = Files.list(systemDir).use { s -> s.map { it.fileName.toString() }.toList() }
        names.filter { !it.startsWith(".") && it !in CLAUDE_OWN && !it.endsWith(".lock") }.forEach { link(systemDir.resolve(it), dir.resolve(it)) }
        mergeSettings(systemDir.resolve("settings.json"), dir.resolve("settings.json"))
        mergeKeys(systemClaudeFile, dir.resolve(".claude.json"))
      }
      AgentKind.CODEX -> {
        CODEX_SHARED.forEach { link(systemDir.resolve(it), dir.resolve(it)) }
        val config = systemDir.resolve("config.toml")
        val own = dir.resolve("config.toml")
        if (Files.isRegularFile(config) && !Files.exists(own, LinkOption.NOFOLLOW_LINKS)) Files.copy(config, own)
      }
    }
  }

  private fun defaultClaudeFile(): Path =
    SystemDirs.redirect(AgentKind.CLAUDE)?.let { Path.of(it, ".claude.json") } ?: Path.of(System.getProperty("user.home"), ".claude.json")

  private fun link(source: Path, target: Path) {
    if (Files.exists(source) && !Files.exists(target, LinkOption.NOFOLLOW_LINKS)) Files.createSymbolicLink(target, source)
  }

  /** The user's settings the account lacks, and the user's hooks beside the account's; a malformed file is left alone. */
  private fun mergeSettings(from: Path, to: Path) {
    if (!Files.isRegularFile(from)) return
    val source = HookJson.read(from) ?: return
    val target = HookJson.read(to) ?: return
    var changed = false
    for ((key, value) in source.entrySet()) {
      if (key !in KEPT_SETTINGS && !target.has(key)) { target.add(key, value); changed = true }
    }
    source.get("hooks")?.takeIf { it.isJsonObject }?.asJsonObject?.let { hooks ->
      val ours = target.get("hooks")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject().also { target.add("hooks", it) }
      for ((event, groups) in hooks.entrySet()) {
        if (!groups.isJsonArray) continue
        val own = ours.get(event)?.takeIf { it.isJsonArray }?.asJsonArray ?: com.google.gson.JsonArray().also { ours.add(event, it) }
        for (group in groups.asJsonArray) if (group !in own) { own.add(group); changed = true }
      }
    }
    if (changed) HookJson.write(to, target)
  }

  /** Written only when a key is missing: a running CLI rewrites this file. */
  private fun mergeKeys(from: Path, to: Path) {
    if (!Files.isRegularFile(from)) return
    val source = HookJson.read(from) ?: return
    val target = HookJson.read(to) ?: return
    val missing = CLAUDE_FILE_KEYS.filter { source.has(it) && !target.has(it) }
    if (missing.isEmpty()) return
    missing.forEach { target.add(it, source.get(it)) }
    HookJson.write(to, target)
  }
}
