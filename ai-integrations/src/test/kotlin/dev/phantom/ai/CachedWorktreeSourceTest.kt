// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import dev.phantom.ai.sidebar.CachedWorktreeSource
import dev.phantom.ai.sidebar.Worktree
import org.junit.Assert.assertEquals
import org.junit.Test

class CachedWorktreeSourceTest {
  private var now = 0L
  private val runs = mutableListOf<String>()
  private val source = CachedWorktreeSource({ repo -> runs += repo; listOf(Worktree(repo, "b${runs.size}", true)) }, ttlMs = 1000) { now }

  @Test fun gitRunsOncePerRepositoryWithinTheTtl() {
    repeat(5) { source.list("/r") }
    source.list("/s")
    assertEquals(listOf("/r", "/s"), runs)
    now = 999
    assertEquals("b1", source.list("/r").single().branch)
    now = 1000
    assertEquals("b3", source.list("/r").single().branch)
  }

  @Test fun invalidateReadsAgainAtOnce() {
    source.list("/r")
    source.invalidate("/r")
    source.list("/r")
    source.invalidateAll()
    source.list("/r")
    assertEquals(listOf("/r", "/r", "/r"), runs)
  }
}
