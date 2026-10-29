// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import com.google.gson.JsonParser
import dev.phantom.ai.hooks.ClaudeHookInstaller
import dev.phantom.ai.hooks.HookEventMapper
import dev.phantom.ai.hooks.HookScripts
import java.nio.file.Files
import junit.framework.TestCase

/** Temp dirs only: never touches the real ~/.claude. */
class ClaudeHookInstallerTest : TestCase() {
  private val root = Files.createTempDirectory("phantom-claude-cfg")
  private val cfg = root.resolve("cfg")
  private val scripts = HookScripts.write(root.resolve("scripts"))
  private val file get() = cfg.resolve("settings.json")

  override fun tearDown() { root.toFile().deleteRecursively() }

  private fun json() = JsonParser.parseString(Files.readString(file)).asJsonObject
  private fun put(text: String) { Files.createDirectories(cfg); Files.writeString(file, text) }

  fun testCreatesHooksAndStatusLine() {
    assertTrue(ClaudeHookInstaller.install(cfg, scripts))
    val j = json()
    for (e in HookEventMapper.CLAUDE_EVENTS) {
      val cmd = j.getAsJsonObject("hooks").getAsJsonArray(e)[0].asJsonObject.getAsJsonArray("hooks")[0].asJsonObject
      assertEquals("command", cmd["type"].asString); assertTrue(cmd["command"].asString.contains("claude-hook.sh"))
    }
    assertTrue(j.getAsJsonObject("statusLine")["command"].asString.contains("claude-statusline.sh"))
  }

  private val ours = HookScripts.command(scripts.claudeStatusline)

  fun testKeepsUserHooksAndUnknownKeysAndWrapsStatusLine() {
    put("""{"theme":"dark","permissions":{"allow":["Bash"]},"statusLine":{"type":"command","command":"my-line","padding":2},
      "hooks":{"Stop":[{"hooks":[{"type":"command","command":"user-stop.sh"}]}],"Custom":[{"x":1}]}}""")
    ClaudeHookInstaller.install(cfg, scripts)
    val j = json()
    assertEquals("dark", j["theme"].asString)
    assertEquals("Bash", j.getAsJsonObject("permissions").getAsJsonArray("allow")[0].asString)
    assertEquals("$ours 'my-line'", j.getAsJsonObject("statusLine")["command"].asString)
    assertEquals(2, j.getAsJsonObject("statusLine")["padding"].asInt)
    val stop = j.getAsJsonObject("hooks").getAsJsonArray("Stop")
    assertEquals(2, stop.size()); assertTrue(stop.toString().contains("user-stop.sh"))
    assertEquals(1, j.getAsJsonObject("hooks").getAsJsonArray("Custom").size())
  }

  fun testSymlinkedSettingsStaysSymlinkAndKeepsPermissions() {
    val real = root.resolve("dotfiles").resolve("settings.json")
    Files.createDirectories(real.parent)
    Files.writeString(real, """{"theme":"dark"}""")
    Files.setPosixFilePermissions(real, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"))
    Files.createDirectories(cfg)
    Files.createSymbolicLink(file, real)
    assertTrue(ClaudeHookInstaller.install(cfg, scripts))
    assertTrue(Files.isSymbolicLink(file))
    assertEquals(real.toRealPath(), file.toRealPath())
    val j = JsonParser.parseString(Files.readString(real)).asJsonObject
    assertEquals("dark", j["theme"].asString)
    assertTrue(j.has("hooks"))
    assertEquals("rw-r--r--", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(real)))
    assertEquals(setOf("settings.json"), Files.list(real.parent).use { it.map { p -> p.fileName.toString() }.toList().toSet() })
  }

  fun testIdempotent() {
    ClaudeHookInstaller.install(cfg, scripts); val first = Files.readString(file)
    ClaudeHookInstaller.install(cfg, scripts)
    assertEquals(first, Files.readString(file))
    assertEquals(1, json().getAsJsonObject("hooks").getAsJsonArray("Stop").size())
  }

  fun testWrappedStatusLineSurvivesReinstallAndAMovedScript() {
    put("""{"statusLine":{"type":"command","command":"echo 'it''s'"}}""")
    ClaudeHookInstaller.install(cfg, scripts); val first = Files.readString(file)
    ClaudeHookInstaller.install(cfg, scripts)
    assertEquals(first, Files.readString(file))
    val moved = HookScripts.write(root.resolve("other"))
    ClaudeHookInstaller.install(cfg, moved)
    val stop = json().getAsJsonObject("hooks").getAsJsonArray("Stop")
    assertEquals(1, stop.size()); assertTrue(stop.toString().contains(moved.claudeHook.toString()))
    assertEquals(HookScripts.command(moved.claudeStatusline) + " 'echo '\\''it'\\'''\\''s'\\'''",
      json().getAsJsonObject("statusLine")["command"].asString)
  }

  fun testOwnPlainStatusLineStaysPlain() {
    ClaudeHookInstaller.install(cfg, scripts)
    ClaudeHookInstaller.install(cfg, scripts)
    assertEquals(ours, json().getAsJsonObject("statusLine")["command"].asString)
  }

  fun testMalformedJsonLeftUntouched() {
    put("{ not json")
    assertFalse(ClaudeHookInstaller.install(cfg, scripts))
    assertEquals("{ not json", Files.readString(file))
    put("[1,2]")
    assertFalse(ClaudeHookInstaller.install(cfg, scripts))
    assertEquals("[1,2]", Files.readString(file))
  }

  fun testNoTempFilesLeft() {
    ClaudeHookInstaller.install(cfg, scripts)
    assertEquals(listOf("settings.json"), Files.list(cfg).use { s -> s.map { it.fileName.toString() }.toList() })
  }
}
