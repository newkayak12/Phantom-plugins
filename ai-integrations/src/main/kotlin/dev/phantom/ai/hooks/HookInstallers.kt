// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.hooks

import dev.phantom.ai.core.bestEffort
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.GsonBuilder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Shared JSON merge for Claude settings.json and Codex hooks.json. Both take the directory as a parameter. */
internal object HookJson {
  private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

  /** The parsed file; an absent or empty file is an empty object, a malformed one is null (leave it alone). */
  fun read(file: Path): JsonObject? {
    if (!Files.isRegularFile(file)) return JsonObject()
    val text = Files.readString(file)
    if (text.isBlank()) return JsonObject()
    return bestEffort { JsonParser.parseString(text).takeIf { it.isJsonObject }?.asJsonObject }.getOrNull()
  }

  /** Writes through a symlink to its real target and keeps the file's permissions. */
  fun write(file: Path, root: JsonObject) {
    Files.createDirectories(file.parent)
    val text = gson.toJson(root) + "\n"
    val target = if (Files.exists(file)) file.toRealPath() else file
    if (Files.isRegularFile(target) && Files.readString(target) == text) return
    val tmp = Files.createTempFile(target.parent, target.fileName.toString(), ".tmp")
    try {
      Files.writeString(tmp, text)
      if (Files.isRegularFile(target)) {
        bestEffort { Files.setPosixFilePermissions(tmp, Files.getPosixFilePermissions(target)) }
      }
      Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } finally {
      Files.deleteIfExists(tmp)
    }
  }

  fun isManaged(group: JsonElement, marker: String): Boolean =
    group.isJsonObject && group.asJsonObject.get("hooks")?.takeIf { it.isJsonArray }?.asJsonArray
      ?.any { h -> h.isJsonObject && h.asJsonObject.get("command")?.takeIf { it.isJsonPrimitive }?.asString?.contains(marker) == true } == true

  /** Replaces the plugin's own group in each event of [events], keeping every other group and event. */
  fun mergeHooks(root: JsonObject, events: List<String>, command: String, marker: String, timeout: (String) -> Int) {
    val hooks = root.get("hooks")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject().also { root.add("hooks", it) }
    for (event in events) {
      val old = hooks.get(event)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
      val groups = JsonArray()
      old.filterNot { isManaged(it, marker) }.forEach { groups.add(it) }
      val hook = JsonObject().apply { addProperty("type", "command"); addProperty("command", command); addProperty("timeout", timeout(event)) }
      groups.add(JsonObject().apply { add("hooks", JsonArray().also { it.add(hook) }) })
      hooks.add(event, groups)
    }
  }
}

/** Installs the hooks and the statusLine into <configDir>/settings.json of Claude Code. */
object ClaudeHookInstaller {
  const val MARKER = "claude-hook.sh"
  const val STATUSLINE_MARKER = "claude-statusline.sh"

  /** @return false when settings.json is not valid JSON (left untouched). */
  fun install(configDir: Path, scripts: ScriptPaths): Boolean {
    val file = configDir.resolve("settings.json")
    val root = HookJson.read(file) ?: return false
    HookJson.mergeHooks(root, HookEventMapper.CLAUDE_EVENTS, HookScripts.command(scripts.claudeHook), MARKER) { 5 }
    // The plugin's script runs the status line (it feeds the usage widget); a previous command is kept as its argument.
    val old = root.get("statusLine")?.takeIf { it.isJsonObject }?.asJsonObject
    val line = old?.deepCopy() ?: JsonObject()
    line.addProperty("type", "command")
    line.addProperty("command", statusLineCommand(HookScripts.command(scripts.claudeStatusline), old?.get("command")))
    root.add("statusLine", line)
    HookJson.write(file, root)
    return true
  }

  /** [ours], followed by the previous command quoted; a command of ours keeps the argument it already carries. */
  private fun statusLineCommand(ours: String, old: JsonElement?): String {
    val cmd = old?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() } ?: return ours
    if (cmd.contains(STATUSLINE_MARKER)) return ours + cmd.substringAfter("$STATUSLINE_MARKER'", "")
    return "$ours '" + cmd.replace("'", "'\\''") + "'"
  }
}

/**
 * Writes <codexHome>/hooks.json (top level exactly {"hooks": ...}: Codex rejects other fields). Trust is Codex's own
 * step (it asks the user to review new hooks); nothing is written to config.toml and no bypass flag is used.
 */
object CodexHookInstaller {
  const val MARKER = "codex-hook.sh"

  fun install(codexHome: Path, scripts: ScriptPaths): Boolean {
    val file = codexHome.resolve("hooks.json")
    val root = HookJson.read(file) ?: return false
    HookJson.mergeHooks(root, HookEventMapper.CODEX_EVENTS, HookScripts.command(scripts.codexHook), MARKER) { if (it == "Interrupt") 3 else 5 }
    HookJson.write(file, root)
    return true
  }
}
