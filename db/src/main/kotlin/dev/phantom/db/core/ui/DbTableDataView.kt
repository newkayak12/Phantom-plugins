// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.ui

import com.intellij.database.DatabaseDataKeys
import dev.phantom.db.core.dialect
import com.intellij.database.datagrid.DataGrid
import com.intellij.database.datagrid.ModelIndex
import com.intellij.database.run.ui.DataAccessType
import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.ui.TextFieldWithStoredHistory
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.phantom.db.core.DbDataSource
import dev.phantom.db.core.DbDataSourceService
import dev.phantom.db.core.PhantomDbBundle
import dev.phantom.db.core.exec.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.TestOnly
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.JPanel

/**
 * A table's data in DB Results: WHERE and ORDER BY fields over the grid (Enter applies, earlier filters are in the
 * drop-down), one page of rows at a time, and the row count on demand. The grid is the usual result grid, so cells
 * edit in place; its popup and Ctrl+B follow foreign keys to the referenced row and back.
 */
class DbTableDataView(private val project: Project, dataSource: DbDataSource, val table: DbTableRef) : JPanel(BorderLayout()), Disposable {
  internal val dataSourceId = dataSource.id
  private val fallback = dataSource
  private val key = "${dataSource.id}|${table.catalog}|${table.schema}|${table.name}"
  private val where = TextFieldWithStoredHistory("phantom.db.where:$key")
  private val orderBy = TextFieldWithStoredHistory("phantom.db.order:$key")
  private val pageSizes = ComboBox(arrayOf(100, 500, 1000, 5000)).apply {
    selectedItem = PropertiesComponent.getInstance().getInt(PAGE_SIZE_KEY, 500)
    addActionListener {
      PropertiesComponent.getInstance().setValue(PAGE_SIZE_KEY, pageSize, 500)
      load(0)
    }
  }
  private val range = JBLabel()
  private val total = JBLabel()
  val result: DbResultPanel = DbResultPanel(project).also {
    it.dataSource = dataSource
    it.table = table
    Disposer.register(this, it)
  }
  private var job: Job? = null

  @get:TestOnly
  var offset: Int = 0
    private set

  @get:TestOnly
  var hasNext: Boolean = false
    private set

  @get:TestOnly
  var shownCount: Long? = null
    private set

  private val pageSize: Int get() = pageSizes.selectedItem as? Int ?: 500

  private val dataSource: DbDataSource get() = DbDataSourceService.getInstance(project).find(dataSourceId) ?: fallback

