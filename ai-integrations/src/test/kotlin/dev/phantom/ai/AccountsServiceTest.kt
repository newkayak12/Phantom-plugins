// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import dev.phantom.ai.accounts.AccountLogin
import dev.phantom.ai.accounts.AccountsService
import dev.phantom.ai.accounts.LoginFailed
import dev.phantom.ai.accounts.LoginRunner
import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.AiState
import com.intellij.openapi.progress.ProcessCanceledException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

class AccountsServiceTest {
  @get:Rule val tmp = TemporaryFolder()

  private class Fixture(val base: Path, install: (AgentKind, Path) -> Boolean = { _, _ -> true }) {
    val state = AiState()
    val installs = mutableListOf<Pair<AgentKind, Path>>()
    val logins = mutableListOf<Triple<String, Map<String, String>, String>>()
    val notices = mutableListOf<Pair<String, Throwable?>>()
    /** What the login leaves in the account dir; null: the user closed it without logging in. */
    var email: String? = ""
    val service = AccountsService(
      base, { k, d -> installs.add(k to d); install(k, d) },
      LoginRunner { c, e, t -> logins.add(Triple(c, e, t)); email?.let { signIn(e, it) } }, { state },
      { m, e -> notices.add(m to e) },
    )

    private fun signIn(env: Map<String, String>, email: String) {
      env["CLAUDE_CONFIG_DIR"]?.let { Files.writeString(Path.of(it, ".claude.json"), """{"oauthAccount":{"emailAddress":"$email"}}""") }
      env["CODEX_HOME"]?.let { Files.writeString(Path.of(it, "auth.json"), """{"OPENAI_API_KEY":"k"}""") }
    }
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

  @Test fun removeForgetsAccountResetsActiveAndDeletesItsDirButNotWhatItLinksTo() {
    val f = fixture()
    val a = f.service.add(AgentKind.CLAUDE)
    val shared = tmp.newFolder("shared").toPath()
    Files.writeString(shared.resolve("keep.txt"), "x")
    Files.createSymbolicLink(Path.of(a.dir, "skills"), shared)
    f.service.setActive(AgentKind.CLAUDE, a.id)
    f.service.remove(AgentKind.CLAUDE, a.id)
    assertNull(f.service.active(AgentKind.CLAUDE))
    assertEquals(emptyList<Any>(), f.service.accounts(AgentKind.CLAUDE))
    assertFalse(Files.exists(Path.of(a.dir)))
    assertTrue(Files.exists(shared.resolve("keep.txt")))
  }

  @Test fun aFailedLoginTellsWhyAndLeavesNothingEvenThroughLinks() {
    val shared = tmp.newFolder("linked").toPath()
    Files.writeString(shared.resolve("keep.txt"), "x")
    val base = tmp.newFolder("base").toPath()
    val service = AccountsService(
      base, { _, d -> Files.createSymbolicLink(d.resolve("skills"), shared); true },
      LoginRunner { _, _, _ -> throw LoginFailed("denied") }, { AiState() }, { _, _ -> },
    )
    assertEquals("denied", assertThrows(LoginFailed::class.java) { service.add(AgentKind.CLAUDE) }.message)
    assertEquals(emptyList<Any>(), Files.list(base.resolve("claude")).use { it.toList() })
    assertTrue(Files.exists(shared.resolve("keep.txt")))
  }

  @Test fun theSameAccountCannotBeAddedTwice() {
    val f = fixture()
    f.email = "Me@example.com"
    f.service.add(AgentKind.CLAUDE)
    f.email = "me@example.com"
    val e = assertThrows(LoginFailed::class.java) { f.service.add(AgentKind.CLAUDE) }
    assertTrue(e.message!!, e.message!!.contains("already added"))
    assertEquals(1, f.service.accounts(AgentKind.CLAUDE).size)
    assertEquals(1, Files.list(f.base.resolve("claude")).use { it.count() })
  }

