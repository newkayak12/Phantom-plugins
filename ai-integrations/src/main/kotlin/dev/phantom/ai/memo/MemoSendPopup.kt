// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.memo

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.SimpleListCellRenderer
import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.AgentStatus
import dev.phantom.ai.core.AiNotify
import dev.phantom.ai.core.PhantomAiBundle
import dev.phantom.ai.core.bestEffort
import java.awt.Component

/** Lists the sessions and a new Claude / Codex; the pick gets the checked memos. */
object MemoSendPopup {
  fun label(target: MemoTarget): String = when (target) {
    is MemoTarget.Session -> {
      val status = when (target.status) {
        AgentStatus.WORKING -> PhantomAiBundle.message("memo.popup.working")
        AgentStatus.WAITING -> PhantomAiBundle.message("memo.popup.waiting")
        AgentStatus.DONE -> PhantomAiBundle.message("memo.popup.done")
        else -> null
      }
      if (status == null) target.label else "${target.label} · $status"
    }
    is MemoTarget.New -> PhantomAiBundle.message(if (target.kind == AgentKind.CODEX) "memo.popup.new.codex" else "memo.popup.new.claude")
  }

  /** Sends the checked memos to [target]; on FAILED the memos stay and a balloon says so. */
  fun sendChecked(
    project: Project,
    target: MemoTarget,
    store: AiMemoStore = AiMemoStore.getInstance(project),
    sender: MemoSender = MemoSender.getInstance(project),
  ): SendOutcome? {
    val ids = store.all().filter { it.checked }.map { it.id }
    if (ids.isEmpty()) return null
    val outcome = sender.send(target, ids)
    if (outcome == SendOutcome.FAILED) {
      bestEffort {
        NotificationGroupManager.getInstance().getNotificationGroup(AiNotify.GROUP)
          .createNotification(PhantomAiBundle.message("notify.title"), PhantomAiBundle.message("memo.send.failed"), NotificationType.WARNING)
          .notify(project)
      }
    }
    return outcome
  }

  fun show(project: Project, anchor: Component?) {
    val sender = MemoSender.getInstance(project)
    val (targets, default) = sender.choices()
    val popup = JBPopupFactory.getInstance().createPopupChooserBuilder(targets)
      .setTitle(PhantomAiBundle.message("memo.send.title"))
      .setRenderer(SimpleListCellRenderer.create("") { label(it) })
      .setSelectedValue(targets.getOrNull(default), true)
      .setItemChosenCallback { target -> sendChecked(project, target) }
      .createPopup()
    if (anchor != null && anchor.isShowing) popup.showUnderneathOf(anchor) else popup.showCenteredInCurrentWindow(project)
  }
}

/** Sends the checked memos: opens the session picker. Enabled while a memo is checked. */
class SendAiMemosAction : DumbAwareAction() {
  override fun getActionUpdateThread() = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    val project = e.project
    e.presentation.isEnabled = project != null && AiMemoStore.getInstance(project).all().any { it.checked }
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    MemoSendPopup.show(project, e.inputEvent?.component)
  }
}
