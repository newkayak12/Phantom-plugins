// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.board

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.testFramework.LightVirtualFile
import com.intellij.util.concurrency.annotations.RequiresEdt
import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.AgentSession
import dev.phantom.ai.core.AgentStatus
import dev.phantom.ai.core.AiNotify
import dev.phantom.ai.core.AiState
import dev.phantom.ai.core.PhantomAiBundle
import dev.phantom.ai.core.SessionListener
import dev.phantom.ai.core.SessionStore
import dev.phantom.ai.core.bestEffort
import dev.phantom.ai.memo.MemoChoices
import dev.phantom.ai.memo.MemoQueue
import dev.phantom.ai.memo.MemoTerminals
import dev.phantom.ai.sidebar.Git4IdeaRun
import dev.phantom.ai.sidebar.GitRun
import dev.phantom.ai.sidebar.SidebarActions
import dev.phantom.ai.sidebar.WorktreeCreator
import dev.phantom.ai.sidebar.WorktreeOpening
import dev.phantom.ai.sidebar.WorktreeRemoval
import dev.phantom.ai.terminal.submit
import git4idea.commands.GitCommand
import java.util.concurrent.ConcurrentHashMap

/** A session badge on a card: working | waiting | done | launched (no event yet). */
data class BoardSession(val tabId: String, val status: String)

/**
 * One card = one task = one worktree (or a planned card, [path] "card:<id>", that gets its worktree on In progress) with
 * its prompt, agent, branch, pull request and live sessions.
 */
data class BoardCard(
  val path: String,
  val title: String,
  val branch: String?,
  val prNumber: Int?,
  val sessions: List<BoardSession> = emptyList(),
  val repo: String? = null,
  val projectPath: String? = null,
  val projectName: String? = null,
  val prompt: String? = null,
  val agent: AgentKind = AgentKind.CLAUDE,
  val prUrl: String? = null,
  val isMain: Boolean = false,
) {
  val planned: Boolean get() = BoardFlow.isPlanned(path)
}

/** The user's answer to Done: whether to remove the worktree too. */
data class DoneChoice(val removeWorktree: Boolean)

/** A new or edited card: repository (new cards only), title, prompt and agent. */
data class CardDraft(val repo: String, val title: String, val prompt: String?, val agent: AgentKind)

/** The questions a move asks. Fakeable; the real ones are [IdeBoardDialogs]. EDT. */
interface BoardDialogs {
  /** The prompt to start with ([followUp] false) or to send to the finished session; null = cancelled. */
  fun prompt(card: BoardCard, followUp: Boolean): String?

  /** Confirms Done and asks whether to remove the worktree; null = cancelled. */
  fun confirmDone(card: BoardCard): DoneChoice?

  /** A new card in one of [repos], or [card] edited; null = cancelled. */
  fun editCard(repos: List<String>, card: BoardCard?): CardDraft?
}

/**
 * What the board's steps do, through the plugin's own parts: WorktreeCreator makes the worktree, AgentTerminalService
 * starts the agent, a [BoardFollowUps] queue sends a follow-up the way memos are sent, WorktreeRemoval removes. Git runs
 * on [background]; everything else on the EDT.
 */
class BoardWork(
  private val git: GitRun = Git4IdeaRun(),
  private val creator: WorktreeCreator = WorktreeCreator(git),
  private val background: (Runnable) -> Unit = { ApplicationManager.getApplication().executeOnPooledThread(it) },
  /** Opens a worktree the board just made as its own project window, unless the user turned that off in New Worktree. */
  private val openProject: (Project, String) -> Unit = { p, path -> if (WorktreeOpening.asProject) SidebarActions(p).openInPhantom(path) },
) {
  /** In progress: a planned card gets its worktree first; then the agent starts there, or a live one gets [prompt]. */
  @RequiresEdt
  fun start(project: Project, card: BoardCard, prompt: String?) {
    if (!card.planned) {
      val live = liveSession(project, card.path)
      when {
        live == null -> SidebarActions(project).newSession(card.path, card.agent, prompt)
        prompt != null -> BoardFollowUps.getInstance(project).send(live, prompt)
      }
      return
    }
    val repo = card.repo ?: return
    background(Runnable {
      val created = creator.create(repo, card.title, null)
      ApplicationManager.getApplication().invokeLater({
        val state = AiState.getInstance()
        created.onSuccess { w ->
          state.rekey(card.path, w.path)
          state.changed()
          // the agent starts in this window first, so its session stays here while the worktree opens as a project
          SidebarActions(project).newSession(w.path, card.agent, prompt)
          openProject(project, w.path)
        }.onFailure {
          state.findWorktree(card.path)?.column = BoardFlow.TODO
          state.changed()
          Messages.showErrorDialog(project, it.message, PhantomAiBundle.message("board.create.failed"))
        }
      }, project.disposed)
    })
  }

  /** In review back to In progress: [prompt] goes to the card's newest live session (queued while busy), else a new agent. */
  @RequiresEdt
  fun followUp(project: Project, card: BoardCard, prompt: String) {
    val live = liveSession(project, card.path)
    if (live != null) BoardFollowUps.getInstance(project).send(live, prompt)
    else SidebarActions(project).newSession(card.path, card.agent, prompt)
  }

  /** Done: a planned card is dropped; [remove] removes the worktree (never the main one). Nothing is merged or pushed. */
  @RequiresEdt
  fun finish(project: Project, card: BoardCard, remove: Boolean) {
    val state = AiState.getInstance()
    if (card.planned) {
      state.forget(card.path)
      state.changed()
      return
    }
    val repo = card.repo ?: return
    if (!remove || card.isMain) return
    WorktreeRemoval(project, creator, background).remove(repo, card.path, card.branch, confirm = false) {
      state.forget(card.path)
      state.changed()
    }
  }

  /** The card's changes against the branch it started from, as a read-only diff tab; git runs off the EDT. */
  @RequiresEdt
  fun showChanges(project: Project, card: BoardCard) {
    background(Runnable {
      val base = baseOf(card)
      val diff = base?.let { diff(card.path, it) }.orEmpty()
      ApplicationManager.getApplication().invokeLater({
        if (diff.isBlank()) {
          Messages.showInfoMessage(project, PhantomAiBundle.message("board.changes.none", base ?: "-"), PhantomAiBundle.message("board.changes"))
        } else {
          val file = LightVirtualFile("${card.title}.diff", diff).apply { isWritable = false }
          FileEditorManager.getInstance(project).openFile(file, true)
        }
      }, project.disposed)
    })
  }

  /** The ref the card's branch started from (WorktreeCreator stores it as branch.<b>.base), else the default branch. Blocking. */
  fun baseOf(card: BoardCard): String? =
    card.branch?.let { line(card.path, GitCommand.CONFIG, "--get", "branch.$it.base") } ?: creator.defaultBase(card.path)

  /** Committed and uncommitted changes of [worktree] since it left [base]. Blocking. */
  fun diff(worktree: String, base: String): String {
    val from = line(worktree, GitCommand.MERGE_BASE, base, "HEAD") ?: base
    val out = git.run(worktree, GitCommand.DIFF, listOf(from))
    return if (out.success) out.lines.joinToString("\n") else ""
  }

  private fun line(repo: String, command: GitCommand, vararg args: String): String? =
    git.run(repo, command, args.toList()).takeIf { it.success }?.lines?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

  /** The newest session of [worktree] with an open terminal in any window. */
  private fun liveSession(project: Project, worktree: String): String? {
    val actions = SidebarActions(project)
    return SessionStore.getInstance().forWorktree(worktree).sortedByDescending { it.startedAt }
      .firstOrNull { actions.terminalOf(it.tabId) != null }?.tabId
  }
}

