// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import com.google.gson.JsonParser
import dev.phantom.ai.accounts.AccountHome
import dev.phantom.ai.core.AgentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class AccountHomeTest {
  @get:Rule val tmp = TemporaryFolder()

  private val system: Path by lazy { tmp.newFolder("system").toPath() }
  private val account: Path by lazy { tmp.newFolder("account").toPath() }
  private val userFile: Path by lazy { tmp.root.toPath().resolve("user.claude.json") }

  private fun inherit(kind: AgentKind = AgentKind.CLAUDE) = AccountHome.inherit(kind, account, system, userFile)

  private fun json(file: Path) = JsonParser.parseString(Files.readString(file)).asJsonObject

  @Test fun sharesTheSetupButNeverTheLoginOrPerRunState() {
    Files.createDirectories(system.resolve("skills"))
    Files.createDirectories(system.resolve("projects"))
    Files.createDirectories(system.resolve("sessions"))
    Files.writeString(system.resolve("CLAUDE.md"), "memory")
    Files.writeString(system.resolve(".credentials.json"), "secret")
    inherit()
    assertEquals(system.resolve("skills"), Files.readSymbolicLink(account.resolve("skills")))
    assertEquals(system.resolve("projects"), Files.readSymbolicLink(account.resolve("projects")))
    assertEquals("memory", Files.readString(account.resolve("CLAUDE.md")))
    assertFalse(Files.exists(account.resolve("sessions")))
    assertFalse(Files.exists(account.resolve(".credentials.json")))
  }

  @Test fun whatTheAccountHasIsNeverReplaced() {
    Files.createDirectories(system.resolve("skills"))
    Files.createDirectories(account.resolve("skills"))
    inherit()
    inherit()
    assertFalse(Files.isSymbolicLink(account.resolve("skills")))
  }

  @Test fun theUsersSettingsAndHooksJoinTheAccountsOwn() {
    Files.writeString(system.resolve("settings.json"), """{"model":"opus","language":"ko","statusLine":{"command":"user"},"hooks":{"Stop":[{"a":1}],"PreToolUse":[{"b":2}]}}""")
    Files.writeString(account.resolve("settings.json"), """{"language":"en","statusLine":{"command":"phantom"},"hooks":{"Stop":[{"p":1}]}}""")
    inherit()
    inherit()
    val merged = json(account.resolve("settings.json"))
    assertEquals("opus", merged.get("model").asString)
    assertEquals("en", merged.get("language").asString)
    assertEquals("phantom", merged.getAsJsonObject("statusLine").get("command").asString)
    assertEquals(2, merged.getAsJsonObject("hooks").getAsJsonArray("Stop").size())
    assertEquals(1, merged.getAsJsonObject("hooks").getAsJsonArray("PreToolUse").size())
  }

  @Test fun mcpServersComeAlongButTheUsersLoginDoesNot() {
    Files.writeString(userFile, """{"mcpServers":{"db":{}},"hasCompletedOnboarding":true,"oauthAccount":{"emailAddress":"user@example.com"}}""")
    inherit()
    val own = json(account.resolve(".claude.json"))
    assertTrue(own.getAsJsonObject("mcpServers").has("db"))
    assertTrue(own.get("hasCompletedOnboarding").asBoolean)
    assertFalse(own.has("oauthAccount"))
  }

  @Test fun codexSharesOnlyItsListAndCopiesItsConfig() {
    Files.createDirectories(system.resolve("skills"))
    Files.writeString(system.resolve("auth.json"), "secret")
    Files.writeString(system.resolve("config.toml"), "model = \"x\"")
    inherit(AgentKind.CODEX)
    assertTrue(Files.isSymbolicLink(account.resolve("skills")))
    assertFalse(Files.exists(account.resolve("auth.json")))
    assertFalse(Files.isSymbolicLink(account.resolve("config.toml")))
    assertEquals("model = \"x\"", Files.readString(account.resolve("config.toml")))
  }

  @Test fun theSystemDirItselfIsLeftAlone() {
    Files.createDirectories(system.resolve("skills"))
    AccountHome.inherit(AgentKind.CLAUDE, system, system, userFile)
    assertFalse(Files.isSymbolicLink(system.resolve("skills")))
  }
}
