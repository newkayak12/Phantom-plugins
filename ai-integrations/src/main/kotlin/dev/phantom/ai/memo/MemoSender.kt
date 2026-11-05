// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.memo

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.annotations.RequiresEdt
import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.AgentSession
import dev.phantom.ai.core.AgentStatus
import dev.phantom.ai.core.AiNotify
import dev.phantom.ai.core.PhantomAiBundle
import dev.phantom.ai.core.SessionListener
import dev.phantom.ai.core.SessionStore
import dev.phantom.ai.core.bestEffort
import dev.phantom.ai.sidebar.SidebarActions
import dev.phantom.ai.terminal.AgentTabsHost
import dev.phantom.ai.terminal.AgentTabsListener
import dev.phantom.ai.terminal.AgentTerminalService
import dev.phantom.ai.terminal.submit

sealed interface MemoTarget {
  data class Session(val tabId: String, val label: String, val status: AgentStatus?) : MemoTarget
  data class New(val kind: AgentKind) : MemoTarget
}

object MemoChoices {
  /** The sessions in start order, then a new Claude and a new Codex; the default is the last used session, else the first, else a new Claude. */
  fun of(sessions: List<AgentSession>, lastUsed: String?): Pair<List<MemoTarget>, Int> {
    val targets = sessions.map { MemoTarget.Session(it.tabId, label(it), effectiveStatus(it)) } +
      listOf(MemoTarget.New(AgentKind.CLAUDE), MemoTarget.New(AgentKind.CODEX))
    val index = targets.indexOfFirst { it is MemoTarget.Session && it.tabId == lastUsed }
    return targets to maxOf(index, 0)
  }

  /** WAITING from Claude's idle_prompt means the turn is over: idle (DONE), not busy. A permission or question WAITING stays WAITING. */
  fun effectiveStatus(s: AgentSession): AgentStatus? =
    if (s.status == AgentStatus.WAITING && s.idleWaiting) AgentStatus.DONE else s.status

  private fun label(s: AgentSession) = s.userTitle ?: s.firstPrompt?.take(30) ?: s.kind.name
}

fun interface MemoQueueListener {
  fun changed()
}

enum class SendOutcome { SENT, QUEUED, STARTED, FAILED }

