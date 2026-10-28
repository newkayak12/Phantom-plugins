// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import com.intellij.openapi.Disposable
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.AgentSession
import dev.phantom.ai.core.AiState
import dev.phantom.ai.core.SessionStore
import dev.phantom.ai.core.SystemDirs
import dev.phantom.ai.launch.LaunchCommand
import dev.phantom.ai.terminal.AgentTerminalService
import dev.phantom.ai.terminal.SessionJsonl
import dev.phantom.ai.terminal.StartedTerminal
import dev.phantom.ai.terminal.TerminalStarter
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import javax.swing.JPanel
import org.junit.Assert.assertNotEquals

/**
 * Two hook-less Claude tabs of one worktree each get their own .jsonl id. No real agent runs: the terminal starter is
 * replaced by a recording fake (so no `claude` process starts), and phantom.ai.claudeDir points at a temp dir (never ~/.claude).
 */
class ResumeJsonlInIdeTest : BasePlatformTestCase() {
  private val worktree = "/w/resume-jsonl/wt"
  private val commands = mutableListOf<String>()
  private lateinit var claudeDir: Path
  private var savedProp: String? = null

  override fun setUp() {
    super.setUp()
    savedProp = System.getProperty(SystemDirs.CLAUDE_DIR_PROP)
    claudeDir = Files.createTempDirectory("phantom-ai-resume-claude")
    System.setProperty(SystemDirs.CLAUDE_DIR_PROP, claudeDir.toString())
    AgentTerminalService.getInstance(project).replaceStarter(TerminalStarter { _: Disposable, _, _, command ->
      commands.add(command)
      StartedTerminal(JPanel())
    })
  }

  override fun tearDown() {
    try {
      ToolWindowManager.getInstance(project).getToolWindow("AI Integrations")?.contentManager?.removeAllContents(true)
      SessionStore.getInstance().all().forEach { SessionStore.getInstance().remove(it.tabId) }
      AiState.getInstance().state.sessions.removeAll { it.worktree == worktree }
      claudeDir.toFile().deleteRecursively()
    } finally {
      if (savedProp == null) System.clearProperty(SystemDirs.CLAUDE_DIR_PROP) else System.setProperty(SystemDirs.CLAUDE_DIR_PROP, savedProp!!)
      super.tearDown()
    }
  }

  private fun newJsonl(): String {
    val id = UUID.randomUUID().toString()
    val dir = claudeDir.resolve("projects").resolve(SessionJsonl.encode(worktree))
    Files.createDirectories(dir)
    Files.writeString(dir.resolve("$id.jsonl"), "{}")
    return id
  }

  /** Closes the only open tab (each test step closes its tab before the next launch). */
  private fun closeOnlyTab() {
    val cm = ToolWindowManager.getInstance(project).getToolWindow("AI Integrations")!!.contentManager
    cm.removeContent(cm.contents.single(), true)
  }

  fun testTwoHooklessTabsGetTwoDifferentResumeIds() {
    val service = AgentTerminalService.getInstance(project)
    service.launch(worktree, AgentKind.CLAUDE)!!
    val id1 = newJsonl()
    closeOnlyTab()
    service.launch(worktree, AgentKind.CLAUDE)!!
    val id2 = newJsonl()
    closeOnlyTab()

    assertNotEquals(id1, id2)
    assertEquals(setOf(id1, id2), AiState.getInstance().sessionsOf(worktree).mapNotNull { it.sessionId }.toSet())

    commands.clear()
    AiState.getInstance().sessionsOf(worktree).forEach { e ->
      service.resume(AgentSession("gone-${e.sessionId}", worktree, AgentKind.CLAUDE, sessionId = e.sessionId))
    }
    assertEquals(setOf(LaunchCommand.resume(AgentKind.CLAUDE, id1), LaunchCommand.resume(AgentKind.CLAUDE, id2)), commands.toSet())
    assertEquals(2, commands.toSet().size)
  }

  /** Both tabs open at once, b written after a (and modified last); the tabs close in the given order. */
  private fun concurrentTabs(closeFirstIsA: Boolean, bindWhileOpen: Boolean) {
    val service = AgentTerminalService.getInstance(project)
    val tabA = service.launch(worktree, AgentKind.CLAUDE)!!
    Thread.sleep(30)
    val idA = newJsonl()
    Thread.sleep(30)
    val tabB = service.launch(worktree, AgentKind.CLAUDE)!!
    Thread.sleep(30)
    val idB = newJsonl()
    if (bindWhileOpen) {
      service.bindOpenSessions()
      assertEquals(idA, SessionStore.getInstance().get(tabA)!!.sessionId)
      assertEquals(idB, SessionStore.getInstance().get(tabB)!!.sessionId)
    }
    val cm = ToolWindowManager.getInstance(project).getToolWindow("AI Integrations")!!.contentManager
    val byTab = mapOf(tabA to cm.contents[0], tabB to cm.contents[1])
    val order = if (closeFirstIsA) listOf(tabA, tabB) else listOf(tabB, tabA)
    order.forEach { cm.removeContent(byTab.getValue(it), true) }

    assertEquals(setOf(idA, idB), AiState.getInstance().sessionsOf(worktree).mapNotNull { it.sessionId }.toSet())
    commands.clear()
    listOf(idA, idB).forEach { service.resume(AgentSession("gone-$it", worktree, AgentKind.CLAUDE, sessionId = it)) }
    assertEquals(listOf(LaunchCommand.resume(AgentKind.CLAUDE, idA), LaunchCommand.resume(AgentKind.CLAUDE, idB)), commands.toList())
  }

  fun testConcurrentTabsKeepOwnIdsClosingFirstOneFirst() = concurrentTabs(closeFirstIsA = true, bindWhileOpen = false)

  fun testConcurrentTabsKeepOwnIdsClosingSecondOneFirst() = concurrentTabs(closeFirstIsA = false, bindWhileOpen = false)

  fun testConcurrentTabsBoundWhileOpenKeepOwnIdsEitherCloseOrder() {
    concurrentTabs(closeFirstIsA = false, bindWhileOpen = true)
  }
}
