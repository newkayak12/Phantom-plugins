// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.accounts

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.util.io.NioFiles
import dev.phantom.ai.core.AccountEntry
import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.AiNotify
import dev.phantom.ai.core.AiState
import dev.phantom.ai.core.PhantomAiBundle
import dev.phantom.ai.core.bestEffort
import dev.phantom.ai.core.rethrowIfCancellation
import com.intellij.openapi.diagnostic.logger
import dev.phantom.ai.hooks.HookInstallService
import dev.phantom.ai.launch.LaunchCommand
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** Runs the CLI login and returns when it ended: command, env, title. Throws [LoginFailed], or a cancellation. */
fun interface LoginRunner {
  fun run(command: String, env: Map<String, String>, title: String)
}

/** Added accounts per provider and the active one. The CLI owns the login and the tokens; nothing secret is stored. */
@Service(Service.Level.APP)
class AccountsService(
  private val baseDir: Path = Path.of(PathManager.getConfigPath(), "phantom-ai", "accounts"),
  private val installer: (AgentKind, Path) -> Boolean = { k, d -> HookInstallService.getInstance().ensureInstalled(k, d) },
  private val login: LoginRunner = LoginRunner { command, env, title -> BrowserLogin.run(command, env, title) },
  private val stateProvider: () -> AiState = { AiState.getInstance() },
  private val notify: (String, Throwable?) -> Unit = { message, cause -> AiNotify.error(LOG, null, message, cause) },
  private val inherit: (AgentKind, Path) -> Unit = { k, d -> AccountHome.inherit(k, d) },
) {
  private val state get() = stateProvider()

  fun accounts(kind: AgentKind): List<AgentAccount> = state.accountsOf(kind).mapNotNull { it.toAccount() }

  /** The active account of [kind]; null = System default. Read when a terminal opens, so a change affects later terminals. */
  fun active(kind: AgentKind): AgentAccount? {
    val id = state.activeAccountId(kind) ?: return null
    return accounts(kind).firstOrNull { it.id == id }
  }

  /** An account whose login is gone (logged out, or never finished) cannot run an agent until it signs in again. */
  fun signedIn(account: AgentAccount): Boolean = AccountLogin.name(account.provider, Path.of(account.dir)) != null

  fun setActive(kind: AgentKind, id: String?) {
    state.setActiveAccount(kind, id?.takeIf { want -> accounts(kind).any { it.id == want && signedIn(it) } })
  }

  /**
   * Creates the account dir, sets it up like the user's own and runs the CLI login, on the EDT under modal progress until
   * it ends. The account is named after the login's email unless [label] is given. A failed or cancelled login, or an
   * account already added, throws and keeps nothing.
   */
  fun add(kind: AgentKind, label: String? = null): AgentAccount {
    val id = UUID.randomUUID().toString().take(8)
    val dir = baseDir.resolve(kind.name.lowercase()).resolve(id)
    Files.createDirectories(dir)
    val pending = AgentAccount(kind, id, "", dir.toString())
    try {
      prepare(kind, dir)
      try {
        if (!installer(kind, dir)) {
          notify(PhantomAiBundle.message("notify.hooks.failed", kind.displayName, dir, "settings are not valid JSON"), null)
        }
      } catch (e: Exception) {
        rethrowIfCancellation(e)
        notify(PhantomAiBundle.message("notify.hooks.failed", kind.displayName, dir, e.message ?: e.toString()), e)
      }
      val name = signIn(pending)
      val account = pending.copy(label = label?.takeIf { it.isNotBlank() } ?: name.ifEmpty { nextName(kind) })
      state.state.accounts.add(AccountEntry().also { it.provider = kind.name; it.id = id; it.label = account.label; it.dir = account.dir })
      return account
    } catch (e: Throwable) {
      bestEffort { NioFiles.deleteRecursively(dir) }
      throw e
    }
  }

  /** Runs the login again in [id]'s dir, which stays whatever happens; a numbered account takes the email as its name. */
  fun signInAgain(kind: AgentKind, id: String) {
    val account = accounts(kind).firstOrNull { it.id == id } ?: return
    val name = signIn(account)
    if (name.isNotEmpty() && NUMBERED.matches(account.label)) state.state.accounts.first { it.id == id }.label = name
  }

  /** The login's name ("" when it has no address). */
  private fun signIn(account: AgentAccount): String {
    val dir = Path.of(account.dir)
    login.run(LaunchCommand.login(account.provider), LaunchCommand.accountEnv(account.provider, account), "${account.provider.displayName} login")
    val name = AccountLogin.name(account.provider, dir) ?: throw LoginFailed(PhantomAiBundle.message("accounts.login.failed.none"))
    val identity = AccountLogin.identity(account.provider, dir)
    val taken = identity != null && accounts(account.provider).any { it.id != account.id && AccountLogin.identity(it.provider, Path.of(it.dir)) == identity }
    if (taken) throw LoginFailed(PhantomAiBundle.message("accounts.login.failed.duplicate", name))
    return name
  }

  /** Brings an account's dir up to the user's current setup, before an agent runs in it. Best effort. */
  fun prepare(kind: AgentKind, dir: Path) {
    bestEffort { inherit(kind, dir) }.onFailure { LOG.warn("Could not set up the account in $dir", it) }
  }

  /** The first "Account N" not in use: after a removal the count alone can name a second account the same. */
  private fun nextName(kind: AgentKind): String {
    val taken = accounts(kind).map { it.label }.toSet()
    return generateSequence(1) { it + 1 }.map { "Account $it" }.first { it !in taken }
  }

  /** Forgets the account and deletes its dir, not following the links into the user's own setup. */
  fun remove(kind: AgentKind, id: String) {
    val dir = accounts(kind).firstOrNull { it.id == id }?.let { Path.of(it.dir).normalize() }
    if (state.activeAccountId(kind) == id) state.setActiveAccount(kind, null)
    state.state.accounts.removeAll { it.provider == kind.name && it.id == id }
    if (dir == null || !dir.startsWith(baseDir.normalize()) || dir == baseDir.normalize()) return
    bestEffort { NioFiles.deleteRecursively(dir) }.onFailure {
      notify(PhantomAiBundle.message("accounts.remove.failed", dir, it.message ?: it.toString()), it)
    }
  }

  private fun AccountEntry.toAccount(): AgentAccount? {
    val p = provider ?: return null
    val i = id ?: return null
    val d = dir ?: return null
    return AgentAccount(AgentKind.valueOf(p), i, label ?: i, d)
  }

  companion object {
    private val LOG = logger<AccountsService>()
    private val NUMBERED = Regex("Account \\d+")
    fun getInstance(): AccountsService = ApplicationManager.getApplication().getService(AccountsService::class.java)
  }
}
