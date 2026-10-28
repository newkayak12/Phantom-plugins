// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import com.intellij.openapi.progress.ProcessCanceledException
import dev.phantom.ai.usage.ClaudeUsage
import dev.phantom.ai.usage.CodexUsage
import dev.phantom.ai.usage.UsagePoller
import dev.phantom.ai.usage.UsageSources
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class UsagePollerTest {
  private class Fake(val onClaude: () -> ClaudeUsage?, val onCodex: () -> CodexUsage?) : UsageSources {
    var codexPolled = 0
    override fun claude(claudeDir: String?) = onClaude()
    override fun codex(codexHome: String?): CodexUsage? { codexPolled++; return onCodex() }
  }

  private fun poller(s: UsageSources) = UsagePoller(s, { null }, { null }, { 0L }, 60)

  @Test fun failingSourceBecomesNullOtherStillPolled() {
    val f = Fake({ throw IllegalStateException("claude down") }, { null })
    val p = poller(f)
    var published = 0
    p.listeners.add { published++ }
    p.pollOnce()
    assertNull(p.snapshot.claude)
    assertNull(p.snapshot.codex)
    assertTrue(f.codexPolled == 1)
    assertTrue(published == 1)
  }

  @Test fun processCanceledFromSourcePropagates() {
    val pce = ProcessCanceledException()
    val p = poller(Fake({ throw pce }, { null }))
    assertSame(pce, runCatching { p.pollOnce() }.exceptionOrNull())
  }
}
