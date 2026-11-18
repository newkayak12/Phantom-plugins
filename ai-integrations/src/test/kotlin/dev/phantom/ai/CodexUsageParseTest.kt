// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import dev.phantom.ai.usage.CodexLauncher
import dev.phantom.ai.usage.CodexUsageClient
import dev.phantom.ai.usage.RpcChannel
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexUsageParseTest {
  private fun fx(n: String) = File("src/test/resources/usage/$n").readText()

  /** Fake app-server: answers initialize (id 1) and account/rateLimits/read (id 2) with [rateLimits] or an error. */
  private class FakeChannel(val result: String?, val error: Boolean = false, val silent: Boolean = false) : RpcChannel {
    val sent = mutableListOf<String>()
    var closed = false
    private val out = java.util.ArrayDeque<String>()
    override fun send(line: String) {
      sent += line
      if (silent) return
      if (line.contains("\"initialize\"")) out += """{"jsonrpc":"2.0","id":1,"result":{}}"""
      if (line.contains("account/rateLimits/read")) out += when {
        error -> """{"jsonrpc":"2.0","id":2,"error":{"code":-1,"message":"not logged in"}}"""
        else -> """{"jsonrpc":"2.0","id":2,"result":$result}""".also { out += "not json noise" }
      }
    }
    override fun readLine(timeoutMs: Long): String? = out.poll() ?: run { if (timeoutMs > 0) Thread.sleep(minOf(timeoutMs, 20)); null }
    override fun close() { closed = true }
  }

  @Test fun systemDefaultHomeFollowsRedirectProperty() {
    System.setProperty("phantom.ai.codexDir", "/sb/codex")
    try {
      var h: String? = null
      CodexUsageClient(CodexLauncher { x -> h = x; FakeChannel(fx("codex-rpc-rate-limits.json")) }, { 5 }).fetch(null)
      assertEquals("/sb/codex", h)
    } finally { System.clearProperty("phantom.ai.codexDir") }
  }

  @Test fun parsesRpcWindows() {
    val u = CodexUsageClient.parseRpc(fx("codex-rpc-rate-limits.json"), 5)!!
    assertEquals(10.4, u.session!!.usedPercent, 0.001); assertEquals(300, u.session!!.windowMinutes)
    assertEquals(1790000000_000L, u.session!!.resetsAtMs)
    assertEquals(3.0, u.weekly!!.usedPercent, 0.001); assertEquals(10080, u.weekly!!.windowMinutes)
  }

  @Test fun classifiesByDurationNotPosition() {
    val u = CodexUsageClient.parseRpc(fx("codex-rpc-swapped-windows.json"), 5)!!
    assertEquals(7.0, u.session!!.usedPercent, 0.001); assertEquals(300, u.session!!.windowMinutes)
    assertEquals(40.0, u.weekly!!.usedPercent, 0.001)
  }

  @Test fun rpcSuccessStopsProcess() {
    val ch = FakeChannel(fx("codex-rpc-rate-limits.json"))
    var codexHome: String? = null
    val c = CodexUsageClient(CodexLauncher { h -> codexHome = h; ch }, { 5 }, { "/dflt" })
    val u = c.fetch("/acct/home")!!
    assertEquals("/acct/home", codexHome)
    assertEquals(10.4, u.session!!.usedPercent, 0.001)
    assertTrue(ch.closed)
    assertTrue(ch.sent.any { it.contains("\"initialized\"") })
  }

  @Test fun nullHomeUsesDefault() {
    var h: String? = null
    CodexUsageClient(CodexLauncher { x -> h = x; FakeChannel(fx("codex-rpc-rate-limits.json")) }, { 5 }, { "/dflt" }).fetch(null)
    assertEquals("/dflt", h)
  }

  @Test fun rpcErrorOrLauncherFailureIsUnavailable() {
    val ch = FakeChannel(null, error = true)
    assertNull(CodexUsageClient(CodexLauncher { ch }, { 5 }).fetch("/acct/home"))
    assertTrue(ch.closed)
    assertNull(CodexUsageClient(CodexLauncher { throw IOException("no codex") }, { 5 }).fetch("/acct/home"))
  }

  @Test fun silentServerTimesOutAndIsClosed() {
    val ch = FakeChannel(null, silent = true)
    val t0 = System.nanoTime()
    assertNull(CodexUsageClient(CodexLauncher { ch }, { 5 }, timeoutMs = 150).fetch("/acct/home"))
    assertTrue(ch.closed); assertTrue((System.nanoTime() - t0) / 1_000_000 < 3000)
  }
}
