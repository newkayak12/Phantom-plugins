// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBRadioButton
import dev.phantom.ai.accounts.AccountsConfigurable
import dev.phantom.ai.accounts.AccountsService
import dev.phantom.ai.accounts.LoginRunner
import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.AiState
import java.awt.Component
import java.awt.Container
import javax.swing.JButton

class AccountsConfigurableInIdeTest : BasePlatformTestCase() {
  private fun all(c: Component): List<Component> = listOf(c) + ((c as? Container)?.components?.flatMap { all(it) } ?: emptyList())

  override fun tearDown() {
    try {
      val s = AccountsService.getInstance()
      AgentKind.entries.forEach { k -> s.accounts(k).forEach { s.remove(k, it.id) } }
    } finally {
      super.tearDown()
    }
  }

  fun testRegisteredUnderToolsAsAiIntegrations() {
    val ep = com.intellij.openapi.options.Configurable.APPLICATION_CONFIGURABLE.extensionList
      .single { it.instanceClass == AccountsConfigurable::class.java.name }
    assertEquals("tools", ep.parentId)
    assertEquals("Phantom AI Integration", ep.displayName)
  }

  fun testSectionsHaveSystemDefaultRowAddButtonAndSelection() {
    val c = AccountsConfigurable()
    val ui = c.createComponent()
    val comps = all(ui)
    for (k in AgentKind.entries) {
      val radio = comps.filterIsInstance<JBRadioButton>().single { it.name == "${k.name}:default" }
      assertEquals("System default", radio.text)
      assertTrue(radio.isSelected)
      assertTrue(comps.filterIsInstance<JButton>().any { it.name == "add:${k.name}" && it.text == "Add account" })
    }
    c.disposeUIResources()
  }

  fun testAddedAccountRowCanBeSelectedAndApplied() {
    val base = java.nio.file.Files.createTempDirectory("accounts")
    val svc = AccountsService(
      baseDir = base,
      installer = { _, _ -> true },
      login = LoginRunner { _, env, _ ->
        java.nio.file.Files.writeString(java.nio.file.Path.of(env.getValue("CODEX_HOME"), "auth.json"), """{"OPENAI_API_KEY":"k"}""")
      },
      stateProvider = { AiState.getInstance() },
    )
    val a = svc.add(AgentKind.CODEX, "Work")
    val c = AccountsConfigurable(svc)
    val ui = c.createComponent()
    val radio = all(ui).filterIsInstance<JBRadioButton>().single { it.name == "CODEX:${a.id}" }
    assertEquals("Work", radio.text)
    assertFalse(c.isModified)
    radio.doClick()
    assertTrue(c.isModified)
    c.apply()
    assertEquals(a.id, AiState.getInstance().activeAccountId(AgentKind.CODEX))
    assertFalse(c.isModified)
    c.disposeUIResources()
  }

  fun testALoggedOutAccountCannotBeChosenAndOffersSignIn() {
    val base = java.nio.file.Files.createTempDirectory("accounts")
    val svc = AccountsService(
      baseDir = base,
      installer = { _, _ -> true },
      login = LoginRunner { _, env, _ ->
        java.nio.file.Files.writeString(java.nio.file.Path.of(env.getValue("CODEX_HOME"), "auth.json"), """{"OPENAI_API_KEY":"k"}""")
      },
      stateProvider = { AiState.getInstance() },
    )
    val a = svc.add(AgentKind.CODEX, "Work")
    java.nio.file.Files.delete(java.nio.file.Path.of(a.dir, "auth.json"))
    val c = AccountsConfigurable(svc)
    val comps = all(c.createComponent())
    val radio = comps.filterIsInstance<JBRadioButton>().single { it.name == "CODEX:${a.id}" }
    assertFalse(radio.isEnabled)
    assertEquals("Work (sign in again)", radio.text)
    val signIn = comps.filterIsInstance<JButton>().single { it.name == "signin:CODEX:${a.id}" }
    signIn.doClick()
    val after = all(c.createComponent()).filterIsInstance<JBRadioButton>().single { it.name == "CODEX:${a.id}" }
    assertTrue(after.isEnabled)
    assertEquals("Work", after.text)
    c.disposeUIResources()
  }

  // The choices that reach outside Phantom start off, say what they do, and are kept by Apply.
  fun testOptionsStartOffAndApply() {
    val state = AiState()
    val c = AccountsConfigurable(AccountsService.getInstance(), dev.phantom.ai.hooks.HookInstallService { state }) { state }
    val ui = c.createComponent()
    val boxes = all(ui).filterIsInstance<com.intellij.ui.components.JBCheckBox>().associateBy { it.name }
    val skip = boxes.getValue("settings.skip.permissions")
    val db = boxes.getValue("settings.db.tools")
    assertFalse(boxes.getValue("settings.hooks").isSelected); assertFalse(skip.isSelected); assertFalse(db.isSelected)
    assertTrue(boxes.getValue("settings.hooks").text.contains("settings.json"))
    val help = all(ui).filterIsInstance<com.intellij.ui.components.JBLabel>().associateBy { it.name }
    assertTrue(help.getValue("settings.skip.permissions.help").text.contains("--dangerously-skip-permissions"))
    assertTrue(help.getValue("settings.db.tools.help").text.contains("AI provider"))
    assertFalse(c.isModified)
    skip.isSelected = true; db.isSelected = true
    assertTrue(c.isModified)
    c.apply()
    assertTrue(state.state.skipPermissions); assertTrue(state.state.dbTools)
    assertEquals("an unticked hooks box leaves the question unanswered", dev.phantom.ai.core.HookConsent.ASK, state.state.hookConsent)
    c.disposeUIResources()
  }
}
