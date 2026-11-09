// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.accounts

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
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

/** Runs the CLI login in a terminal and returns when the user is done with it: command, env, title. */
fun interface LoginTerminal {
  fun run(command: String, env: Map<String, String>, title: String)
}

/** Added accounts per provider and the active one. The CLI owns the login and the tokens; nothing secret is stored. */
@Service(Service.Level.APP)
class AccountsService(
  private val baseDir: Path = Path.of(PathManager.getConfigPath(), "phantom-ai", "accounts"),
  private val installer: (AgentKind, Path) -> Boolean = { k, d -> HookInstallService.getInstance().ensureInstalled(k, d) },
  private val terminal: LoginTerminal = LoginTerminal { command, env, title -> LoginDialog(command, env, title).show() },
  private val stateProvider: () -> AiState = { AiState.getInstance() },
  private val notify: (String, Throwable?) -> Unit = { message, cause -> AiNotify.error(LOG, null, message, cause) },
) {
  private val state get() = stateProvider()

  fun accounts(kind: AgentKind): List<AgentAccount> = state.accountsOf(kind).mapNotNull { it.toAccount() }

  /** The active account of [kind]; null = System default. Read when a terminal opens, so a change affects later terminals. */
  fun active(kind: AgentKind): AgentAccount? {
    val id = state.activeAccountId(kind) ?: return null
    return accounts(kind).firstOrNull { it.id == id }
  }

  fun setActive(kind: AgentKind, id: String?) {
    state.setActiveAccount(kind, id?.takeIf { want -> accounts(kind).any { it.id == want } })
  }

  /**
   * Creates the account dir, installs the hooks there and runs the CLI login, on the EDT until the user closes it. The
   * account is named after the login's email unless [label] is given. Null: no login happened, nothing is kept.
   */
  fun add(kind: AgentKind, label: String? = null): AgentAccount? {
    val id = UUID.randomUUID().toString().take(8)
    val dir = baseDir.resolve(kind.name.lowercase()).resolve(id)
    Files.createDirectories(dir)
    val pending = AgentAccount(kind, id, "", dir.toString())
    try {
      if (!installer(kind, dir)) {
        notify(PhantomAiBundle.message("notify.hooks.failed", kind.displayName, dir, "settings are not valid JSON"), null)
      }
    } catch (e: Exception) {
      rethrowIfCancellation(e)
      notify(PhantomAiBundle.message("notify.hooks.failed", kind.displayName, dir, e.message ?: e.toString()), e)
    }
    terminal.run(LaunchCommand.login(kind), LaunchCommand.accountEnv(kind, pending), "${kind.displayName} login")
    val login = AccountLogin.name(kind, dir)
    if (login == null) {
      bestEffort { dir.toFile().deleteRecursively() }
      return null
    }
    val account = pending.copy(label = label?.takeIf { it.isNotBlank() } ?: login.ifEmpty { nextName(kind) })
    state.state.accounts.add(AccountEntry().also { it.provider = kind.name; it.id = id; it.label = account.label; it.dir = account.dir })
    return account
  }

  /** The first "Account N" not in use: after a removal the count alone can name a second account the same. */
  private fun nextName(kind: AgentKind): String {
    val taken = accounts(kind).map { it.label }.toSet()
    return generateSequence(1) { it + 1 }.map { "Account $it" }.first { it !in taken }
  }

  /** Forgets the account; its dir stays on disk (it holds the CLI's own login). */
  fun remove(kind: AgentKind, id: String) {
    if (state.activeAccountId(kind) == id) state.setActiveAccount(kind, null)
    state.state.accounts.removeAll { it.provider == kind.name && it.id == id }
  }

  private fun AccountEntry.toAccount(): AgentAccount? {
    val p = provider ?: return null
    val i = id ?: return null
    val d = dir ?: return null
    return AgentAccount(AgentKind.valueOf(p), i, label ?: i, d)
  }

  companion object {
    private val LOG = logger<AccountsService>()
    fun getInstance(): AccountsService = ApplicationManager.getApplication().getService(AccountsService::class.java)
  }
}
