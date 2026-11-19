// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.ui

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import dev.phantom.db.core.DbDataSource
import dev.phantom.db.core.DbDataSourceService
import dev.phantom.db.core.PhantomDbBundle
import dev.phantom.db.core.PhantomDbIcons
import dev.phantom.db.core.dialect
import dev.phantom.db.core.exec.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Component
import javax.swing.Icon

/** One table of one data source, a side of Compare Content. */
data class DbTableSide(val dataSource: DbDataSource, val table: DbTableRef) {
  val title: String get() = "${dataSource.name} · ${listOfNotNull(table.schema ?: table.catalog, table.name).joinToString(".")}"
}

/**
 * Compare Content… in the Database tree: two selected tables, or the selected one and a table picked from another
 * schema (the one of the same name, else chosen from its tables). The rows that differ are shown in the diff viewer,
 * whose toolbar opens the SQL that makes the right table match the left in that data source's console.
 */
object DbDataCompareUi {
  fun chooseAndCompare(project: Project, left: DbTableSide, owner: Component) {
    DbConnectionService.getInstance(project).launch {
      val cache = DbMetadataCache.getInstance(project)
      val here = DbSchemaRef(left.table.dataSourceId, left.table.catalog, left.table.schema)
      val sides = DbDataSourceService.getInstance(project).dataSources.flatMap { ds ->
        runCatching { cache.schemas(ds) }.getOrDefault(emptyList()).map { DbSchemaSide(ds, it) }
      }.filter { it.schema != here }
      withContext(Dispatchers.EDT) {
        if (sides.isEmpty()) {
          Messages.showInfoMessage(project, PhantomDbBundle.message("db.data.compare.nothing"), PhantomDbBundle.message("action.PhantomDb.Tree.CompareContent.text"))
          return@withContext
        }
        popup(PhantomDbBundle.message("db.data.compare.choose.schema", left.table.name), sides, { it.title }, { PhantomDbIcons.forDataSource(it.dataSource) }, owner) { side ->
          DbConnectionService.getInstance(project).launch {
            val tables = runCatching { cache.tables(side.dataSource, side.schema) }.getOrDefault(emptyList())
            withContext(Dispatchers.EDT) {
              val same = tables.firstOrNull { it.name.equals(left.table.name, ignoreCase = true) }
              when {
                same != null -> compare(project, left, DbTableSide(side.dataSource, same))
                tables.isEmpty() -> Messages.showInfoMessage(project, PhantomDbBundle.message("db.data.compare.no.tables", side.title),
                                                             PhantomDbBundle.message("action.PhantomDb.Tree.CompareContent.text"))
                else -> popup(PhantomDbBundle.message("db.data.compare.choose.table", side.title), tables, { it.name },
                              { if (it.isView) AllIcons.Nodes.DataSchema else AllIcons.Nodes.DataTables }, owner) {
                  compare(project, left, DbTableSide(side.dataSource, it))
                }
              }
            }
          }
        }
      }
    }
  }

  private fun <T> popup(title: String, items: List<T>, text: (T) -> String, icon: (T) -> Icon, owner: Component, chosen: (T) -> Unit) {
    JBPopupFactory.getInstance().createListPopup(object : BaseListPopupStep<T>(title, items) {
      override fun getTextFor(value: T): String = text(value)
      override fun getIconFor(value: T): Icon = icon(value)
      override fun isSpeedSearchEnabled(): Boolean = true
      override fun onChosen(selectedValue: T, finalChoice: Boolean): PopupStep<*>? = doFinalStep { chosen(selectedValue) }
    }).showInBestPositionFor(com.intellij.ide.DataManager.getInstance().getDataContext(owner))
  }

  fun compare(project: Project, left: DbTableSide, right: DbTableSide) {
    object : Task.Backgroundable(project, PhantomDbBundle.message("db.data.compare.progress", left.title, right.title), true) {
      override fun run(indicator: ProgressIndicator) {
        val diff = try {
          runBlockingCancellable { DbDataCompare.run(project, left.dataSource, left.table, right.dataSource, right.table) }
        }
        catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
          throw e
        }
        catch (e: Exception) {
          val message = (e as? java.sql.SQLException)?.let(DbExecutor::message) ?: e.message ?: e.javaClass.simpleName
          NotificationGroupManager.getInstance().getNotificationGroup("Phantom Database")
            .createNotification(PhantomDbBundle.message("db.data.compare.failed", message), NotificationType.ERROR).notify(project)
          return
        }
        ApplicationManager.getApplication().invokeLater({ show(project, left, right, diff) }, project.disposed)
      }
    }.queue()
  }

  private fun show(project: Project, left: DbTableSide, right: DbTableSide, diff: DbDataDiff) {
    if (diff.identical) {
      Messages.showInfoMessage(project, PhantomDbBundle.message("db.data.compare.identical", left.title, right.title, diff.same),
                               PhantomDbBundle.message("action.PhantomDb.Tree.CompareContent.text"))
      return
    }
    val (leftText, rightText) = DbDataCompare.text(diff)
    val contents = DiffContentFactory.getInstance()
    val title = PhantomDbBundle.message("db.data.compare.title", diff.onlyLeft.size, diff.onlyRight.size, diff.changed.size, diff.same)
    val request = SimpleDiffRequest(title, contents.create(project, leftText), contents.create(project, rightText), left.title, right.title)
    request.putUserData(DiffUserDataKeys.CONTEXT_ACTIONS, listOf(object : DumbAwareAction(
      PhantomDbBundle.message("db.data.compare.sync", right.title), null, AllIcons.Actions.Execute) {
      override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
      override fun actionPerformed(e: AnActionEvent) {
        val dbms = right.dataSource.dialect()
        val header = "-- " + PhantomDbBundle.message("db.data.compare.sync.header", right.title, left.title) +
                     (if (diff.keyed) "" else "\n-- " + PhantomDbBundle.message("db.data.compare.sync.no.key"))
        DbConsoles.openConsole(project, right.dataSource, header + "\n" + DbDataCompare.sync(diff, dbms, DbNames.table(dbms, right.table)).joinToString("\n"))
      }
    }))
    DiffManager.getInstance().showDiff(project, request)
  }
}
