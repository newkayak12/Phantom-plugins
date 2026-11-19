// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.components.service
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.wm.ToolWindowEP
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.ai.core.AiState
import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.AgentSession
import dev.phantom.ai.core.SessionStore
import dev.phantom.ai.hooks.AgentTeamsPoller

class AiPluginInIdeTest : BasePlatformTestCase() {
  fun testWorkspacesPluginIsLoaded() {
    val plugin = PluginManagerCore.getPlugin(PluginId.getId("dev.phantom.workspaces"))
    assertNotNull("dev.phantom.workspaces not in the sandbox", plugin)
    assertTrue(PluginManagerCore.getPlugin(PluginId.getId("dev.phantom.ai")) != null)
  }

  // the headless test tool window manager does not instantiate plugin.xml tool windows, so read the registration itself
  fun testToolWindowIsRegisteredOnTheRight() {
    val ep = ToolWindowEP.EP_NAME.extensionList.singleOrNull { it.id == "Phantom AI Integration" }
    assertNotNull(ep)
    assertEquals("right", ep!!.anchor)
    assertEquals(dev.phantom.ai.core.AiToolWindowFactory::class.java.name, ep.factoryClass)
  }

  fun testSettingsPersist() {
    val s = AiState.getInstance()
    s.setActiveAccount(AgentKind.CODEX, "x")
    assertEquals("x", AiState.getInstance().activeAccountId(AgentKind.CODEX))
    s.setActiveAccount(AgentKind.CODEX, null)
  }

  // registered on the session topic in plugin.xml, not as a startup activity: a Claude session with an id starts it
  fun testAgentTeamsPollerStartsWithAClaudeSessionThatHasAnId() {
    val store = SessionStore.getInstance()
    store.put(AgentSession("teams-starter", "/w/teams", AgentKind.CLAUDE, sessionId = "s-teams"))
    try {
      assertTrue(service<AgentTeamsPoller>().isStarted)
    } finally {
      store.remove("teams-starter")
    }
  }
}
