// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.ui

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.SimpleListCellRenderer
import dev.phantom.db.core.DbDataSource
import dev.phantom.db.core.PhantomDbBundle
import dev.phantom.db.core.exec.*

/** Full-text Search… in the Database tree: asks for the text, searches in the background, then lists the tables with rows. */
object DbFullTextSearchUi {
  fun start(project: Project, dataSource: DbDataSource, schema: DbSchemaRef?) {
    val title = PhantomDbBundle.message("action.PhantomDb.Tree.FullTextSearch.text")
    val text = Messages.showInputDialog(project, PhantomDbBundle.message("db.fts.prompt", schema?.let { it.schema ?: it.catalog } ?: dataSource.name),
                                        title, null)?.takeIf { it.isNotBlank() } ?: return
    object : Task.Backgroundable(project, PhantomDbBundle.message("db.fts.progress", text), true) {
      override fun run(indicator: ProgressIndicator) {
        val result = try {
          runBlockingCancellable {
            val schemas = schema?.let(::listOf) ?: DbMetadataCache.getInstance(project).schemas(dataSource)
            DbFullTextSearch.run(project, dataSource, schemas, text) { table, i, n ->
              indicator.isIndeterminate = false
              indicator.fraction = i.toDouble() / n
              indicator.text2 = table.name
            }
          }
        }
        catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
          throw e
        }
        catch (e: Exception) {
          notify(project, PhantomDbBundle.message("db.fts.failed", e.message ?: e.javaClass.simpleName), NotificationType.ERROR)
          return
        }
        if (result.failed.isNotEmpty()) {
          notify(project, PhantomDbBundle.message("db.fts.some.failed", result.failed.size,
                                                  result.failed.take(5).joinToString("<br>") { "${it.first.name}: ${it.second}" }), NotificationType.WARNING)
        }
        ApplicationManager.getApplication().invokeLater({ show(project, dataSource, text, result) }, project.disposed)
      }
    }.queue()
  }

  private fun show(project: Project, dataSource: DbDataSource, text: String, result: DbFullTextResult) {
    if (result.hits.isEmpty()) {
      Messages.showInfoMessage(project, PhantomDbBundle.message("db.fts.none", text, result.searched),
                               PhantomDbBundle.message("action.PhantomDb.Tree.FullTextSearch.text"))
      return
    }
    val hits = result.hits.sortedByDescending { it.count }
    // stays open after a table is opened, so the next one is a click away
    JBPopupFactory.getInstance().createPopupChooserBuilder(hits)
      .setTitle(PhantomDbBundle.message("db.fts.found", text, hits.sumOf { it.count }, hits.size))
      .setRenderer(SimpleListCellRenderer.create { label, value, _ ->
        label.icon = AllIcons.Nodes.DataTables
        label.text = PhantomDbBundle.message("db.fts.hit", listOfNotNull(value.table.schema ?: value.table.catalog, value.table.name).joinToString("."), value.count)
      })
      .setNamerForFiltering { it.table.name }
      .setCloseOnEnter(false)
      .setMovable(true)
      .setResizable(true)
      .setItemChosenCallback { DbTableDataView.open(project, dataSource, it.table, it.where) }
      .createPopup()
      .showCenteredInCurrentWindow(project)
  }

  private fun notify(project: Project, text: String, type: NotificationType) =
    NotificationGroupManager.getInstance().getNotificationGroup("Phantom Database").createNotification(text, type).notify(project)
}
