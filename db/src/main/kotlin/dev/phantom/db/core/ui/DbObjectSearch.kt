// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributor
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributorFactory
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.psi.codeStyle.NameUtil
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.util.Processor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.phantom.db.core.DbDataSource
import dev.phantom.db.core.DbDataSourceService
import dev.phantom.db.core.PhantomDbBundle
import dev.phantom.db.core.PhantomDbIcons
import dev.phantom.db.core.dialect
import dev.phantom.db.core.exec.DbDdl
import dev.phantom.db.core.exec.DbMetadataCache
import dev.phantom.db.core.exec.DbObjectCache
import dev.phantom.db.core.exec.DbRoutine
import dev.phantom.db.core.exec.DbTableRef
import java.awt.BorderLayout
import java.awt.Component
import javax.swing.Icon
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.SwingConstants

/** A table, view, routine or column the Database tree has loaded. */
class DbObjectItem(val dataSource: DbDataSource, val kind: Kind, val name: String, val schema: String,
                   val table: DbTableRef? = null, val routine: DbRoutine? = null) {
  enum class Kind { TABLE, VIEW, ROUTINE, COLUMN }

  /** Data source and schema, and the table for a column. */
  val location: String get() = (if (kind == Kind.COLUMN) table!!.name + " · " else "") + dataSource.name + " / " + schema

  val icon: Icon get() = when (kind) {
    Kind.TABLE -> AllIcons.Nodes.DataTables
    Kind.VIEW -> AllIcons.Nodes.DataTables
    Kind.ROUTINE -> if (routine!!.function) AllIcons.Nodes.Function else AllIcons.Nodes.Method
    Kind.COLUMN -> AllIcons.Nodes.DataColumn
  }

  override fun toString(): String = "$name ($location)"
}

/**
 * Go to a database object by name, over what the tree has already loaded in every data source of the project. It
 * reads the metadata caches only and never connects; a data source nobody expanded has nothing to offer.
 */
object DbObjectSearch {
  fun items(project: Project): List<DbObjectItem> {
    val sources = DbDataSourceService.getInstance(project).dataSources.associateBy { it.id }
    val result = ArrayList<DbObjectItem>()
    DbMetadataCache.getInstanceIfCreated(project)?.let { cache ->
      for (ref in cache.cachedTables()) {
        ProgressManager.checkCanceled()
        val ds = sources[ref.dataSourceId] ?: continue
        result += DbObjectItem(ds, if (ref.isView) DbObjectItem.Kind.VIEW else DbObjectItem.Kind.TABLE, ref.name, schemaOf(ref), table = ref)
      }
      for ((ref, columns) in cache.cachedColumns()) {
        ProgressManager.checkCanceled()
        val ds = sources[ref.dataSourceId] ?: continue
        columns.mapTo(result) { DbObjectItem(ds, DbObjectItem.Kind.COLUMN, it.name, schemaOf(ref), table = ref) }
      }
    }
    DbObjectCache.getInstanceIfCreated(project)?.let { objects ->
      for (routine in objects.cachedRoutines()) {
        ProgressManager.checkCanceled()
        val ds = sources[routine.schema.dataSourceId] ?: continue
        result += DbObjectItem(ds, DbObjectItem.Kind.ROUTINE, routine.name, routine.schema.name, routine = routine)
      }
    }
    return result
  }

  /** Items whose name matches [pattern] (camel humps, any case, anywhere in the name), best matches and tables first. */
  fun search(project: Project, pattern: String): List<DbObjectItem> {
    if (pattern.isBlank()) return emptyList()
    val matcher = NameUtil.buildMatcher("*" + pattern.trim(), NameUtil.MatchingCaseSensitivity.NONE)
    return items(project).filter { ProgressManager.checkCanceled(); matcher.matches(it.name) }
      .sortedWith(compareByDescending<DbObjectItem> { matcher.matchingDegree(it.name) }.thenBy { it.kind }.thenBy { it.name.lowercase() })
  }

