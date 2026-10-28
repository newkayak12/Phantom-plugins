// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.accounts

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.ProjectManager
import dev.phantom.ai.core.AccountEntry
import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.AiNotify
import dev.phantom.ai.core.AiState
import dev.phantom.ai.core.PhantomAiBundle
import dev.phantom.ai.core.rethrowIfCancellation
import com.intellij.openapi.diagnostic.logger
import dev.phantom.ai.hooks.HookInstallService
import dev.phantom.ai.launch.LaunchCommand
import dev.phantom.ai.terminal.AgentTerminalService
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** Opens the CLI login in a terminal: command, env, tab title. */
fun interface LoginTerminal {
  fun run(command: String, env: Map<String, String>, title: String)
}

/** Added accounts per provider and the active one. The CLI owns the login and the tokens; nothing secret is stored. */
@Service(Service.Level.APP)
class AccountsService(
  private val baseDir: Path = Path.of(PathManager.getConfigPath(), "phantom-ai", "accounts"),
  private val installer: (AgentKind, Path) -> Boolean = { k, d -> HookInstallService.getInstance().ensureInstalled(k, d) },
  private val terminal: LoginTerminal = LoginTerminal { command, env, title ->
    ProjectManager.getInstance().openProjects.firstOrNull()
      ?.let { AgentTerminalService.getInstance(it).runInTerminal(command, env, title) }
  },
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

  /** Creates the account dir, installs the hooks there and starts the CLI login in a terminal. */
  fun add(kind: AgentKind, label: String? = null): AgentAccount {
    val id = UUID.randomUUID().toString().take(8)
    val dir = baseDir.resolve(kind.name.lowercase()).resolve(id)
    Files.createDirectories(dir)
    val account = AgentAccount(kind, id, label?.takeIf { it.isNotBlank() } ?: "Account ${accounts(kind).size + 1}", dir.toString())
    state.state.accounts.add(AccountEntry().also { it.provider = kind.name; it.id = id; it.label = account.label; it.dir = account.dir })
    try {
      if (!installer(kind, dir)) {
        notify(PhantomAiBundle.message("notify.hooks.failed", kind.displayName, dir, "settings are not valid JSON"), null)
      }
    } catch (e: Exception) {
      rethrowIfCancellation(e)
      notify(PhantomAiBundle.message("notify.hooks.failed", kind.displayName, dir, e.message ?: e.toString()), e)
    }
    terminal.run(LaunchCommand.login(kind), LaunchCommand.accountEnv(kind, account), "${kind.displayName} login")
    return account
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
