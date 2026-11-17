// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import dev.phantom.ai.usage.ClaudeCredentials
import dev.phantom.ai.usage.CommandRunner
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class ClaudeCredentialsTest {
  private val fixture = File("src/test/resources/usage/claude-credentials.json").readText()
  private val keychainJson = """{"claudeAiOauth":{"accessToken":"tok-kc"}}"""

  private class Rec(val reply: String?) : CommandRunner {
    val calls = mutableListOf<List<String>>()
    override fun run(argv: List<String>): String? = reply.also { calls += argv }
  }

  private fun tmp(withFile: Boolean): File = Files.createTempDirectory("cc").toFile().also {
    if (withFile) File(it, ".credentials.json").writeText(fixture)
  }

  @Test fun systemDefaultUsesExactArgv() {
    val r = Rec(keychainJson)
    assertEquals("tok-kc", ClaudeCredentials(r, true, tmp(false).path).readToken(null))
    assertEquals(listOf(listOf("/usr/bin/security", "find-generic-password", "-s", "Claude Code-credentials", "-w")), r.calls)
  }

  @Test fun redirectedSystemDefaultReadsRedirectedDirWithSuffixedService() {
    val dir = tmp(true)
    val r = Rec(null)
    assertEquals("tok-file", ClaudeCredentials(r, true, "/nonexistent-home") { dir.path }.readToken(null))
    assertEquals(ClaudeCredentials.service(dir.path), r.calls.single()[3])
    val r2 = Rec(null)
    assertEquals("tok-file", ClaudeCredentials(r2, false, "/nonexistent-home") { dir.path }.readToken(null))
    assertEquals(0, r2.calls.size)
  }

  @Test fun accountDirAddsSha256Nfc8Suffix() {
    val dir = tmp(false)
    val r = Rec(keychainJson)
    ClaudeCredentials(r, true).readToken(dir.path)
    val nfc = java.text.Normalizer.normalize(dir.path, java.text.Normalizer.Form.NFC)
    val hex = MessageDigest.getInstance("SHA-256").digest(nfc.toByteArray()).joinToString("") { "%02x".format(it) }.take(8)
    assertEquals("Claude Code-credentials-$hex", r.calls.single()[3])
  }

  @Test fun nfdAndNfcDirsGetSameService() {
    assertEquals(ClaudeCredentials.service("/tmp/é"), ClaudeCredentials.service("/tmp/é"))
  }

  @Test fun keychainMissFallsBackToCredentialsFile() {
    val dir = tmp(true)
    val r = Rec(null)
    assertEquals("tok-file", ClaudeCredentials(r, true).readToken(dir.path))
    assertEquals(1, r.calls.size)
  }

  @Test fun nonMacNeverRunsSecurity() {
    val r = Rec(keychainJson)
    assertEquals("tok-file", ClaudeCredentials(r, false).readToken(tmp(true).path))
    assertEquals(0, r.calls.size)
  }

  @Test fun neitherSourceGivesNull() {
    assertNull(ClaudeCredentials(Rec(null), true).readToken(tmp(false).path))
    assertNull(ClaudeCredentials(Rec("garbage"), true).readToken(tmp(false).path))
  }

  @Test fun keychainDisabledNeverRunsSecurity() {
    System.setProperty("phantom.ai.keychain", "false")
    try {
      val r = Rec(keychainJson)
      assertEquals("tok-file", ClaudeCredentials(r, true).readToken(tmp(true).path))
      assertNull(ClaudeCredentials(r, true).readToken(tmp(false).path))
      assertEquals(0, r.calls.size)
    } finally { System.clearProperty("phantom.ai.keychain") }
  }
}
