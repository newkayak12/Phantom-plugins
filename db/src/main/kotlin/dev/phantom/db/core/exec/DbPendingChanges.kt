// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.exec

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectCloseHandler
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import dev.phantom.db.core.*
import dev.phantom.db.core.ui.DbChangesToolWindowFactory
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** A statement that changed data inside a transaction that is still open. */
class DbPendingChange(val dataSourceId: String, val sql: String, val rows: Long, val at: Instant)

/**
 * Uncommitted changes of data sources in manual-commit mode, per data source, in the order they ran. The transaction
 * itself lives on the console connection ([DbConnectionService]); this only remembers what went into it, so the
 * Database Changes tool window can show it and the project does not close over it unnoticed.
 */
@Service(Service.Level.PROJECT)
class DbPendingChanges(private val project: Project) {
  private val changes = ConcurrentHashMap<String, MutableList<DbPendingChange>>()
  private val listeners = CopyOnWriteArrayList<() -> Unit>()

  fun of(dataSourceId: String): List<DbPendingChange> = changes[dataSourceId]?.toList().orEmpty()

  fun hasPending(dataSourceId: String): Boolean = !changes[dataSourceId].isNullOrEmpty()

  /** Data sources with uncommitted changes, in the project's order. */
  fun dataSourcesWithChanges(): List<DbDataSource> = DbDataSourceService.getInstance(project).dataSources.filter { hasPending(it.id) }

  fun total(): Int = changes.values.sumOf { it.size }

  /** Records what a statement run on a manual-commit connection did to the open transaction. */
  fun afterStatement(dataSource: DbDataSource, sql: String, outcomes: List<DbOutcome>) {
    when {
      outcomes.any { it is DbOutcome.Failed } -> return
      SqlSafety.endsTransaction(sql) -> discard(dataSource.id)
      // MySQL, MariaDB and H2 commit implicitly around DDL, so everything so far is in the database; PostgreSQL DDL is transactional
      SqlSafety.isDdl(sql) && dataSource.dbms != Dbms.POSTGRESQL -> discard(dataSource.id)
      SqlSafety.changesData(sql) -> {
        val rows = outcomes.filterIsInstance<DbOutcome.Updated>().sumOf { it.count }
        changes.computeIfAbsent(dataSource.id) { CopyOnWriteArrayList() }.add(DbPendingChange(dataSource.id, sql, rows, Instant.now()))
        changed()
        ApplicationManager.getApplication().invokeLater({ DbChangesToolWindowFactory.ensureRegistered(project) }, { project.isDisposed })
      }
    }
  }

  /** Forgets the data source's changes after a commit, a rollback or a lost connection. */
  fun discard(dataSourceId: String) {
    if (changes.remove(dataSourceId) != null) changed()
  }

  suspend fun commit(dataSource: DbDataSource) = DbConnectionService.getInstance(project).endTransaction(dataSource, commit = true)

  suspend fun rollback(dataSource: DbDataSource) = DbConnectionService.getInstance(project).endTransaction(dataSource, commit = false)

  fun addListener(parent: com.intellij.openapi.Disposable, listener: () -> Unit) {
    listeners.add(listener)
    Disposer.register(parent) { listeners.remove(listener) }
  }

  private fun changed() {
    listeners.forEach { it() }
    // the SQL editor banner shows the open transaction's size
    ApplicationManager.getApplication().invokeLater({ com.intellij.ui.EditorNotifications.getInstance(project).updateAllNotifications() },
                                                    { project.isDisposed })
  }

  companion object {
    fun getInstance(project: Project): DbPendingChanges = project.service()
    fun getInstanceIfCreated(project: Project): DbPendingChanges? = project.serviceIfCreated()
  }
}

/** Asks before a project closes over uncommitted changes, which closing would roll back. */
class DbPendingChangesCloseHandler : ProjectCloseHandler {
  override fun canClose(project: Project): Boolean {
    val pending = DbPendingChanges.getInstanceIfCreated(project) ?: return true
    val dataSources = pending.dataSourcesWithChanges()
    if (dataSources.isEmpty()) return true
    val answer = Messages.showYesNoCancelDialog(
      project,
      PhantomDbBundle.message("db.changes.close.message", pending.total(), dataSources.joinToString { it.name }),
      PhantomDbBundle.message("db.changes.close.title"),
      PhantomDbBundle.message("db.changes.commit"), PhantomDbBundle.message("db.changes.rollback"), Messages.getCancelButton(),
      Messages.getWarningIcon())
    if (answer == Messages.CANCEL) return false
    val failure = runWithModalProgressBlocking(project, PhantomDbBundle.message("db.changes.close.title")) {
      runCatching { dataSources.forEach { if (answer == Messages.YES) pending.commit(it) else pending.rollback(it) } }.exceptionOrNull()
    }
    if (failure != null) {
      Messages.showErrorDialog(project, failure.message ?: failure.javaClass.simpleName, PhantomDbBundle.message("db.changes.close.title"))
      return false
    }
    return true
  }
}
