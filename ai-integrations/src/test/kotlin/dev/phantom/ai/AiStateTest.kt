// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import com.intellij.util.xmlb.XmlSerializer
import dev.phantom.ai.core.AccountEntry
import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.AiState
import dev.phantom.ai.core.AiStateData
import dev.phantom.ai.core.Group
import dev.phantom.ai.core.GroupTree
import dev.phantom.ai.core.Repo
import dev.phantom.ai.sidebar.SessionRow
import dev.phantom.ai.sidebar.SidebarModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiStateTest {
  @Test
  fun `state round trips through xml`() {
    val a = AiState()
    a.setTree(GroupTree.normalize(listOf(Group("g1", "One", null, 0, true), Group("g2", "Two", "g1", 0)), listOf(Repo("/r", "g2", 0))))
    a.worktree("/r/wt").apply { column = "Done"; order = 2; userName = "mine" }
    a.rememberSession("/r/wt", AgentKind.CLAUDE, "abc", "hello")
    a.state.accounts.add(AccountEntry().apply { provider = "CLAUDE"; id = "w"; label = "Work"; dir = "/d" })
    a.setActiveAccount(AgentKind.CLAUDE, "w")
    a.state.boardColumns = mutableListOf("A", "B")

    val xml = XmlSerializer.serialize(a.state)
    val b = AiState()
    b.loadState(XmlSerializer.deserialize(xml, AiStateData::class.java))

    val t = b.tree()
    assertEquals(listOf("g1", "g2"), t.groups.map { it.id })
    assertEquals(true, t.groups.first().collapsed)
    assertEquals("g1", t.groups.last().parentId)
    assertEquals("g2", t.repos.single().groupId)
    assertEquals("Done", b.worktree("/r/wt").column)
    assertEquals("mine", b.worktree("/r/wt").userName)
    assertEquals("hello", b.sessionsOf("/r/wt").single().firstPrompt)
    assertEquals("Work", b.accountsOf(AgentKind.CLAUDE).single().label)
    assertEquals("w", b.activeAccountId(AgentKind.CLAUDE))
    assertEquals(listOf("A", "B"), b.boardColumns())
  }

  @Test
  fun `defaults are the four columns and System default accounts`() {
    val a = AiState()
    assertEquals(listOf("Todo", "In progress", "In review", "Done"), a.boardColumns())
    assertNull(a.activeAccountId(AgentKind.CODEX))
  }

  @Test
  fun `firstPrompt is filled by a later event and never overwritten`() {
    val a = AiState()
    a.rememberSession("/r/wt", AgentKind.CLAUDE, "abc", null)
    assertNull(a.sessionsOf("/r/wt").single().firstPrompt)
    a.rememberSession("/r/wt", AgentKind.CLAUDE, "abc", "hello")
    a.rememberSession("/r/wt", AgentKind.CLAUDE, "abc", "second")
    assertEquals("hello", a.sessionsOf("/r/wt").single().firstPrompt)
  }

  @Test
  fun `title survives an IDE restart`() {
    val a = AiState()
    a.rememberSession("/r/wt", AgentKind.CLAUDE, "abc", null)
    a.rememberSession("/r/wt", AgentKind.CLAUDE, "abc", "hello")

    val b = AiState()
    b.loadState(XmlSerializer.deserialize(XmlSerializer.serialize(a.state), AiStateData::class.java))

    val entry = b.sessionsOf("/r/wt").single()
    assertEquals("hello", entry.firstPrompt)
    assertEquals("hello", SessionRow.title(SidebarModel.saved(entry)!!))
  }
}
