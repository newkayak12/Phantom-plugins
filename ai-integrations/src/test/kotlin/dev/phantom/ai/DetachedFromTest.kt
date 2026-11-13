// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.AgentSession
import dev.phantom.ai.terminal.DetachedSessions
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

/** What a project window opening reattaches; with nothing detached (the IDE starting) nothing is looked up. */
class DetachedFromTest {
  @Test fun nothingDetachedIsEmpty() {
    val sessions = listOf(AgentSession("a", "/w/a", AgentKind.CLAUDE), AgentSession("b", "/w/b", AgentKind.CODEX, detachedFrom = "/w/b"))
    assertEquals(emptyList<AgentSession>(), DetachedSessions.detachedFrom(sessions, "/w/b"))
    assertEquals(emptyList<AgentSession>(), DetachedSessions.detachedFrom(emptyList(), "/w/b"))
  }

  @Test fun onlyThoseDetachedFromThisWindow() {
    val dir = Files.createTempDirectory("win")
    try {
      val mine = AgentSession("d1", "/w/any", AgentKind.CLAUDE, detached = true, detachedFrom = "$dir/./")
      val other = AgentSession("d2", dir.toString(), AgentKind.CLAUDE, detached = true, detachedFrom = "/w/other-window")
      val attached = AgentSession("d3", "/w/x", AgentKind.CODEX, detachedFrom = dir.toString())
      assertEquals(listOf(mine), DetachedSessions.detachedFrom(listOf(mine, other, attached), dir.toRealPath().toString()))
    } finally {
      dir.toFile().deleteRecursively()
    }
  }
}
