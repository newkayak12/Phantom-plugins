// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListSeparator
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.ui.UIUtil
import dev.phantom.db.core.DbDataSource
import dev.phantom.db.core.DbDataSourceService
import dev.phantom.db.core.PhantomDbBundle
import dev.phantom.db.core.PhantomDbIcons
import dev.phantom.db.core.exec.DbConnectionService
import dev.phantom.db.core.exec.DbConsoles
import dev.phantom.db.core.exec.DbMetadataCache
import dev.phantom.db.core.exec.DbRunner
import dev.phantom.db.core.exec.DbSchemaChoice
import dev.phantom.db.core.exec.DbSchemaRef
import com.intellij.openapi.application.EDT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Component
import javax.swing.Icon

/**
 * A new console of a data source: the one selected in the Database tree (in the selected schema), else the current
 * console's or `.sql` file's (in its schema), else the only one, else the one chosen from a list.
 */
class DbNewConsoleAction : DumbAwareAction() {
  init {
    // not the toolbar's "+" of Add Data Source
    templatePresentation.icon = AllIcons.Actions.AddFile
  }

  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    val project = e.project
    e.presentation.isEnabledAndVisible = project != null && DbDataSourceService.getInstance(project).dataSources.isNotEmpty()
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val owner = e.getData(PlatformCoreDataKeys.CONTEXT_COMPONENT)
    val panel = owner?.let { it as? DbExplorerPanel ?: UIUtil.getParentOfType(DbExplorerPanel::class.java, it) }
    if (panel != null) {
      panel.selectedSource()?.let { ds ->
        create(project, ds, panel.selectedSchema()?.takeIf { it.dataSourceId == ds.id }?.let { schemaChoice(project, it) })
        return
      }
    }
    else {
      val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
      DbConsoles.dataSourceFor(project, file)?.let { ds ->
        create(project, ds, DbConsoles.schemaFor(project, file))
        return
      }
    }
    val all = DbDataSourceService.getInstance(project).dataSources
    if (all.size == 1) create(project, all[0], null)
    else chooseDataSource(project, all, owner) { create(project, it, null) }
  }

  companion object {
    /** Makes and opens the console off the EDT. */
    fun create(project: Project, dataSource: DbDataSource, schema: DbSchemaChoice?) {
      DbConnectionService.getInstance(project).launch { createNow(project, dataSource, schema) }
    }

    suspend fun createNow(project: Project, dataSource: DbDataSource, schema: DbSchemaChoice?): VirtualFile? =
      DbConsoles.newConsole(project, dataSource, schema).also {
        withContext(Dispatchers.EDT) { DbExplorerPanel.find(project)?.consolesChanged(dataSource.id) }
      }

    /** The console schema choice for a tree schema: its catalog only when the name is in several catalogs. */
    fun schemaChoice(project: Project, ref: DbSchemaRef): DbSchemaChoice {
      val listed = DbMetadataCache.getInstanceIfCreated(project)?.cachedSchemas(ref.dataSourceId).orEmpty()
      return DbRunner.schemaChoices(listed).firstOrNull { it.name == ref.name && (it.catalog == null || it.catalog == ref.catalog) }
             ?: DbSchemaChoice(ref.name)
    }

    private fun chooseDataSource(project: Project, all: List<DbDataSource>, owner: Component?, onChosen: (DbDataSource) -> Unit) {
      val step = object : BaseListPopupStep<DbDataSource>(PhantomDbBundle.message("db.datasource.choose"), all) {
        override fun getTextFor(value: DbDataSource): String = value.name
        override fun getIconFor(value: DbDataSource): Icon = PhantomDbIcons.forDataSource(value)
        override fun onChosen(selectedValue: DbDataSource, finalChoice: Boolean): PopupStep<*>? = doFinalStep { onChosen(selectedValue) }
      }
      val popup = JBPopupFactory.getInstance().createListPopup(step)
      if (owner != null && owner.isShowing) popup.showInCenterOf(owner) else popup.showCenteredInCurrentWindow(project)
    }
  }
}

/** A list of every data source's consoles, grouped by data source; choosing one opens it. */
class DbJumpToConsoleAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    val project = e.project
    e.presentation.isEnabledAndVisible = project != null && DbDataSourceService.getInstance(project).dataSources.isNotEmpty()
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val owner = e.getData(PlatformCoreDataKeys.CONTEXT_COMPONENT)
    val sources = DbDataSourceService.getInstance(project).dataSources
    DbConnectionService.getInstance(project).launch {
      // listing folders and finding the files is file access: not on the EDT
      val consoles = sources.flatMap { ds -> DbConsoles.consoles(ds.id).map { ds to it } }
      withContext(Dispatchers.EDT) { show(project, consoles, owner) }
    }
  }

  private fun show(project: Project, consoles: List<Pair<DbDataSource, VirtualFile>>, owner: Component?) {
    if (consoles.isEmpty()) {
      JBPopupFactory.getInstance().createMessage(PhantomDbBundle.message("db.consoles.none")).showCenteredInCurrentWindow(project)
      return
    }
    val step = object : BaseListPopupStep<Pair<DbDataSource, VirtualFile>>(PhantomDbBundle.message("db.consoles.jump"), consoles) {
      override fun getTextFor(value: Pair<DbDataSource, VirtualFile>): String = value.second.nameWithoutExtension
      override fun getIconFor(value: Pair<DbDataSource, VirtualFile>): Icon = PhantomDbIcons.forDataSource(value.first)
      override fun getSeparatorAbove(value: Pair<DbDataSource, VirtualFile>): ListSeparator? {
        val i = values.indexOf(value)
        return if (i == 0 || values[i - 1].first.id != value.first.id) ListSeparator(value.first.name) else null
      }
      override fun isSpeedSearchEnabled(): Boolean = true
      override fun onChosen(selectedValue: Pair<DbDataSource, VirtualFile>, finalChoice: Boolean): PopupStep<*>? = doFinalStep {
        FileEditorManager.getInstance(project).openFile(selectedValue.second, true)
      }
    }
    val popup = JBPopupFactory.getInstance().createListPopup(step)
    if (owner != null && owner.isShowing) popup.showInCenterOf(owner) else popup.showCenteredInCurrentWindow(project)
  }
}