  /** Selects the object in the tree and opens it: table data, a view's or routine's DDL, a column's table data. */
  fun open(project: Project, item: DbObjectItem) {
    when (item.kind) {
      DbObjectItem.Kind.TABLE, DbObjectItem.Kind.VIEW -> {
        DbTreeNavigation.select(project, item.dataSource, item.table!!, null, focus = false)
        DbExplorerPanel.openObject(project, item.dataSource, item.table)
      }
      DbObjectItem.Kind.COLUMN -> {
        DbTreeNavigation.select(project, item.dataSource, item.table!!, item.name, focus = false)
        DbTableDataView.open(project, item.dataSource, item.table)
      }
      DbObjectItem.Kind.ROUTINE -> {
        DbTreeNavigation.select(project, item.dataSource, item.routine!!, focus = false)
        if (item.dataSource.dialect().hasDialect) DbExplorerPanel.showDdl(project, item.dataSource, DbDdl.Target.Routine(item.routine))
      }
    }
  }

  private fun schemaOf(ref: DbTableRef): String = ref.schema ?: ref.catalog.orEmpty()
}

/** The "Database" tab of Search Everywhere. */
class DbSearchEverywhereContributor(private val project: Project) : SearchEverywhereContributor<DbObjectItem> {
  override fun getSearchProviderId(): String = ID
  override fun getGroupName(): String = PhantomDbBundle.message("db.search.group")
  override fun getSortWeight(): Int = 600
  override fun showInFindResults(): Boolean = false
  override fun isShownInSeparateTab(): Boolean = true
  override fun isDumbAware(): Boolean = true

  override fun fetchElements(pattern: String, progressIndicator: ProgressIndicator, consumer: Processor<in DbObjectItem>) {
    for (item in DbObjectSearch.search(project, pattern)) {
      progressIndicator.checkCanceled()
      if (!consumer.process(item)) return
    }
  }

  override fun processSelectedItem(selected: DbObjectItem, modifiers: Int, searchText: String): Boolean {
    DbObjectSearch.open(project, selected)
    return true
  }

  override fun getElementsRenderer(): ListCellRenderer<in DbObjectItem> = DbObjectRenderer()

  override fun getItemDescription(element: DbObjectItem): String = element.location

  class Factory : SearchEverywhereContributorFactory<DbObjectItem> {
    override fun createContributor(initEvent: AnActionEvent): SearchEverywhereContributor<DbObjectItem> =
      DbSearchEverywhereContributor(initEvent.project!!)

    override fun isAvailable(project: Project): Boolean = DbDataSourceService.getInstance(project).dataSources.isNotEmpty()
  }

  companion object {
    const val ID: String = "PhantomDbObjects"
  }
}

/** Navigate → Database Object…: Search Everywhere on its Database tab. */
class DbGotoObjectAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = e.project != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    val manager = SearchEverywhereManager.getInstance(e.project ?: return)
    if (manager.isShown) manager.selectedTabID = DbSearchEverywhereContributor.ID
    else manager.show(DbSearchEverywhereContributor.ID, "", e)
  }
}

/** Kind icon and name on the left; data source and schema with the vendor icon on the right. */
private class DbObjectRenderer : ListCellRenderer<DbObjectItem> {
  private val nameText = SimpleColoredComponent()
  private val locationLabel = JBLabel().apply { horizontalTextPosition = SwingConstants.LEFT }
  private val panel = JPanel(BorderLayout()).apply {
    border = JBUI.Borders.empty(1, 4)
    add(nameText, BorderLayout.CENTER)
    add(locationLabel, BorderLayout.EAST)
  }

  override fun getListCellRendererComponent(list: JList<out DbObjectItem>, value: DbObjectItem?, index: Int, selected: Boolean, focused: Boolean): Component {
    val background = UIUtil.getListBackground(selected, focused)
    val foreground = UIUtil.getListForeground(selected, focused)
    panel.background = background
    nameText.clear()
    nameText.background = background
    if (value != null) {
      nameText.icon = value.icon
      nameText.append(value.name, SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, foreground))
      locationLabel.text = value.location
      locationLabel.icon = PhantomDbIcons.forDataSource(value.dataSource)
      locationLabel.foreground = if (selected) foreground else UIUtil.getContextHelpForeground()
    }
    return panel
  }
}