  @Test fun aLoggedOutAccountCannotBeSelectedUntilItSignsInAgain() {
    val f = fixture()
    val a = f.service.add(AgentKind.CLAUDE)
    assertEquals("Account 1", a.label)
    Files.delete(Path.of(a.dir, ".claude.json"))
    assertFalse(f.service.signedIn(a))
    f.service.setActive(AgentKind.CLAUDE, a.id)
    assertNull(f.service.active(AgentKind.CLAUDE))

    f.email = "back@example.com"
    f.service.signInAgain(AgentKind.CLAUDE, a.id)
    val again = f.service.accounts(AgentKind.CLAUDE).single()
    assertEquals("back@example.com", again.label)
    assertTrue(f.service.signedIn(again))
    f.service.setActive(AgentKind.CLAUDE, a.id)
    assertEquals(a.id, f.service.active(AgentKind.CLAUDE)?.id)
  }

  @Test fun aFailedSignInAgainKeepsTheAccount() {
    val f = fixture()
    val a = f.service.add(AgentKind.CLAUDE)
    Files.delete(Path.of(a.dir, ".claude.json"))
    f.email = null
    assertThrows(LoginFailed::class.java) { f.service.signInAgain(AgentKind.CLAUDE, a.id) }
    assertEquals(listOf(a), f.service.accounts(AgentKind.CLAUDE))
    assertTrue(Files.isDirectory(Path.of(a.dir)))
  }

  @Test fun persistedStateHoldsNoSecrets() {
    val f = fixture()
    f.service.add(AgentKind.CLAUDE, "x")
    val e = f.state.state.accounts.single()
    val names = e.javaClass.declaredFields.map { it.name.substringBefore('$') }.filter { it.isNotEmpty() && it != "Companion" }.toSet()
    assertEquals(setOf("provider", "id", "label", "dir"), names)
  }

  @Test fun theLoginsEmailNamesTheAccount() {
    val f = fixture()
    f.email = "me@example.com"
    assertEquals("me@example.com", f.service.add(AgentKind.CLAUDE).label)
  }

  @Test fun aGivenLabelWinsOverTheEmail() {
    val f = fixture()
    f.email = "me@example.com"
    assertEquals("Work", f.service.add(AgentKind.CLAUDE, "Work").label)
  }

  @Test fun aLoginThatEndsWithoutAnAccountFailsAndKeepsNothing() {
    val f = fixture()
    f.email = null
    assertThrows(LoginFailed::class.java) { f.service.add(AgentKind.CLAUDE) }
    assertEquals(emptyList<Any>(), f.service.accounts(AgentKind.CLAUDE))
    assertEquals(emptyList<Any>(), Files.list(f.base.resolve("claude")).use { it.toList() })
  }

  @Test fun aFileTheCliCreatesWithoutALoginIsNotALogin() {
    val dir = tmp.newFolder("cfg").toPath()
    Files.writeString(dir.resolve(".claude.json"), """{"numStartups":1}""")
    assertNull(AccountLogin.name(AgentKind.CLAUDE, dir))
    Files.writeString(dir.resolve(".claude.json"), """{"oauthAcc""")
    assertNull(AccountLogin.name(AgentKind.CLAUDE, dir))
  }

  @Test fun codexEmailComesFromTheIdToken() {
    val dir = tmp.newFolder("codex").toPath()
    val payload = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"email":"c@example.com"}""".toByteArray())
    Files.writeString(dir.resolve("auth.json"), """{"OPENAI_API_KEY":null,"tokens":{"id_token":"h.$payload.s"}}""")
    assertEquals("c@example.com", AccountLogin.name(AgentKind.CODEX, dir))
    Files.writeString(dir.resolve("auth.json"), """{"OPENAI_API_KEY":null,"tokens":{}}""")
    assertEquals("", AccountLogin.name(AgentKind.CODEX, dir))
    Files.writeString(dir.resolve("auth.json"), """{"OPENAI_API_KEY":null}""")
    assertNull(AccountLogin.name(AgentKind.CODEX, dir))
  }

  @Test fun numberedNamesDoNotRepeatAfterARemoval() {
    val f = fixture()
    val first = f.service.add(AgentKind.CLAUDE)
    val second = f.service.add(AgentKind.CLAUDE)
    assertEquals(listOf("Account 1", "Account 2"), listOf(first.label, second.label))
    f.service.remove(AgentKind.CLAUDE, first.id)
    assertEquals("Account 1", f.service.add(AgentKind.CLAUDE).label)
    assertEquals(listOf("Account 2", "Account 1"), f.service.accounts(AgentKind.CLAUDE).map { it.label })
  }
}
