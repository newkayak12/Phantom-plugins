// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.mcp

import com.google.gson.JsonObject
import com.intellij.openapi.application.PathManager
import dev.phantom.ai.core.bestEffort
import dev.phantom.ai.hooks.HookEnv
import dev.phantom.ai.launch.McpLink
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * Per-tab links to [PhantomMcp]: the URL carries the tab id, the token comes from the tab's env ([HookEnv.TOKEN]) or, for
 * Claude, from a config file only the user can read, under the IDE's temp dir and deleted with the tab.
 */
object McpConfigFiles {
  fun link(port: Int, token: String, tabId: String, dir: Path = defaultDir()): McpLink {
    val url = "http://127.0.0.1:$port/mcp?tabId=$tabId"
    val file = dir.resolve("$tabId.json")
    val server = JsonObject().apply {
      addProperty("type", "http")
      addProperty("url", url)
      add("headers", JsonObject().apply { addProperty("Authorization", "Bearer $token") })
    }
    val config = JsonObject().apply { add("mcpServers", JsonObject().apply { add(McpLink.NAME, server) }) }
    Files.createDirectories(dir)
    bestEffort { Files.deleteIfExists(file) }
    val created = bestEffort { Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))) }
      .getOrNull() ?: Files.createFile(file)
    Files.writeString(created, config.toString())
    return McpLink(url, HookEnv.TOKEN, file.toString())
  }

  fun delete(tabId: String, dir: Path = defaultDir()) {
    bestEffort { Files.deleteIfExists(dir.resolve("$tabId.json")) }
  }

  private fun defaultDir(): Path = Path.of(PathManager.getTempPath(), "phantom-ai-mcp")
}
