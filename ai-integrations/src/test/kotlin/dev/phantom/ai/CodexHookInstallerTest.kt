// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import com.google.gson.JsonParser
import dev.phantom.ai.hooks.CodexHookInstaller
import dev.phantom.ai.hooks.HookInstallService
import dev.phantom.ai.hooks.HookScripts
import dev.phantom.ai.core.AgentKind
import java.nio.file.Files
import junit.framework.TestCase

/** Temp dirs only: never touches the real ~/.codex. */
class CodexHookInstallerTest : TestCase() {
  private val root = Files.createTempDirectory("phantom-codex-home")
  private val home = root.resolve("home")
  private val scripts = HookScripts.write(root.resolve("scripts"))
  private val events = listOf("SessionStart", "UserPromptSubmit", "PreToolUse", "PermissionRequest", "PostToolUse", "SubagentStart", "SubagentStop", "Stop", "Interrupt")

  override fun tearDown() { root.toFile().deleteRecursively() }

  fun testWritesNineEventsUnderHooksOnly() {
    assertTrue(CodexHookInstaller.install(home, scripts))
    val j = JsonParser.parseString(Files.readString(home.resolve("hooks.json"))).asJsonObject
    assertEquals(setOf("hooks"), j.keySet())
    assertEquals(events.toSet(), j.getAsJsonObject("hooks").keySet())
    val interrupt = j.getAsJsonObject("hooks").getAsJsonArray("Interrupt")[0].asJsonObject.getAsJsonArray("hooks")[0].asJsonObject
    assertTrue(interrupt["timeout"].asInt <= 3)
    assertTrue(interrupt["command"].asString.contains("codex-hook.sh"))
  }

  fun testKeepsUserHooksIdempotentAndLeavesConfigTomlAlone() {
    Files.createDirectories(home)
    Files.writeString(home.resolve("hooks.json"), """{"hooks":{"Stop":[{"hooks":[{"type":"command","command":"mine.sh"}]}]}}""")
    Files.writeString(home.resolve("config.toml"), "model = \"x\"\n")
    CodexHookInstaller.install(home, scripts); val first = Files.readString(home.resolve("hooks.json"))
    CodexHookInstaller.install(home, scripts)
    assertEquals(first, Files.readString(home.resolve("hooks.json")))
    val stop = JsonParser.parseString(first).asJsonObject.getAsJsonObject("hooks").getAsJsonArray("Stop")
    assertEquals(2, stop.size()); assertTrue(first.contains("mine.sh"))
    assertEquals("model = \"x\"\n", Files.readString(home.resolve("config.toml")))
    assertEquals(setOf("hooks.json", "config.toml"), Files.list(home).use { s -> s.map { it.fileName.toString() }.toList().toSet() })
  }

  fun testMalformedUntouchedAndNoBypassFlag() {
    Files.createDirectories(home); Files.writeString(home.resolve("hooks.json"), "oops")
    assertFalse(CodexHookInstaller.install(home, scripts))
    assertEquals("oops", Files.readString(home.resolve("hooks.json")))
    assertFalse(Files.readString(scripts.codexHook).contains("dangerously"))
  }

  fun testServiceEnsureInstalledUsesGivenDirs() {
    val svc = HookInstallService()
    assertTrue(svc.ensureInstalled(AgentKind.CODEX, home, root.resolve("s2")))
    assertTrue(Files.isRegularFile(home.resolve("hooks.json")))
    assertTrue(svc.ensureInstalled(AgentKind.CLAUDE, root.resolve("c"), root.resolve("s2")))
    assertTrue(Files.isRegularFile(root.resolve("c/settings.json")))
  }

  fun testSystemDirRedirect() {
    val svc = HookInstallService()
    System.setProperty(HookInstallService.CODEX_DIR_PROP, home.toString())
    System.setProperty(HookInstallService.CLAUDE_DIR_PROP, root.resolve("c").toString())
    try {
      assertEquals(home, svc.systemDir(AgentKind.CODEX)); assertEquals(root.resolve("c"), svc.systemDir(AgentKind.CLAUDE))
    } finally {
      System.clearProperty(HookInstallService.CODEX_DIR_PROP); System.clearProperty(HookInstallService.CLAUDE_DIR_PROP)
    }
  }
}
