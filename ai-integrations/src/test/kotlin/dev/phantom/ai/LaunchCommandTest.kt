// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import dev.phantom.ai.accounts.AccountsService
import dev.phantom.ai.accounts.AgentAccount
import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.AiState
import dev.phantom.ai.core.AiStateData
import dev.phantom.ai.launch.LaunchCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

class LaunchCommandTest {
  @Test fun claudeLaunch() = assertEquals("claude '--dangerously-skip-permissions'", LaunchCommand.launch(AgentKind.CLAUDE))

  @Test fun claudeResume() =
    assertEquals("claude '--dangerously-skip-permissions' '--resume' 'abc-1'", LaunchCommand.resume(AgentKind.CLAUDE, "abc-1"))

  @Test fun codexLaunch() =
    assertEquals("codex '--dangerously-bypass-approvals-and-sandbox'", LaunchCommand.launch(AgentKind.CODEX))

  @Test fun codexResume() =
    assertEquals("codex resume '--dangerously-bypass-approvals-and-sandbox' 'abc-1'", LaunchCommand.resume(AgentKind.CODEX, "abc-1"))

  @Test fun idIsSingleQuoteEscaped() {
    assertEquals("'a'\\''b'", LaunchCommand.quote("a'b"))
    assertEquals("claude '--dangerously-skip-permissions' '--resume' ''\\''; rm -rf ~'\\'''", LaunchCommand.resume(AgentKind.CLAUDE, "'; rm -rf ~'"))
  }

  @Test fun addedAccountSetsItsVariableOnly() {
    val hook = mapOf("PHANTOM_TAB_ID" to "t1")
    val c = AgentAccount(AgentKind.CLAUDE, "i", "A", "/x/c")
    assertEquals(mapOf("CLAUDE_CONFIG_DIR" to "/x/c", "PHANTOM_TAB_ID" to "t1"), LaunchCommand.env(AgentKind.CLAUDE, c, hook))
    val x = AgentAccount(AgentKind.CODEX, "i", "A", "/x/x")
    assertEquals(mapOf("CODEX_HOME" to "/x/x", "PHANTOM_TAB_ID" to "t1"), LaunchCommand.env(AgentKind.CODEX, x, hook))
  }

  @Test fun systemDefaultHasNoAccountVariableButKeepsHookEnv() {
    val hook = mapOf("PHANTOM_TAB_ID" to "t1")
    for (k in AgentKind.entries) {
      val env = LaunchCommand.env(k, null, hook)
      assertEquals(hook, env)
      assertFalse(env.containsKey("CLAUDE_CONFIG_DIR"))
      assertFalse(env.containsKey("CODEX_HOME"))
    }
  }

  @Test fun redirectedSystemDefaultSetsTheRedirectedDirOnly() {
    System.setProperty("phantom.ai.claudeDir", "/sb/c"); System.setProperty("phantom.ai.codexDir", "/sb/x")
    try {
      val hook = mapOf("PHANTOM_TAB_ID" to "t1")
      assertEquals(mapOf("CLAUDE_CONFIG_DIR" to "/sb/c", "PHANTOM_TAB_ID" to "t1"), LaunchCommand.env(AgentKind.CLAUDE, null, hook))
      assertEquals(mapOf("CODEX_HOME" to "/sb/x", "PHANTOM_TAB_ID" to "t1"), LaunchCommand.env(AgentKind.CODEX, null, hook))
      val a = AgentAccount(AgentKind.CLAUDE, "i", "A", "/x/c")
      assertEquals(mapOf("CLAUDE_CONFIG_DIR" to "/x/c"), LaunchCommand.accountEnv(AgentKind.CLAUDE, a))
    } finally { System.clearProperty("phantom.ai.claudeDir"); System.clearProperty("phantom.ai.codexDir") }
  }

  @Test fun loginCommands() {
    assertEquals("claude 'auth' 'login' '--claudeai'", LaunchCommand.login(AgentKind.CLAUDE))
    assertEquals("codex 'login'", LaunchCommand.login(AgentKind.CODEX))
  }

  @Test fun noOffSwitch() {
    val bad = Regex("skip|bypass|permission|yolo|safe|confirm|ask|approv|sandbox", RegexOption.IGNORE_CASE)
    // no Boolean parameter on any public function of LaunchCommand or AccountsService
    for (c in listOf(LaunchCommand::class.java, AccountsService::class.java)) {
      for (m in c.declaredMethods.filter { Modifier.isPublic(it.modifiers) }) {
        assertFalse("${c.simpleName}.${m.name} takes a Boolean", m.parameterTypes.any { it == Boolean::class.javaPrimitiveType || it == Boolean::class.javaObjectType })
      }
    }
    // no property of AiState, its state classes and the account classes is named like an off-switch
    val classes = listOf(AiState::class.java, AiStateData::class.java, AgentAccount::class.java) +
      listOf("AccountEntry", "ActiveAccountEntry", "SessionEntry", "WorktreeEntry", "GroupEntry", "RepoEntry").map { Class.forName("dev.phantom.ai.core.$it") }
    for (c in classes) {
      for (f in c.declaredFields) assertFalse("${c.simpleName}.${f.name}", bad.containsMatchIn(f.name))
      for (m in c.declaredMethods.filter { Modifier.isPublic(it.modifiers) && (it.name.startsWith("get") || it.name.startsWith("is")) })
        assertFalse("${c.simpleName}.${m.name}", bad.containsMatchIn(m.name))
    }
    assertTrue(true)
  }

  @Test fun agentBinDirPrefixesEveryCommand() {
    System.setProperty("phantom.ai.agentBinDir", "/sb/bin")
    try {
      assertEquals("'/sb/bin/claude' '--dangerously-skip-permissions'", LaunchCommand.launch(AgentKind.CLAUDE))
      assertEquals("'/sb/bin/claude' '--dangerously-skip-permissions' '--resume' 'a'", LaunchCommand.resume(AgentKind.CLAUDE, "a"))
      assertEquals("'/sb/bin/codex' '--dangerously-bypass-approvals-and-sandbox'", LaunchCommand.launch(AgentKind.CODEX))
      assertEquals("'/sb/bin/codex' resume '--dangerously-bypass-approvals-and-sandbox' 'a'", LaunchCommand.resume(AgentKind.CODEX, "a"))
      assertEquals("'/sb/bin/claude' 'auth' 'login' '--claudeai'", LaunchCommand.login(AgentKind.CLAUDE))
      assertEquals("'/sb/bin/codex' 'login'", LaunchCommand.login(AgentKind.CODEX))
    } finally { System.clearProperty("phantom.ai.agentBinDir") }
    assertEquals("claude '--dangerously-skip-permissions'", LaunchCommand.launch(AgentKind.CLAUDE))
  }
}