  init {
    val enter = object : KeyAdapter() {
      override fun keyPressed(e: KeyEvent) {
        if (e.keyCode != KeyEvent.VK_ENTER) return
        (e.source as? javax.swing.JComponent)?.let { (UIUtil.getParentOfType(TextFieldWithStoredHistory::class.java, it))?.addCurrentTextToHistory() }
        load(0)
      }
    }
    where.addKeyboardListener(enter)
    orderBy.addKeyboardListener(enter)
    (where.textEditor as? com.intellij.ui.components.JBTextField)?.emptyText?.text = PhantomDbBundle.message("db.data.where.hint")
    (orderBy.textEditor as? com.intellij.ui.components.JBTextField)?.emptyText?.text = PhantomDbBundle.message("db.data.order.hint")
    where.toolTipText = PhantomDbBundle.message("db.data.where.hint")
    orderBy.toolTipText = PhantomDbBundle.message("db.data.order.hint")

    val filters = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
      border = JBUI.Borders.empty(2, 6)
      add(JBLabel("WHERE"), BorderLayout.WEST)
      add(where, BorderLayout.CENTER)
      add(JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
        add(JBLabel("ORDER BY"), BorderLayout.WEST)
        add(orderBy.also { it.preferredSize = JBUI.size(220, it.preferredSize.height) }, BorderLayout.CENTER)
      }, BorderLayout.EAST)
    }
    val paging = ActionManager.getInstance().createActionToolbar("PhantomDbTableData", DefaultActionGroup(
      nav(AllIcons.Actions.Play_first, "db.data.first", { offset > 0 }) { load(0) },
      nav(AllIcons.Actions.Play_back, "db.data.previous", { offset > 0 }) { load(maxOf(0, offset - pageSize)) },
      nav(AllIcons.Actions.Play_forward, "db.data.next", { hasNext }) { load(offset + pageSize) },
      nav(AllIcons.Actions.Play_last, "db.data.last", { hasNext || offset > 0 }) { last() },
      nav(AllIcons.Actions.Refresh, "db.action.refresh.text", { true }) { load(offset) },
    ), true).also { it.targetComponent = this }
    val pager = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
      add(paging.component)
      add(range)
      add(total)
      add(ActionLink(PhantomDbBundle.message("db.data.count")) { count() })
      add(JBLabel(PhantomDbBundle.message("db.data.page.size")))
      add(pageSizes)
    }
    add(JPanel(BorderLayout()).apply {
      add(filters, BorderLayout.NORTH)
      add(pager, BorderLayout.SOUTH)
    }, BorderLayout.NORTH)
    add(result, BorderLayout.CENTER)
  }

  /** Sets the filter and order and loads the first page. */
  fun apply(whereText: String, orderText: String = orderBy.text) {
    where.text = whereText
    orderBy.text = orderText
    if (whereText.isNotBlank()) where.addCurrentTextToHistory()
    load(0)
  }

  fun load(newOffset: Int) {
    job?.cancel()
    val ds = dataSource
    val size = pageSize
    val sql = DbTableQuery.select(ds.dialect(), table, where.text, orderBy.text, size + 1, newOffset)
    result.dataSource = ds
    result.showRunning(SqlSafety.preview(sql))
    shownCount = null
    total.text = ""
    val running = DbConnectionService.getInstance(project).launch {
      try {
        // one row more than a page tells whether there is a next page
        val outcomes = DbExecutor.execute(project, ds.copy().also { it.maxRows = size + 1 }, listOf(sql))
        val rows = outcomes.singleOrNull() as? DbOutcome.Rows
        val page = rows?.let { DbOutcome.Rows(it.sql, it.columns, it.rows.take(size), false, it.elapsedMs) }
        withContext(Dispatchers.EDT) {
          offset = newOffset
          hasNext = rows != null && rows.rows.size > size
          range.text = if (page == null || page.rows.isEmpty()) "" else PhantomDbBundle.message("db.data.range", newOffset + 1, newOffset + page.rows.size)
          result.showOutcomes(if (page != null) listOf(page) else outcomes)
        }
      }
      catch (e: CancellationException) {
        throw e
      }
      catch (e: java.sql.SQLException) {
        withContext(Dispatchers.EDT) { result.showError(DbExecutor.message(e)) }
      }
      catch (e: DbException) {
        withContext(Dispatchers.EDT) { result.showError(e.message.orEmpty()) }
      }
    }
    job = running
    result.job = running
  }

  private fun count(onCounted: (Long) -> Unit = {}) {
    val ds = dataSource
    val sql = DbTableQuery.count(ds.dialect(), table, where.text)
    total.text = PhantomDbBundle.message("db.data.counting")
    DbConnectionService.getInstance(project).launch {
      val outcome = runCatching { DbExecutor.execute(project, ds, listOf(sql)).singleOrNull() }.getOrNull()
      val count = ((outcome as? DbOutcome.Rows)?.rows?.firstOrNull()?.firstOrNull() as? Number)?.toLong()
      withContext(Dispatchers.EDT) {
        shownCount = count
        total.text = if (count != null) PhantomDbBundle.message("db.data.total", count) else (outcome as? DbOutcome.Failed)?.message.orEmpty()
        if (count != null) onCounted(count)
      }
    }
  }

  private fun last() = count { load(DbTableQuery.lastPageOffset(it, pageSize)) }

  private fun nav(icon: javax.swing.Icon, key: String, enabled: () -> Boolean, perform: () -> Unit): AnAction =
    object : DumbAwareAction(PhantomDbBundle.message(key), null, icon) {
      override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
      override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = enabled()
      }

      override fun actionPerformed(e: AnActionEvent) = perform()
    }

  override fun dispose() {
    job?.cancel()
  }

  companion object {
    /** Tree and foreign key metadata name the table type differently (`BASE TABLE`, `TABLE`); the name is what counts. */
    private fun DbTableRef.sameTable(other: DbTableRef) = catalog == other.catalog && schema == other.schema && name == other.name

    private const val PAGE_SIZE_KEY = "phantom.db.pageSize"

    /** Opens [table]'s data in DB Results (one tab per table), optionally filtered by [where]. */
    fun open(project: Project, dataSource: DbDataSource, table: DbTableRef, where: String? = null): DbTableDataView {
      val view = DbResultsToolWindow.tableTab(project, table.name, { (it as? DbTableDataView)?.let { v -> v.dataSourceId == dataSource.id && v.table.sameTable(table) } == true }) {
        DbTableDataView(project, dataSource, table)
      } as DbTableDataView
      if (where != null) view.apply(where) else view.load(0)
      return view
    }
  }
}

