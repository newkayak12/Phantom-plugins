// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.exec

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.ui.EditorNotifications
import dev.phantom.db.core.DbDataSource
import dev.phantom.db.core.DbDataSourceService
import dev.phantom.db.core.PhantomDbBundle
import dev.phantom.db.core.SqlFile

/**
 * The commit mode of a data source's console connection, switched from the SQL editor: Auto-commit, or Manual, where
 * changes wait in an open transaction (shown in the editor banner and in Database Changes) until Commit or Roll Back.
 * There is one console connection per data source, so the mode belongs to the data source and is saved with it.
 */
object DbTransactions {
  /** Switches [dataSource] to [manual]. Leaving manual mode with open changes asks to commit or roll them back first. */
  fun setManual(project: Project, dataSource: DbDataSource, manual: Boolean): Boolean {
    if (dataSource.readOnly || dataSource.manualCommit == manual) return false
    if (!manual && !endOrKeep(project, dataSource, PhantomDbBundle.message("db.tx.switch.title"))) return false
    val changed = dataSource.copy().also { it.manualCommit = manual }
    DbDataSourceService.getInstance(project).save(changed, null)
    DbConnectionService.getInstance(project).launch { DbConnectionService.getInstance(project).applyCommitMode(changed) }
    if (manual) dev.phantom.db.core.ui.DbChangesToolWindowFactory.ensureRegistered(project)
    EditorNotifications.getInstance(project).updateAllNotifications()
    return true
  }

  /**
   * Before something would roll back [dataSource]'s open transaction (disconnecting, removing, switching mode): asks
   * whether to commit or roll it back. False when the user cancelled or ending it failed.
   */
  fun endOrKeep(project: Project, dataSource: DbDataSource, title: String): Boolean {
    val pending = DbPendingChanges.getInstanceIfCreated(project) ?: return true
    val count = pending.of(dataSource.id).size
    if (count == 0) return true
    val answer = Messages.showYesNoCancelDialog(project, PhantomDbBundle.message("db.tx.end.message", count, dataSource.name), title,
                                                PhantomDbBundle.message("db.changes.commit"), PhantomDbBundle.message("db.changes.rollback"),
                                                Messages.getCancelButton(), Messages.getWarningIcon())
    if (answer == Messages.CANCEL) return false
    val failure = runWithModalProgressBlocking(project, title) {
      runCatching { if (answer == Messages.YES) pending.commit(dataSource) else pending.rollback(dataSource) }.exceptionOrNull()
    }
    if (failure != null) {
      Messages.showErrorDialog(project, failure.message ?: failure.javaClass.simpleName, title)
      return false
    }
    return true
  }

  fun dataSourceOf(e: AnActionEvent): DbDataSource? {
    val project = e.project ?: return null
    val file = (e.getData(CommonDataKeys.PSI_FILE) as? SqlFile)?.virtualFile ?: e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return null
    return DbConsoles.dataSourceFor(project, file)
  }
}

class DbManualCommitToggleAction : DumbAwareToggleAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    super.update(e)
    val ds = DbTransactions.dataSourceOf(e)
    e.presentation.isEnabledAndVisible = ds != null && !ds.readOnly
  }

  override fun isSelected(e: AnActionEvent): Boolean = DbTransactions.dataSourceOf(e)?.manualCommit == true

  override fun setSelected(e: AnActionEvent, state: Boolean) {
    val project = e.project ?: return
    DbTransactions.setManual(project, DbTransactions.dataSourceOf(e) ?: return, state)
  }
}

abstract class DbEndTransactionAction(private val commit: Boolean) : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    val project = e.project
    val ds = DbTransactions.dataSourceOf(e)
    e.presentation.isVisible = ds != null && ds.manualCommit
    e.presentation.isEnabled = project != null && ds != null && DbPendingChanges.getInstanceIfCreated(project)?.hasPending(ds.id) == true
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val ds = DbTransactions.dataSourceOf(e) ?: return
    end(project, ds, commit)
  }

  companion object {
    fun end(project: Project, dataSource: DbDataSource, commit: Boolean) {
      val pending = DbPendingChanges.getInstance(project)
      DbConnectionService.getInstance(project).launch {
        val failure = runCatching { if (commit) pending.commit(dataSource) else pending.rollback(dataSource) }.exceptionOrNull()
        if (failure != null) {
          com.intellij.notification.NotificationGroupManager.getInstance().getNotificationGroup("Phantom Database")
            .createNotification(PhantomDbBundle.message(if (commit) "db.changes.commit" else "db.changes.rollback"),
                                failure.message ?: failure.javaClass.simpleName, com.intellij.notification.NotificationType.ERROR)
            .notify(project)
        }
      }
    }
  }
}

class DbCommitAction : DbEndTransactionAction(commit = true)

class DbRollbackAction : DbEndTransactionAction(commit = false)