/** Sends the memos of the project as one prompt to a session (now, or queued while it is busy) or to a new one. EDT only. */
@Service(Service.Level.PROJECT)
class MemoSender(
  private val project: Project,
  private val store: AiMemoStore,
  private val resolve: (String) -> VirtualFile? = { LocalFileSystem.getInstance().refreshAndFindFileByPath(it) },
) : Disposable {
  constructor(project: Project) : this(project, AiMemoStore.getInstance(project))

  @Volatile private var lastUsed: String? = null

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
    onReturned = { batch ->
      restore(batch)
      notify(PhantomAiBundle.message("memo.returned", batch.items.size), NotificationType.WARNING)
    },
  )

  init {
    val connection = project.messageBus.connect(this)
    connection.subscribe(SessionStore.TOPIC, SessionListener { tabId, _ ->
      ApplicationManager.getApplication().invokeLater({
        queue.sessionChanged(tabId)
        publishQueueChanged()
      }, project.disposed)
    })
    connection.subscribe(AgentTabsHost.TOPIC, AgentTabsListener {
      val id = AgentTabsHost.getInstance(project).selected()?.id
      if (id != null && SessionStore.getInstance().get(id) != null) lastUsed = id
    })
  }

  fun queue(): MemoQueue = queue

  fun lastUsed(): String? = lastUsed

  fun choices(): Pair<List<MemoTarget>, Int> {
    val worktree = store.worktree() ?: return MemoChoices.of(emptyList(), lastUsed)
    return MemoChoices.of(SessionStore.getInstance().forWorktree(worktree), lastUsed)
  }

  @RequiresEdt
  fun send(target: MemoTarget, memoIds: List<Long>): SendOutcome =
    doSend(target, memoIds).also { publishQueueChanged() }

  private fun publishQueueChanged() {
    if (!project.isDisposed) project.messageBus.syncPublisher(TOPIC).changed()
  }

  private fun doSend(target: MemoTarget, memoIds: List<Long>): SendOutcome {
    val memos = store.all().filter { it.id in memoIds }
    val docs = FileDocumentManager.getInstance()
    memos.forEach { docs.saveDocument(it.marker.document) }
    val sendable = memos.mapNotNull { m -> store.toItem(m)?.let { m to it } }
    if (sendable.isEmpty()) return SendOutcome.FAILED
    val items = sendable.map { it.second }
    val prompt = MemoPrompt.build(items)
    val outcome: SendOutcome
    when (target) {
      is MemoTarget.Session -> {
        if (SidebarActions(project).terminalOf(target.tabId) == null) return SendOutcome.FAILED
        outcome = when {
          queue.offer(target.tabId, prompt, items) -> SendOutcome.SENT
          queue.pending().any { it.items === items } -> SendOutcome.QUEUED
          else -> return SendOutcome.FAILED
        }
        lastUsed = target.tabId
        if (outcome == SendOutcome.QUEUED) {
          notify(PhantomAiBundle.message("memo.queued", target.label, statusText(target.status), items.size), NotificationType.INFORMATION)
        }
      }
      is MemoTarget.New -> {
        val worktree = store.worktree() ?: return SendOutcome.FAILED
        val tab = AgentTerminalService.getInstance(project).launch(worktree, target.kind, prompt = prompt) ?: return SendOutcome.FAILED
        lastUsed = tab
        outcome = SendOutcome.STARTED
      }
    }
    store.remove(sendable.map { it.first.id })
    if (outcome != SendOutcome.QUEUED) lastUsed?.let { tab ->
      // the session may live in another window: focus it in the project that owns the tab
      val owner = ProjectManager.getInstance().openProjects.firstOrNull { !it.isDisposed && AgentTabsHost.getInstance(it).get(tab) != null } ?: project
      AgentTerminalService.getInstance(owner).focus(tab)
    }
    return outcome
  }

  /** Takes a queued batch back and puts its memos into the store again, without a notification. */
  @RequiresEdt
  fun cancel(batchId: Long): Boolean {
    val batch = queue.cancel(batchId) ?: return false
    restore(batch)
    publishQueueChanged()
    return true
  }

  private fun restore(batch: QueuedBatch) {
    val base = store.worktree()?.trimEnd('/') ?: return
    val docs = FileDocumentManager.getInstance()
    for (item in batch.items) {
      val doc = resolve("$base/${item.path}")?.takeIf { it.isValid }?.let { docs.getDocument(it) } ?: continue
      val last = maxOf(doc.lineCount - 1, 0)
      val start = (item.startLine - 1).coerceIn(0, last)
      val end = (item.endLine - 1).coerceIn(start, last)
      store.add(doc, start, end, item.text)
    }
  }

  private fun statusText(status: AgentStatus?) = when (status) {
    AgentStatus.WORKING -> PhantomAiBundle.message("memo.status.working")
    AgentStatus.WAITING -> PhantomAiBundle.message("memo.status.waiting")
    else -> ""
  }

  private fun notify(message: String, type: NotificationType) {
    bestEffort {
      NotificationGroupManager.getInstance().getNotificationGroup(AiNotify.GROUP)
        .createNotification(PhantomAiBundle.message("notify.title"), message, type)
        .notify(project)
    }
  }

  override fun dispose() {}

  companion object {
    /** Fires after every queue transition: queued, sent from the queue, returned, cancelled. */
    val TOPIC: com.intellij.util.messages.Topic<MemoQueueListener> = com.intellij.util.messages.Topic.create("AI memo queue changes", MemoQueueListener::class.java)

    fun getInstance(project: Project): MemoSender = project.getService(MemoSender::class.java)
  }
}
