// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import com.sun.net.httpserver.HttpServer
import dev.phantom.ai.hooks.HookEnv
import dev.phantom.ai.hooks.HookScripts
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import junit.framework.TestCase

/** Runs the generated scripts with /bin/sh against a throwaway loopback HTTP server. */
class HookScriptsTest : TestCase() {
  private data class Req(val path: String, val token: String?, val tab: String?, val body: String)

  private val dir = Files.createTempDirectory("phantom-ai-scripts")
  private val seen = CopyOnWriteArrayList<Req>()
  private lateinit var http: HttpServer

  override fun setUp() {
    http = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    http.createContext("/") { ex ->
      seen += Req(ex.requestURI.path, ex.requestHeaders.getFirst(HookEnv.TOKEN_HEADER), ex.requestHeaders.getFirst("X-Phantom-Tab-Id"), String(ex.requestBody.readAllBytes()))
      ex.sendResponseHeaders(204, -1); ex.close()
    }
    http.start()
  }

  override fun tearDown() { http.stop(0); dir.toFile().deleteRecursively() }

  private fun run(script: java.nio.file.Path, stdin: String, withEnv: Boolean, vararg args: String): Pair<Int, String> {
    val pb = ProcessBuilder("/bin/sh", script.toString(), *args).redirectErrorStream(true)
    pb.environment().keys.removeIf { it.startsWith("PHANTOM_") }
    if (withEnv) pb.environment().putAll(mapOf(HookEnv.PORT to http.address.port.toString(), HookEnv.TOKEN to "tok-123", HookEnv.TAB_ID to "tab-9"))
    val p = pb.start()
    p.outputStream.use { it.write(stdin.toByteArray()) }
    val out = p.inputStream.readAllBytes().decodeToString()
    assertTrue(p.waitFor(10, TimeUnit.SECONDS))
    return p.exitValue() to out
  }

  fun testHookPostsBodyWithTokenAndTab() {
    val s = HookScripts.write(dir)
    val body = """{"hook_event_name":"Stop","session_id":"s1","note":"a 'quoted' \"x\""}"""
    val (code, out) = run(s.claudeHook, body, true)
    assertEquals(0, code); assertEquals("", out)
    assertEquals(listOf(Req("/hook/claude", "tok-123", "tab-9", body)), seen.toList())
    run(s.codexHook, body, true)
    assertEquals("/hook/codex", seen.last().path)
  }

  fun testHookWithoutEnvIsSilentNoop() {
    val s = HookScripts.write(dir)
    for (p in listOf(s.claudeHook, s.codexHook)) { val (code, out) = run(p, "{}", false); assertEquals(0, code); assertEquals("", out) }
    assertTrue(seen.isEmpty())
  }

  fun testHookExitsZeroWhenServerIsGone() {
    val s = HookScripts.write(dir)
    http.stop(0)
    val (code, out) = run(s.claudeHook, "{}", true)
    assertEquals(0, code); assertEquals("", out)
  }

  fun testScriptsHoldNoTokenAndUseNoproxy() {
    val s = HookScripts.write(dir)
    for (p in listOf(s.claudeHook, s.codexHook, s.claudeStatusline)) {
      val t = Files.readString(p)
      assertTrue(t.contains("--noproxy 127.0.0.1") && t.contains("--max-time 1.5") && t.contains("--data-binary @-"))
      assertFalse(Regex("${HookEnv.TOKEN_HEADER}: *[A-Za-z0-9]{16,}").containsMatchIn(t))
    }
  }

  fun testStatuslinePrintsModelAndLimitsAndPosts() {
    val s = HookScripts.write(dir)
    val payload = """{"model":{"id":"x","display_name":"Opus"},
      "rate_limits":{"five_hour":{"used_percentage":12.5,"resets_at":1},"seven_day":{"used_percentage":30,"resets_at":2}}}"""
    val (code, out) = run(s.claudeStatusline, payload, true)
    assertEquals(0, code); assertEquals("Opus · 5h 12.5% · 7d 30%\n", out)
    assertEquals("/statusline/claude", seen.single().path); assertEquals("tab-9", seen.single().tab)
  }

  fun testStatuslinePrintsThePreviousLineFedTheSamePayloadAndStillPosts() {
    val s = HookScripts.write(dir)
    val payload = """{"model":{"display_name":"Opus"}}"""
    val (code, out) = run(s.claudeStatusline, payload, true, "printf 'mine: '; cat")
    assertEquals(0, code); assertEquals("mine: $payload\n", out)
    assertEquals(payload, seen.single().body)
  }

  fun testStatuslineFallsBackToItsOwnLineWhenThePreviousPrintsNothing() {
    val s = HookScripts.write(dir)
    val (_, out) = run(s.claudeStatusline, """{"model":{"display_name":"Opus"}}""", false, "exit 3")
    assertEquals("Opus\n", out)
  }

  fun testStatuslinePrintsClaudeWithoutDataAndDoesNotPostWithoutEnv() {
    val s = HookScripts.write(dir)
    val (code, out) = run(s.claudeStatusline, "", false)
    assertEquals(0, code); assertEquals("Claude\n", out)
    val (_, out2) = run(s.claudeStatusline, "not json", false)
    assertEquals("Claude\n", out2)
    assertTrue(seen.isEmpty())
  }

  /** The agents' global config points at the scripts, so they live outside the IDE's caches, under the user's home. */
  fun testDefaultDirIsUnderTheHome() {
    assertEquals(java.nio.file.Path.of(System.getProperty("user.home"), ".phantom", "ai"), HookScripts.defaultDir())
  }

  fun testSandboxKeepsTheScriptsOutOfTheHome() {
    System.setProperty(dev.phantom.ai.core.SystemDirs.CLAUDE_DIR_PROP, dir.toString())
    try {
      assertFalse(HookScripts.defaultDir().startsWith(java.nio.file.Path.of(System.getProperty("user.home"), ".phantom")))
    } finally { System.clearProperty(dev.phantom.ai.core.SystemDirs.CLAUDE_DIR_PROP) }
  }

  fun testWriteIsIdempotentAndExecutable() {
    val a = HookScripts.write(dir); val b = HookScripts.write(dir)
    assertEquals(a, b)
    assertTrue(Files.isExecutable(a.claudeHook))
  }
}
