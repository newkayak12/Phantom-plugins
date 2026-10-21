// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.ui

import com.intellij.diff.DiffContentFactory
import dev.phantom.db.core.dialect
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.ui.Messages
import dev.phantom.db.core.DbDataSource
import dev.phantom.db.core.DbDataSourceService
import dev.phantom.db.core.PhantomDbBundle
import dev.phantom.db.core.PhantomDbIcons
import dev.phantom.db.core.exec.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Component
import javax.swing.Icon

/** One schema of one data source: a side of a comparison. */
data class DbSchemaSide(val dataSource: DbDataSource, val schema: DbSchemaRef) {
  val title: String get() = "${dataSource.name} · ${schema.name}"
}

/**
 * Compare With…: the selected schema against another one, from the same or another data source. Both are read from the
 * JDBC metadata into the same text form and shown in the diff viewer, whose toolbar opens the SQL that makes the other
 * schema match the selected one in that data source's console.
 */
object DbSchemaCompare {
  fun chooseAndCompare(project: Project, source: DbSchemaSide, owner: Component) {
    DbConnectionService.getInstance(project).launch {
      val cache = DbMetadataCache.getInstance(project)
      val sides = DbDataSourceService.getInstance(project).dataSources.flatMap { ds ->
        runCatching { cache.schemas(ds) }.getOrDefault(emptyList()).map { DbSchemaSide(ds, it) }
      }.filter { it != source }
      withContext(Dispatchers.EDT) {
        if (sides.isEmpty()) {
          Messages.showInfoMessage(project, PhantomDbBundle.message("db.compare.nothing"), PhantomDbBundle.message("db.compare.title"))
          return@withContext
        }
        JBPopupFactory.getInstance().createListPopup(object : BaseListPopupStep<DbSchemaSide>(PhantomDbBundle.message("db.compare.choose", source.title), sides) {
          override fun getTextFor(value: DbSchemaSide): String = value.title
          override fun getIconFor(value: DbSchemaSide): Icon = PhantomDbIcons.forDataSource(value.dataSource)
          override fun isSpeedSearchEnabled(): Boolean = true
          override fun onChosen(selectedValue: DbSchemaSide, finalChoice: Boolean): PopupStep<*>? {
            doFinalStep { compare(project, source, selectedValue) }
            return FINAL_CHOICE
          }
        }).showInBestPositionFor(com.intellij.ide.DataManager.getInstance().getDataContext(owner))
      }
    }
  }

  fun compare(project: Project, source: DbSchemaSide, target: DbSchemaSide) {
    DbConnectionService.getInstance(project).launch {
      val result = runCatching {
        DbSchemaReader.read(project, source.dataSource, source.schema) to DbSchemaReader.read(project, target.dataSource, target.schema)
      }
      withContext(Dispatchers.EDT) {
        val (left, right) = result.getOrElse { e ->
          Messages.showErrorDialog(project, (e as? java.sql.SQLException)?.let(DbExecutor::message) ?: e.message.orEmpty(),
                                   PhantomDbBundle.message("db.compare.title"))
          return@withContext
        }
        show(project, source, left, target, right)
      }
    }
  }

  private fun show(project: Project, source: DbSchemaSide, left: DbSchemaModel, target: DbSchemaSide, right: DbSchemaModel) {
    val contents = DiffContentFactory.getInstance()
    val request = SimpleDiffRequest(PhantomDbBundle.message("db.compare.diff.title", source.title, target.title),
                                    contents.create(project, DbSchemaDiff.dump(left)), contents.create(project, DbSchemaDiff.dump(right)),
                                    source.title, target.title)
    val statements = DbSchemaDiff.migration(left, right, target.dataSource.dialect())
    request.putUserData(DiffUserDataKeys.CONTEXT_ACTIONS, listOf(object : DumbAwareAction(
      PhantomDbBundle.message("db.compare.migration", target.dataSource.name), null, AllIcons.Actions.Execute) {
      override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
      override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = statements.isNotEmpty()
      }
      override fun actionPerformed(e: AnActionEvent) {
        DbConsoles.openConsole(project, target.dataSource, migrationText(source, target, statements))
      }
    }))
    DiffManager.getInstance().showDiff(project, request)
  }

  internal fun migrationText(source: DbSchemaSide, target: DbSchemaSide, statements: List<String>): String =
    "-- " + PhantomDbBundle.message("db.compare.migration.header", target.title, source.title) + "\n" + statements.joinToString("\n")
}