/** Follow-up prompts from the board: never interrupt a busy session, as memos (the same [MemoQueue]). EDT. */
@Service(Service.Level.PROJECT)
class BoardFollowUps(private val project: Project) : Disposable {
  private val queue = MemoQueue(
    object : MemoTerminals {
      override fun status(tabId: String) = SessionStore.getInstance().get(tabId)?.let { MemoChoices.effectiveStatus(it) }
      override fun alive(tabId: String) = SidebarActions(project).terminalOf(tabId) != null
      override fun submit(tabId: String, prompt: String): Boolean {
        val io = SidebarActions(project).terminalOf(tabId) ?: return false
        io.submit(prompt)
        return true
      }
    },
    onReturned = { notify(PhantomAiBundle.message("board.followup.returned"), NotificationType.WARNING) },
  )

  init {
    project.messageBus.connect(this).subscribe(SessionStore.TOPIC, SessionListener { tabId, _ ->
      ApplicationManager.getApplication().invokeLater({ queue.sessionChanged(tabId) }, project.disposed)
    })
  }

  /** Sends [prompt] now if the session is free; else queues it and says so. True when it went out now. */
  @RequiresEdt
  fun send(tabId: String, prompt: String): Boolean {
    val sent = queue.offer(tabId, prompt, emptyList())
    if (!sent && queue.pending().any { it.tabId == tabId }) {
      val title = SessionStore.getInstance().get(tabId)?.let { it.userTitle ?: it.firstPrompt?.take(30) ?: it.kind.displayName }.orEmpty()
      notify(PhantomAiBundle.message("board.followup.queued", title), NotificationType.INFORMATION)
    }
    return sent
  }

  fun pending(): Int = queue.pending().size

  private fun notify(message: String, type: NotificationType) {
    bestEffort {
      NotificationGroupManager.getInstance().getNotificationGroup(AiNotify.GROUP)
        .createNotification(PhantomAiBundle.message("notify.title"), message, type)
        .notify(project)
    }
  }

  override fun dispose() = Unit

  companion object {
    fun getInstance(project: Project): BoardFollowUps = project.getService(BoardFollowUps::class.java)
  }
}

/**
 * Moves a card from In progress to In review when its session finishes, whether or not a board is open: registered in
 * plugin.xml on SessionStore's topic, so it costs nothing until sessions exist.
 */
class BoardReviewMover : SessionListener {
  override fun changed(tabId: String, session: AgentSession?) {
    if (session == null) {
      last.remove(tabId)
      return
    }
    val after = MemoChoices.effectiveStatus(session)
    val before = last.put(tabId, after?.name.orEmpty())?.takeIf { it.isNotEmpty() }?.let(AgentStatus::valueOf)
    if (after != AgentStatus.DONE || before == AgentStatus.DONE) return
    ApplicationManager.getApplication().invokeLater {
      val state = AiState.getInstance()
      val entry = state.findWorktree(session.worktree) ?: state.findWorktree(session.worktree.trimEnd('/')) ?: return@invokeLater
      if (!BoardFlow.reviewAfter(entry.column, before, after)) return@invokeLater
      entry.column = BoardFlow.IN_REVIEW
      entry.order = Int.MAX_VALUE
      state.changed()
    }
  }

  private companion object {
    /** Last status seen per tab ("" = none yet), so only a change to DONE moves a card. */
    val last = ConcurrentHashMap<String, String>()
  }
}
