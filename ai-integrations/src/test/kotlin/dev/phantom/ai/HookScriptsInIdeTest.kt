// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.AgentSession
import dev.phantom.ai.core.AgentStatus
import dev.phantom.ai.core.SessionStore
import dev.phantom.ai.hooks.HookEnv
import dev.phantom.ai.hooks.HookScripts
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** The generated scripts run with /bin/sh against the real HookServer: header names, env names, paths, body. */
class HookScriptsInIdeTest : BasePlatformTestCase() {
  private val wt = "/tmp/phantom-script-e2e-wt"

  override fun tearDown() { try { SessionStore.getInstance().remove("e2eTab") } finally { super.tearDown() } }

  private fun run(script: java.nio.file.Path, stdin: String): String {
    val pb = ProcessBuilder("/bin/sh", script.toString()).redirectErrorStream(true)
    pb.environment().putAll(HookEnv.forTab(wt, "e2eTab"))
    val p = pb.start()
    p.outputStream.use { it.write(stdin.toByteArray()) }
    val out = p.inputStream.readAllBytes().decodeToString()
    assertTrue(p.waitFor(10, TimeUnit.SECONDS)); assertEquals(0, p.exitValue())
    return out
  }

  fun testScriptsReachRealServer() {
    val dir = Files.createTempDirectory("phantom-e2e")
    try {
      val s = HookScripts.write(dir)
      SessionStore.getInstance().put(AgentSession("e2eTab", wt, AgentKind.CLAUDE))
      run(s.claudeHook, """{"hook_event_name":"UserPromptSubmit","session_id":"sx","prompt":"hello"}""")
      val a = SessionStore.getInstance().get("e2eTab")!!
      assertEquals(AgentStatus.WORKING, a.status); assertEquals("hello", a.firstPrompt); assertEquals("sx", a.sessionId)
      run(s.claudeHook, """{"hook_event_name":"Stop","session_id":"sx"}""")
      assertEquals(AgentStatus.DONE, SessionStore.getInstance().get("e2eTab")!!.status)
      var got: String? = null
      dev.phantom.ai.hooks.HookServer.getInstance().statuslineListeners.add { tab, body -> if (tab == "e2eTab") got = body }
      val line = run(s.claudeStatusline, """{"model":{"display_name":"Opus"}}""")
      assertEquals("Opus\n", line); assertTrue(got!!.contains("Opus"))
    } finally { dir.toFile().deleteRecursively() }
  }
}
