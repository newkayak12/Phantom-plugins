// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import dev.phantom.ai.accounts.AccountsService
import dev.phantom.ai.accounts.LoginTerminal
import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.AiState
import com.intellij.openapi.progress.ProcessCanceledException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class AccountsServiceTest {
  @get:Rule val tmp = TemporaryFolder()

  private class Fixture(val base: Path, install: (AgentKind, Path) -> Boolean = { _, _ -> true }) {
    val state = AiState()
    val installs = mutableListOf<Pair<AgentKind, Path>>()
    val logins = mutableListOf<Triple<String, Map<String, String>, String>>()
    val notices = mutableListOf<Pair<String, Throwable?>>()
    val service = AccountsService(
      base, { k, d -> installs.add(k to d); install(k, d) }, LoginTerminal { c, e, t -> logins.add(Triple(c, e, t)) }, { state },
      { m, e -> notices.add(m to e) },
    )
  }

  private fun fixture(install: (AgentKind, Path) -> Boolean = { _, _ -> true }) = Fixture(tmp.newFolder("accounts").toPath(), install)

  @Test fun hookInstallFailureIsReportedToTheUser() {
    val f = fixture { _, _ -> false }
    val a = f.service.add(AgentKind.CODEX)
    val n = f.notices.single()
    assertTrue(n.first, n.first.contains(AgentKind.CODEX.displayName) && n.first.contains(a.dir))
    assertEquals(listOf(a), f.service.accounts(AgentKind.CODEX))
    assertEquals(1, f.logins.size)
  }

  @Test fun hookInstallExceptionIsReported() {
    val boom = IllegalStateException("disk full")
    val f = fixture { _, _ -> throw boom }
    val a = f.service.add(AgentKind.CLAUDE)
    val n = f.notices.single()
    assertTrue(n.first, n.first.contains("Claude") && n.first.contains(a.dir) && n.first.contains("disk full"))
    assertSame(boom, n.second)
    assertEquals(listOf(a), f.service.accounts(AgentKind.CLAUDE))
    assertEquals(1, f.logins.size)
  }

  @Test fun cancellationDuringHookInstallIsRethrown() {
    val pce = ProcessCanceledException()
    val f = fixture { _, _ -> throw pce }
    assertSame(pce, runCatching { f.service.add(AgentKind.CLAUDE) }.exceptionOrNull())
    assertTrue(f.notices.isEmpty())
    assertTrue(f.logins.isEmpty())
  }

  @Test fun addCreatesDirInstallsHooksAndRunsLoginWithAccountVariable() {
    val f = fixture()
    val a = f.service.add(AgentKind.CLAUDE)
    val dir = f.base.resolve("claude").resolve(a.id)
    assertTrue(Files.isDirectory(dir))
    assertEquals(dir.toString(), a.dir)
    assertEquals(listOf(AgentKind.CLAUDE to dir), f.installs)
    assertEquals(Triple("claude 'auth' 'login' '--claudeai'", mapOf("CLAUDE_CONFIG_DIR" to dir.toString()), "Claude login"), f.logins.single())
    assertEquals(listOf(a), f.service.accounts(AgentKind.CLAUDE))
    assertEquals(emptyList<Any>(), f.service.accounts(AgentKind.CODEX))
  }

  @Test fun codexAccountUsesCodexHome() {
    val f = fixture()
    val a = f.service.add(AgentKind.CODEX, "Work")
    assertEquals("Work", a.label)
    assertEquals(mapOf("CODEX_HOME" to a.dir), f.logins.single().second)
    assertEquals("codex 'login'", f.logins.single().first)
    assertTrue(a.dir.contains("/codex/"))
  }

  @Test fun activeAccountPerProviderAndSystemDefault() {
    val f = fixture()
    assertNull(f.service.active(AgentKind.CLAUDE))
    val a = f.service.add(AgentKind.CLAUDE)
    f.service.setActive(AgentKind.CLAUDE, a.id)
    assertEquals(a, f.service.active(AgentKind.CLAUDE))
    assertNull(f.service.active(AgentKind.CODEX))
    f.service.setActive(AgentKind.CLAUDE, null)
    assertNull(f.service.active(AgentKind.CLAUDE))
    f.service.setActive(AgentKind.CLAUDE, "unknown")
    assertNull(f.service.active(AgentKind.CLAUDE))
  }

  @Test fun removeForgetsAccountAndResetsActiveButKeepsDir() {
    val f = fixture()
    val a = f.service.add(AgentKind.CLAUDE)
    f.service.setActive(AgentKind.CLAUDE, a.id)
    f.service.remove(AgentKind.CLAUDE, a.id)
    assertNull(f.service.active(AgentKind.CLAUDE))
    assertEquals(emptyList<Any>(), f.service.accounts(AgentKind.CLAUDE))
    assertTrue(Files.isDirectory(Path.of(a.dir)))
  }

  @Test fun persistedStateHoldsNoSecrets() {
    val f = fixture()
    f.service.add(AgentKind.CLAUDE, "x")
    val e = f.state.state.accounts.single()
    val names = e.javaClass.declaredFields.map { it.name.substringBefore('$') }.filter { it.isNotEmpty() && it != "Companion" }.toSet()
    assertEquals(setOf("provider", "id", "label", "dir"), names)
  }
}