/**
 * Go to Referenced Row (also Ctrl+B in the grid) and Referencing Rows, for the cell at the caret of a table's data
 * view. They read the foreign keys of the table once (cached with the schema tree's metadata).
 */
object DbForeignKeyActions {
  private val referenced = object : DumbAwareAction(PhantomDbBundle.message("db.fk.referenced"), null, AllIcons.Actions.Forward) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    override fun update(e: AnActionEvent) {
      e.presentation.isEnabledAndVisible = context(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
      val c = context(e) ?: return
      DbConnectionService.getInstance(c.project).launch {
        val fks = runCatching { DbObjectCache.getInstance(c.project).foreignKeys(c.dataSource, c.table) }.getOrDefault(emptyList())
          .filter { fk -> fk.columns.any { it.equals(c.column, ignoreCase = true) } }
        withContext(Dispatchers.EDT) {
          choose(c, fks, { "${it.refTable.name} (${it.refColumns.joinToString()})" }, "db.fk.none") { fk ->
            DbForeignKeyNavigation.referenced(c.dataSource.dialect(), fk, c.valueOf)?.let { DbTableDataView.open(c.project, c.dataSource, fk.refTable, it) }
          }
        }
      }
    }
  }

  private val referencing = object : DumbAwareAction(PhantomDbBundle.message("db.fk.referencing"), null, AllIcons.Actions.Back) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    override fun update(e: AnActionEvent) {
      e.presentation.isEnabledAndVisible = context(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
      val c = context(e) ?: return
      DbConnectionService.getInstance(c.project).launch {
        val fks = runCatching { DbObjectCache.getInstance(c.project).referencingKeys(c.dataSource, c.table) }.getOrDefault(emptyList())
        withContext(Dispatchers.EDT) {
          choose(c, fks, { "${it.table.name} (${it.columns.joinToString()})" }, "db.fk.no.referencing") { fk ->
            DbForeignKeyNavigation.referencing(c.dataSource.dialect(), fk, c.valueOf)?.let { DbTableDataView.open(c.project, c.dataSource, fk.table, it) }
          }
        }
      }
    }
  }

  fun group(): ActionGroup = DefaultActionGroup(referenced, referencing)

  /** Ctrl+B (Go to Declaration's keys) on the grid follows the foreign key of the cell. */
  fun installShortcut(grid: DataGrid) {
    val keys = ActionManager.getInstance().getAction(IdeActions.ACTION_GOTO_DECLARATION)?.shortcutSet ?: return
    referenced.registerCustomShortcutSet(keys, grid.panel.component)
  }

  private class Context(val project: Project, val dataSource: DbDataSource, val table: DbTableRef, val column: String, val valueOf: (String) -> Any?)

  private fun context(e: AnActionEvent): Context? {
    val project = e.project ?: return null
    val grid = e.getData(DatabaseDataKeys.DATA_GRID_KEY) ?: return null
    val panel = UIUtil.getParentOfType(DbResultPanel::class.java, grid.panel.component) ?: return null
    val table = panel.table ?: return null
    val dataSource = panel.dataSource ?: return null
    val model = grid.getDataModel(DataAccessType.DATA_WITH_MUTATIONS)
    val row = grid.selectionModel.leadSelectionRow.takeIf { it.isValid(grid) } ?: return null
    val column = grid.selectionModel.leadSelectionColumn.takeIf { it.isValid(grid) } ?: return null
    val names = model.columns.map { it.name }
    val valueOf: (String) -> Any? = { name ->
      val i = names.indexOfFirst { it.equals(name, ignoreCase = true) }
      if (i < 0) DbForeignKeyNavigation.Missing else model.getValueAt(row, ModelIndex.forColumn(model, i))
    }
    return Context(project, dataSource, table, model.getColumn(column)?.name ?: return null, valueOf)
  }

  private fun choose(c: Context, fks: List<DbForeignKey>, text: (DbForeignKey) -> String, noneKey: String, go: (DbForeignKey) -> Unit) {
    when (fks.size) {
      0 -> NotificationGroupManager.getInstance().getNotificationGroup("Phantom Database")
        .createNotification(PhantomDbBundle.message(noneKey, c.column, c.table.name), NotificationType.INFORMATION).notify(c.project)
      1 -> go(fks[0])
      else -> JBPopupFactory.getInstance().createPopupChooserBuilder(fks).setRenderer(com.intellij.ui.SimpleListCellRenderer.create("") { text(it) })
        .setItemChosenCallback(go).createPopup().showCenteredInCurrentWindow(c.project)
    }
  }
}
