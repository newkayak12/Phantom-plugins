// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.core

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.messages.Topic

// ===== persisted records (U1: ALL fields are defined here; later units only read and write them) =====

/** Board placement and user name of a worktree, keyed by its path. */
class WorktreeEntry : BaseState() {
  var path by string()
  var column by string()
  var order by property(0)
  var userName by string()
  /** The worktree this one was made from, as in Orca's lineage; the sidebar shows it below that one. Not its base branch. */
  var parentPath by string()
  /** The board card's task: the prompt its agent starts with when the card moves to In progress. */
  var prompt by string()
  /** AgentKind name the card's agent runs as; absent = Claude. */
  var agent by string()
  /** Repository of a planned card (no worktree yet, [path] "card:<id>"): its worktree is made there on In progress. */
  var repo by string()
}

/** A remembered agent session of a worktree (provider = AgentKind.name). */
class SessionEntry : BaseState() {
  var worktree by string()
  var provider by string()
  var sessionId by string()
  var userTitle by string()
  var firstPrompt by string()
  /** Account the session ran under; absent = System default. */
  var accountId by string()
}

/** A folder of the worktree tree: only a group in the view, nothing on disk. [worktrees] are paths in [repo]. */
class WorktreeFolderEntry : BaseState() {
  var repo by string()
  var name by string()
  var worktrees by list<String>()
}

class AccountEntry : BaseState() {
  var provider by string()
  var id by string()
  var label by string()
  var dir by string()
}

class ActiveAccountEntry : BaseState() {
  var provider by string()
  /** Account id; absent = System default. */
  var accountId by string()
}

class AiStateData : BaseState() {
  var worktrees by list<WorktreeEntry>()
  var boardColumns by list<String>()
  var sessions by list<SessionEntry>()
  var accounts by list<AccountEntry>()
  var activeAccounts by list<ActiveAccountEntry>()
  /** Whether Phantom may write its hooks into the System default ~/.claude/settings.json and ~/.codex/hooks.json. */
  var hookConsent by enum(HookConsent.ASK)
  /** Agents start with --dangerously-skip-permissions / --dangerously-bypass-approvals-and-sandbox; off by default. */
  var skipPermissions by property(false)
  /** The agents' database tools (Phantom Database Console data sources, their schemas and query results); off by default. */
  var dbTools by property(false)
  /** The tree and board show the open pull request of each branch, looked up through the GitHub API; off by default. */
  var prTitles by property(false)
  /** Ids of the data sources the user allowed agents to read (asked once per data source); cleared when [dbTools] goes off. */
  var dbAllowedSources by list<String>()
  /** Id of the TerminalBackend agent terminals open in; absent = automatic (the IDE's tab with tmux, else the OS terminal). */
  var terminalBackend by string()
  /** The worktree tree's folders, per repository, in the order they were made. */
  var worktreeFolders by list<WorktreeFolderEntry>()
}

/** The user's answer to changing the agents' own config files: not asked yet, allowed, or refused. */
enum class HookConsent { ASK, ALLOWED, DECLINED }

/** Board placements or worktree names changed; every window's panels refresh. */
fun interface AiStateListener {
  fun changed()
}

@Service(Service.Level.APP)
@State(name = "PhantomAiIntegrations", storages = [Storage("phantom-ai.xml")])
class AiState : SimplePersistentStateComponent<AiStateData>(AiStateData()) {

  /** Tells the panels of every window that the shared state changed, as it is one for the whole IDE. */
  fun changed() {
    ApplicationManager.getApplication().messageBus.syncPublisher(TOPIC).changed()
  }

  // ===== board (U1; U6 uses) =====
  fun boardColumns(): List<String> = state.boardColumns.ifEmpty { DEFAULT_COLUMNS }

  /** Stores the board position of each card (worktree path): column and index. */
  fun placeCards(placements: List<dev.phantom.ai.board.CardPlacement>) {
    placements.forEach { p -> worktree(p.card).apply { column = p.column; order = p.order } }
    changed()
  }

  /** Read-only: the entry of [path] or null; does not create one. */
  fun findWorktree(path: String): WorktreeEntry? = state.worktrees.firstOrNull { it.path == path }

  /** Creates the entry on write; use [findWorktree] to read. */
  fun worktree(path: String): WorktreeEntry =
    state.worktrees.firstOrNull { it.path == path } ?: WorktreeEntry().also { it.path = path; state.worktrees.add(it) }

  // ===== sessions per worktree (U1; U2/U4 use) =====
  fun sessionsOf(worktree: String): List<SessionEntry> = state.sessions.filter { it.worktree == worktree }

  fun rememberSession(
    worktree: String,
    provider: AgentKind,
    sessionId: String,
    firstPrompt: String?,
    accountId: String? = null,
  ): SessionEntry {
    val existing = state.sessions.firstOrNull {
      it.worktree == worktree && it.provider == provider.name && it.sessionId == sessionId
    }
    if (existing != null) {
      // The first event (SessionStart) has no prompt: a later event fills the title once; a set one is never overwritten.
      if (existing.firstPrompt.isNullOrBlank() && !firstPrompt.isNullOrBlank()) existing.firstPrompt = firstPrompt
      if (existing.accountId == null && accountId != null) existing.accountId = accountId
      return existing
    }
    return SessionEntry().also {
      it.worktree = worktree; it.provider = provider.name; it.sessionId = sessionId; it.firstPrompt = firstPrompt; it.accountId = accountId
      state.sessions.add(it)
    }
  }

  // ===== accounts (U1; U3 uses) =====
  fun accountsOf(provider: AgentKind): List<AccountEntry> = state.accounts.filter { it.provider == provider.name }

  /** Id of the active account of [provider]; null = System default. */
  fun activeAccountId(provider: AgentKind): String? =
    state.activeAccounts.firstOrNull { it.provider == provider.name }?.accountId

  fun setActiveAccount(provider: AgentKind, accountId: String?) {
    val e = state.activeAccounts.firstOrNull { it.provider == provider.name }
      ?: ActiveAccountEntry().also { it.provider = provider.name; state.activeAccounts.add(it) }
    e.accountId = accountId
  }

  companion object {
    val TOPIC: Topic<AiStateListener> = Topic.create("Phantom AI state", AiStateListener::class.java)
    val DEFAULT_COLUMNS = listOf("Todo", "In progress", "In review", "Done")

    fun getInstance(): AiState = ApplicationManager.getApplication().getService(AiState::class.java)
  }
}
