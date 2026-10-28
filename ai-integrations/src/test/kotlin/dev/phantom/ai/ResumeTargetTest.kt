// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.terminal.ResumeTarget
import dev.phantom.ai.terminal.SessionJsonl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ResumeTargetTest {
  @Test fun hookIdBeatsJsonl() {
    var asked = false
    val t = ResumeTarget.resolve(AgentKind.CLAUDE, "hook-1") { asked = true; listOf("j1") }
    assertEquals(ResumeTarget.Id("hook-1", true), t)
    assertFalse("jsonl must not be read when the hook gave an id", asked)
  }

  @Test fun claudeFallsBackToNewestJsonl() =
    assertEquals(ResumeTarget.Id("j1", false), ResumeTarget.resolve(AgentKind.CLAUDE, null) { listOf("j1", "j0") })

  @Test fun blankHookIdCountsAsNone() =
    assertEquals(ResumeTarget.Id("j1", false), ResumeTarget.resolve(AgentKind.CLAUDE, " ") { listOf("j1") })

  @Test fun noIdMeansNone() = assertEquals(ResumeTarget.None, ResumeTarget.resolve(AgentKind.CLAUDE, null) { emptyList() })

  @Test fun codexHasNoJsonlFallback() =
    assertEquals(ResumeTarget.None, ResumeTarget.resolve(AgentKind.CODEX, null) { listOf("j1") })

  private fun f(id: String, created: Long, modified: Long = created) = SessionJsonl.JsonlFile(id, created, modified)

  @Test fun claimedIdsAreSkipped() =
    assertEquals(
      ResumeTarget.Id("j0", false),
      ResumeTarget.resolve(AgentKind.CLAUDE, null, { listOf(f("j1", 20), f("j0", 10)) }, setOf("j1"), null),
    )

  @Test fun filesOlderThanTheSessionAreSkipped() {
    val files = { listOf(f("old", 1_000), f("fresh", 9_000)) }
    assertEquals(ResumeTarget.Id("fresh", false), ResumeTarget.resolve(AgentKind.CLAUDE, null, files, emptySet(), 10_000))
    assertEquals(ResumeTarget.None, ResumeTarget.resolve(AgentKind.CLAUDE, null, files, setOf("fresh"), 10_000))
    assertEquals(ResumeTarget.None, ResumeTarget.resolve(AgentKind.CLAUDE, null, files, emptySet(), 20_000))
  }

  @Test fun twoHooklessSessionsInOneWorktreeGetDifferentIds() {
    val t0 = 100_000L
    val t1 = 200_000L
    val first = ResumeTarget.resolve(AgentKind.CLAUDE, null, { listOf(f("a", t0 + 1)) }, emptySet(), t0)
    assertEquals(ResumeTarget.Id("a", false), first)
    val both = { listOf(f("a", t0 + 1), f("b", t1 + 1)) }
    val second = ResumeTarget.resolve(AgentKind.CLAUDE, null, both, setOf("a"), t1)
    assertEquals(ResumeTarget.Id("b", false), second)
    assertNotEquals(first, second)
  }

  @Test fun hookIdStillWins() {
    var asked = false
    val t = ResumeTarget.resolve(AgentKind.CLAUDE, "hook-1", { asked = true; listOf(f("j1", 1)) }, setOf("hook-1"), 0)
    assertEquals(ResumeTarget.Id("hook-1", true), t)
    assertFalse(asked)
  }
}
