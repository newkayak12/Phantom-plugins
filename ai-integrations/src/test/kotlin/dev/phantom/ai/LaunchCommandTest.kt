// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import dev.phantom.ai.accounts.AgentAccount
import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.AiStateData
import dev.phantom.ai.launch.LaunchCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LaunchCommandTest {
  @Test fun claudeLaunch() = assertEquals("claude", LaunchCommand.launch(AgentKind.CLAUDE))

  @Test fun claudeResume() = assertEquals("claude '--resume' 'abc-1'", LaunchCommand.resume(AgentKind.CLAUDE, "abc-1"))

  @Test fun codexLaunch() = assertEquals("codex", LaunchCommand.launch(AgentKind.CODEX))

  @Test fun codexResume() = assertEquals("codex resume 'abc-1'", LaunchCommand.resume(AgentKind.CODEX, "abc-1"))

  @Test fun skippingPermissionsOnlyWhenAsked() {
    assertEquals("claude '--dangerously-skip-permissions'", LaunchCommand.launch(AgentKind.CLAUDE, skipPermissions = true))
    assertEquals(
      "claude '--dangerously-skip-permissions' '--resume' 'abc-1'",
      LaunchCommand.resume(AgentKind.CLAUDE, "abc-1", skipPermissions = true),
    )
    assertEquals("codex '--dangerously-bypass-approvals-and-sandbox'", LaunchCommand.launch(AgentKind.CODEX, skipPermissions = true))
    assertEquals(
      "codex resume '--dangerously-bypass-approvals-and-sandbox' 'abc-1'",
      LaunchCommand.resume(AgentKind.CODEX, "abc-1", skipPermissions = true),
    )
  }

  // The setting is off until the user turns it on: new agents ask before running commands and editing files.
  @Test fun skippingPermissionsIsOffByDefault() = assertFalse(AiStateData().skipPermissions)

  @Test fun claudeGetsPhantomMcpFromItsConfigFile() {
    val link = dev.phantom.ai.launch.McpLink("http://127.0.0.1:9/mcp?tabId=t", "PHANTOM_AGENT_HOOK_TOKEN", "/tmp/a b/t.json")
    assertEquals("claude '--mcp-config' '/tmp/a b/t.json'", LaunchCommand.launch(AgentKind.CLAUDE, link))
    assertEquals(
      "claude '--dangerously-skip-permissions' '--mcp-config' '/tmp/a b/t.json' '--resume' 'abc-1'",
      LaunchCommand.resume(AgentKind.CLAUDE, "abc-1", link, skipPermissions = true),
    )
  }

  @Test fun codexGetsPhantomMcpAsConfigOverridesBeforeItsSubcommand() {
    val link = dev.phantom.ai.launch.McpLink("http://127.0.0.1:9/mcp?tabId=t", "PHANTOM_AGENT_HOOK_TOKEN", "/tmp/t.json")
    val mcp = "'-c' 'mcp_servers.phantom.url=\"http://127.0.0.1:9/mcp?tabId=t\"' '-c' 'mcp_servers.phantom.bearer_token_env_var=\"PHANTOM_AGENT_HOOK_TOKEN\"'"
    assertEquals("codex $mcp '--dangerously-bypass-approvals-and-sandbox'", LaunchCommand.launch(AgentKind.CODEX, link, skipPermissions = true))
    assertEquals("codex $mcp resume 'abc-1'", LaunchCommand.resume(AgentKind.CODEX, "abc-1", link))
  }

  @Test fun aPromptStartsTheAgentOnIt() {
    val link = dev.phantom.ai.launch.McpLink("http://127.0.0.1:9/mcp?tabId=t", "PHANTOM_AGENT_HOOK_TOKEN", "/tmp/t.json")
    // before Claude's options: --mcp-config would take it as another config file
    assertEquals(
      "claude 'fix it'\\''s tests' '--dangerously-skip-permissions' '--mcp-config' '/tmp/t.json'",
      LaunchCommand.launch(AgentKind.CLAUDE, link, "fix it's tests", skipPermissions = true),
    )
    assertEquals("codex 'a\nb'", LaunchCommand.launch(AgentKind.CODEX, prompt = "a\nb"))
  }

  @Test fun idIsSingleQuoteEscaped() {
    assertEquals("'a'\\''b'", LaunchCommand.quote("a'b"))
    assertEquals("claude '--resume' ''\\''; rm -rf ~'\\'''", LaunchCommand.resume(AgentKind.CLAUDE, "'; rm -rf ~'"))
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

  @Test fun agentBinDirPrefixesEveryCommand() {
    System.setProperty("phantom.ai.agentBinDir", "/sb/bin")
    try {
      assertEquals("'/sb/bin/claude'", LaunchCommand.launch(AgentKind.CLAUDE))
      assertEquals("'/sb/bin/claude' '--resume' 'a'", LaunchCommand.resume(AgentKind.CLAUDE, "a"))
      assertEquals("'/sb/bin/codex'", LaunchCommand.launch(AgentKind.CODEX))
      assertEquals("'/sb/bin/codex' resume 'a'", LaunchCommand.resume(AgentKind.CODEX, "a"))
      assertEquals("'/sb/bin/claude' 'auth' 'login' '--claudeai'", LaunchCommand.login(AgentKind.CLAUDE))
      assertEquals("'/sb/bin/codex' 'login'", LaunchCommand.login(AgentKind.CODEX))
    } finally { System.clearProperty("phantom.ai.agentBinDir") }
    assertEquals("claude", LaunchCommand.launch(AgentKind.CLAUDE))
  }
}
