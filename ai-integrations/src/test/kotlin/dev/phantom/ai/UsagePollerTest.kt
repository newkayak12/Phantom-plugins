// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import com.intellij.openapi.progress.ProcessCanceledException
import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.AgentSession
import dev.phantom.ai.usage.CodexUsage
import dev.phantom.ai.usage.ProcessCodexLauncher
import dev.phantom.ai.usage.UsageStarter
import dev.phantom.ai.usage.UsagePoller
import dev.phantom.ai.usage.UsageSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.nio.file.Files

class UsagePollerTest {
  private class Fake(val onCodex: () -> CodexUsage?) : UsageSources {
    var codexPolled = 0
    override fun codex(codexHome: String?): CodexUsage? { codexPolled++; return onCodex() }
  }

  @Test fun failingSourceBecomesNullAndIsPublished() {
    val p = UsagePoller(Fake { throw IllegalStateException("codex down") }, { null }, { 0L })
    var published = 0
    p.listeners.add { published++ }
    p.pollOnce()
    assertNull(p.snapshot.claude)
    assertNull(p.snapshot.codex)
    assertEquals(1, published)
  }

  @Test fun processCanceledFromSourcePropagates() {
    val pce = ProcessCanceledException()
    val p = UsagePoller(Fake { throw pce }, { null }, { 0L })
    assertSame(pce, runCatching { p.pollOnce() }.exceptionOrNull())
  }

  @Test fun tickSkipsWhileInactive() {
    val f = Fake { null }
    var active = false
    val p = UsagePoller(f, { null }, { 0L }, 300, { active }, { true }, { true })
    p.listeners.add {}
    p.tick()
    assertEquals(0, f.codexPolled)
    active = true
    repeat(2) { p.tick() }
    assertEquals(2, f.codexPolled)
  }

  // each look starts a codex process: none without a widget, a Codex session or the codex binary
  @Test fun tickSkipsWithoutWidgetCodexSessionOrBinary() {
    val f = Fake { null }
    var inUse = false
    var installed = true
    val p = UsagePoller(f, { null }, { 0L }, 300, { true }, { inUse }, { installed })
    inUse = true
    p.tick()
    assertEquals("no widget listens", 0, f.codexPolled)
    p.listeners.add {}
    inUse = false
    p.tick()
    assertEquals("no Codex session", 0, f.codexPolled)
    inUse = true
    installed = false
    p.tick()
    assertEquals("codex not installed", 0, f.codexPolled)
    installed = true
    p.tick()
    assertEquals(1, f.codexPolled)
  }

  @Test fun onlyACodexSessionStartsTheCodexTimer() {
    assertFalse(UsageStarter.needed(null))
    assertFalse(UsageStarter.needed(AgentSession("t", "/w", AgentKind.CLAUDE)))
    assertTrue(UsageStarter.needed(AgentSession("t", "/w", AgentKind.CODEX)))
  }

  @Test fun codexInstalledLooksOnThePath() {
    val dir = Files.createTempDirectory("codex-bin")
    try {
      assertFalse(ProcessCodexLauncher.installed("codex-phantom-test", dir.toString()))
      val exe = Files.createFile(dir.resolve("codex-phantom-test")).toFile().apply { setExecutable(true) }
      assertTrue(ProcessCodexLauncher.installed("codex-phantom-test", dir.toString()))
      assertTrue(ProcessCodexLauncher.installed(exe.absolutePath, null))
      assertFalse(ProcessCodexLauncher.installed(dir.resolve("absent").toString(), null))
    } finally {
      dir.toFile().deleteRecursively()
    }
  }

  // Claude usage has no poll at all: it stays unknown until a running Claude session sends a statusLine payload.
  @Test fun claudeOnlyFromStatusLine() {
    val p = UsagePoller(Fake { null }, { null }, { 7L })
    p.pollOnce()
    assertNull(p.snapshot.claude)
    p.onStatusLine("""{"rate_limits":{"five_hour":{"used_percentage":30,"resets_at":1790000000}}}""")
    assertEquals(30.0, p.snapshot.claude!!.session!!.usedPercent, 0.0001)
    assertEquals(7L, p.snapshot.claude!!.updatedAtMs)
  }
}
