// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.ui

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import dev.phantom.db.core.DbDataSource
import dev.phantom.db.core.DbDataSourceService
import dev.phantom.db.core.PhantomDbBundle
import dev.phantom.db.core.PhantomDbIcons
import dev.phantom.db.core.exec.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Component
import javax.swing.Icon

/** Copy Table To… in the Database tree: a schema of any data source to copy into, then the copy in the background. */
object DbTableCopyUi {
  fun chooseAndCopy(project: Project, dataSource: DbDataSource, table: DbTableRef, owner: Component) {
    DbConnectionService.getInstance(project).launch {
      val cache = DbMetadataCache.getInstance(project)
      val here = DbSchemaRef(table.dataSourceId, table.catalog, table.schema)
      val sides = DbDataSourceService.getInstance(project).dataSources.filterNot { it.readOnly }.flatMap { ds ->
        runCatching { cache.schemas(ds) }.getOrDefault(emptyList()).map { DbSchemaSide(ds, it) }
      }.filter { it.schema != here }
      withContext(Dispatchers.EDT) {
        if (sides.isEmpty()) {
          Messages.showInfoMessage(project, PhantomDbBundle.message("db.copy.table.nothing"), PhantomDbBundle.message("action.PhantomDb.Tree.CopyTable.text"))
          return@withContext
        }
        JBPopupFactory.getInstance().createListPopup(object : BaseListPopupStep<DbSchemaSide>(PhantomDbBundle.message("db.copy.table.choose", table.name), sides) {
          override fun getTextFor(value: DbSchemaSide): String = value.title
          override fun getIconFor(value: DbSchemaSide): Icon = PhantomDbIcons.forDataSource(value.dataSource)
          override fun isSpeedSearchEnabled(): Boolean = true
          override fun onChosen(selectedValue: DbSchemaSide, finalChoice: Boolean): PopupStep<*>? {
            doFinalStep { copy(project, dataSource, table, selectedValue) }
            return FINAL_CHOICE
          }
        }).showInBestPositionFor(com.intellij.ide.DataManager.getInstance().getDataContext(owner))
      }
    }
  }

  fun copy(project: Project, dataSource: DbDataSource, table: DbTableRef, target: DbSchemaSide) {
    object : Task.Backgroundable(project, PhantomDbBundle.message("db.copy.table.progress", table.name, 0), true) {
      override fun run(indicator: ProgressIndicator) {
        val (text, type) = try {
          val result = runBlockingCancellable {
            DbTableCopy.run(project, dataSource, table, target.dataSource, target.schema) { indicator.text = PhantomDbBundle.message("db.copy.table.progress", table.name, it) }
          }
          PhantomDbBundle.message("db.copy.table.done", result.rows, "${target.dataSource.name} · ${result.target}",
                                  (if (result.created) PhantomDbBundle.message("db.copy.table.done.created") else "") +
                                  (if (result.pending) PhantomDbBundle.message("db.copy.table.done.pending") else "")) to NotificationType.INFORMATION
        }
        catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
          throw e
        }
        catch (e: Exception) {
          PhantomDbBundle.message("db.copy.table.error", table.name, e.message ?: e.javaClass.simpleName) to NotificationType.ERROR
        }
        NotificationGroupManager.getInstance().getNotificationGroup("Phantom Database").createNotification(text, type).notify(project)
      }
    }.queue()
  }
}
