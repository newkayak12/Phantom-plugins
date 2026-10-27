// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.exec

import com.intellij.database.DatabaseDataKeys
import com.intellij.database.datagrid.DataGrid
import com.intellij.database.datagrid.GridColumn
import com.intellij.database.datagrid.GridRow
import com.intellij.database.datagrid.ModelIndex
import com.intellij.database.datagrid.ModelIndexSet
import com.intellij.database.run.ui.DataGridRequestPlace
import com.intellij.database.datagrid.GridRequestSource
import com.intellij.openapi.actionSystem.ActionPromoter
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.database.run.ui.DataAccessType
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.util.ui.UIUtil
import dev.phantom.db.core.PhantomDbBundle
import dev.phantom.db.core.dialect
import java.awt.datatransfer.StringSelection

/** The selected cells of a result grid. */
object DbGridSelection {
  /** At most this many cells are summed up in the status line. */
  const val AGGREGATE_LIMIT: Int = 200_000

  /** The selected rows × selected columns, in the order the grid shows them; [columns] are the result's columns. */
  fun rows(grid: DataGrid, columns: List<DbColumn>, sql: String): DbOutcome.Rows {
    val (rows, cols) = indices(grid)
    val model = grid.getDataModel(DataAccessType.DATA_WITH_MUTATIONS)
    val values = rows.map { r -> Array(cols.size) { c -> DbUnsetCells.stored(model.getValueAt(r, cols[c])) } }
    return DbOutcome.Rows(sql, cols.map { columns.getOrElse(it.value) { c -> DbColumn("column${c + 1}", java.sql.Types.OTHER, null, null) } }, values, false, 0)
  }

  /** The values of the selected cells, row by row. */
  fun values(grid: DataGrid): List<Any?> {
    val (rows, cols) = indices(grid)
    if (rows.size.toLong() * cols.size > AGGREGATE_LIMIT) return emptyList()
    val model = grid.getDataModel(DataAccessType.DATA_WITH_MUTATIONS)
    return rows.flatMap { r -> cols.map { c -> DbUnsetCells.stored(model.getValueAt(r, c)) } }
  }

  /** The result column indices of the selected columns, in the order the grid shows them. */
  fun columns(grid: DataGrid): List<Int> = indices(grid).second.map { it.value }

  private fun indices(grid: DataGrid): Pair<List<ModelIndex<GridRow>>, List<ModelIndex<GridColumn>>> {
    val selection = grid.selectionModel
    val rows = selection.selectedRows.asIterable().filter { it.isValid(grid) }.sortedBy { it.toView(grid).value }
    val cols = selection.selectedColumns.asIterable().filter { it.isValid(grid) }.sortedBy { it.toView(grid).value }
    return rows to cols
  }
}

/** Copy As in the grid's popup: the selection as TSV, CSV, JSON, SQL INSERTs, a Markdown or an HTML table. */
object DbCopyActions {
  fun group(): ActionGroup = DefaultActionGroup(PhantomDbBundle.message("db.copy.as"), true).apply {
    DbExportFormat.entries.filter { it.text }.forEach { add(copy(it)) }
  }

  private fun copy(format: DbExportFormat): AnAction = object : DumbAwareAction(format.title) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    override fun update(e: AnActionEvent) {
      e.presentation.isEnabledAndVisible = context(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
      val (grid, panel) = context(e) ?: return
      val text = panel.copyText(grid, format) ?: return
      CopyPasteManager.getInstance().setContents(StringSelection(text))
    }
  }

  private fun context(e: AnActionEvent): Pair<DataGrid, DbResultPanel>? {
    val grid = e.getData(DatabaseDataKeys.DATA_GRID_KEY) ?: return null
    val panel = UIUtil.getParentOfType(DbResultPanel::class.java, grid.panel.component) ?: return null
    return grid to panel
  }
}

/** Set NULL (Ctrl+Alt+N) and Set DEFAULT (Ctrl+Alt+D) on the selected cells of an editable result, as in DataGrip. */
abstract class DbGridSetAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

  override fun update(e: AnActionEvent) {
    val grid = e.getData(DatabaseDataKeys.DATA_GRID_KEY)
    val edits = grid?.let { edits(it) }
    e.presentation.isVisible = grid != null
    e.presentation.isEnabled = edits != null && grid.selectionModel.selectedColumns.asIterable().any { edits.target.isEditable(it.value) }
  }

  override fun actionPerformed(e: AnActionEvent) {
    val grid = e.getData(DatabaseDataKeys.DATA_GRID_KEY) ?: return
    val edits = edits(grid) ?: return
    val selection = grid.selectionModel
    apply(edits, GridRequestSource(DataGridRequestPlace(grid)), selection.selectedRows, selection.selectedColumns)
  }

  protected abstract fun apply(edits: DbGridEdits, source: GridRequestSource, rows: ModelIndexSet<GridRow>, columns: ModelIndexSet<GridColumn>)

  companion object {
    fun edits(grid: DataGrid): DbGridEdits? = (grid.dataHookup as? DbEditableHookUp)?.editing
  }
}

class DbGridSetNullAction : DbGridSetAction() {
  override fun apply(edits: DbGridEdits, source: GridRequestSource, rows: ModelIndexSet<GridRow>, columns: ModelIndexSet<GridColumn>) =
    edits.mutate(source, rows, columns, null, true)
}

class DbGridSetDefaultAction : DbGridSetAction() {
  override fun apply(edits: DbGridEdits, source: GridRequestSource, rows: ModelIndexSet<GridRow>, columns: ModelIndexSet<GridColumn>) =
    edits.setDefault(source, rows, columns)
}

/** A grid command of DataGrip's, on the grid of the data context. */
abstract class DbGridAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

  override fun update(e: AnActionEvent) {
    val grid = e.getData(DatabaseDataKeys.DATA_GRID_KEY)
    e.presentation.isVisible = grid != null
    e.presentation.isEnabled = grid != null && isEnabled(grid)
  }

  override fun actionPerformed(e: AnActionEvent) {
    perform(e.getData(DatabaseDataKeys.DATA_GRID_KEY) ?: return)
  }

  protected open fun isEnabled(grid: DataGrid): Boolean = true

  protected abstract fun perform(grid: DataGrid)

  protected fun edits(grid: DataGrid): DbGridEdits? = DbGridSetAction.edits(grid)
}

/** Submit (Ctrl+Enter): runs the grid's waiting changes. */
class DbGridSubmitAction : DbGridAction() {
  override fun isEnabled(grid: DataGrid): Boolean = edits(grid)?.hasPendingChanges() == true

  override fun perform(grid: DataGrid) {
    edits(grid)?.submit(GridRequestSource(DataGridRequestPlace(grid))) {}
  }
}

/** Submit and Commit (Ctrl+Alt+Shift+Enter): the waiting changes, then the data source's transaction when it is manual. */
class DbGridSubmitAndCommitAction : DbGridAction() {
  override fun isEnabled(grid: DataGrid): Boolean {
    val edits = edits(grid) ?: return false
    return edits.hasPendingChanges() || edits.dataSource.manualCommit
  }

  override fun perform(grid: DataGrid) {
    val edits = edits(grid) ?: return
    val project = grid.project
    edits.submit(GridRequestSource(DataGridRequestPlace(grid))) { ok ->
      if (ok && edits.dataSource.manualCommit) DbEndTransactionAction.end(project, edits.dataSource, commit = true)
    }
  }
}

/** Preview Pending Changes: the statements Submit would run, in a popup to read or copy. */
class DbGridPreviewChangesAction : DbGridAction() {
  override fun isEnabled(grid: DataGrid): Boolean = edits(grid)?.hasPendingChanges() == true

  override fun perform(grid: DataGrid) {
    val text = edits(grid)?.pendingChanges ?: return
    val area = com.intellij.ui.components.JBTextArea(text).apply {
      isEditable = false
      font = com.intellij.util.ui.JBUI.Fonts.create(java.awt.Font.MONOSPACED, font.size)
      border = com.intellij.util.ui.JBUI.Borders.empty(6, 8)
    }
    com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
      .createComponentPopupBuilder(com.intellij.ui.components.JBScrollPane(area).apply { preferredSize = com.intellij.util.ui.JBUI.size(560, 220) }, area)
      .setTitle(PhantomDbBundle.message("action.PhantomDb.Grid.PreviewChanges.text"))
      .setResizable(true).setMovable(true).setRequestFocus(true)
      .createPopup().showInCenterOf(grid.panel.component)
  }
}

/** Submit Changes Immediately: each edit runs as it is made instead of waiting for Submit. */
class DbGridSubmitImmediatelyAction : com.intellij.openapi.project.DumbAwareToggleAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

  override fun update(e: AnActionEvent) {
    super.update(e)
    e.presentation.isEnabledAndVisible = e.getData(DatabaseDataKeys.DATA_GRID_KEY) != null
  }

  override fun isSelected(e: AnActionEvent): Boolean = DbGridSettings.submitImmediately

  override fun setSelected(e: AnActionEvent, state: Boolean) {
    // what waits is written first, so nothing is left behind unseen
    if (state) e.getData(DatabaseDataKeys.DATA_GRID_KEY)?.let { grid ->
      DbGridSetAction.edits(grid)?.takeIf { it.hasPendingChanges() }?.submit(GridRequestSource(DataGridRequestPlace(grid))) {}
    }
    DbGridSettings.submitImmediately = state
  }
}

/**
 * Column Statistics: for the selected cell's column, over the rows the grid has: NULLs, distinct values, sum, average,
 * minimum and maximum of numbers (else minimum and maximum of comparable values) and the most frequent values.
 */
class DbGridColumnStatisticsAction : DbGridAction() {
  override fun isEnabled(grid: DataGrid): Boolean = grid.selectionModel.selectedColumns.asIterable().any { it.isValid(grid) }

  override fun perform(grid: DataGrid) {
    val column = grid.selectionModel.selectedColumns.asIterable().firstOrNull { it.isValid(grid) } ?: return
    val model = grid.getDataModel(DataAccessType.DATA_WITH_MUTATIONS)
    val values = (0 until model.rowCount).map { DbUnsetCells.stored(model.getValueAt(ModelIndex.forRow(grid, it), column)) }
    val text = DbColumnStatistics.text(model.getColumn(column)?.name.orEmpty(), values)
    val area = com.intellij.ui.components.JBTextArea(text).apply {
      isEditable = false
      font = com.intellij.util.ui.JBUI.Fonts.create(java.awt.Font.MONOSPACED, font.size)
      border = com.intellij.util.ui.JBUI.Borders.empty(6, 8)
    }
    com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
      .createComponentPopupBuilder(com.intellij.ui.components.JBScrollPane(area).apply { preferredSize = com.intellij.util.ui.JBUI.size(460, 300) }, area)
      .setTitle(PhantomDbBundle.message("action.PhantomDb.Grid.ColumnStatistics.text"))
      .setResizable(true).setMovable(true).setRequestFocus(true)
      .createPopup().showInCenterOf(grid.panel.component)
  }
}

object DbColumnStatistics {
  /** The most frequent values listed. */
  const val TOP: Int = 10

  fun text(column: String, values: List<Any?>): String = buildString {
    val n = values.size
    fun pct(k: Int) = if (n == 0) "" else " (%.1f%%)".format(java.util.Locale.ROOT, k * 100.0 / n)
    fun shown(v: Any?) = when (v) {
      null -> "NULL"
      is ByteArray -> "${v.size} bytes"
      else -> v.toString().replace("\r\n", " ").replace('\n', ' ').let { if (it.length > 60) it.take(57) + "…" else it }
    }
    val nulls = values.count { it == null }
    val present = values.filterNotNull()
    val aggregates = DbAggregates.of(values)
    append(column).append('\n')
    append("rows      ").append(n).append('\n')
    append("NULL      ").append(nulls).append(pct(nulls)).append('\n')
    append("distinct  ").append(aggregates.distinct).append('\n')
    if (aggregates.numeric) {
      append("sum       ").append(aggregates.sum!!.stripTrailingZeros().toPlainString()).append('\n')
      append("avg       ").append(aggregates.avg!!.stripTrailingZeros().toPlainString()).append('\n')
      append("min       ").append(aggregates.min!!.stripTrailingZeros().toPlainString()).append('\n')
      append("max       ").append(aggregates.max!!.stripTrailingZeros().toPlainString()).append('\n')
    }
    else if (present.isNotEmpty() && present.all { it is Comparable<*> && it.javaClass == present[0].javaClass }) {
      @Suppress("UNCHECKED_CAST") val comparable = present as List<Comparable<Any>>
      append("min       ").append(shown(comparable.min())).append('\n')
      append("max       ").append(shown(comparable.max())).append('\n')
    }
    val top = values.groupingBy { if (it is ByteArray) it.toList() else it }.eachCount().entries.sortedByDescending { it.value }.take(TOP)
    if (top.isNotEmpty()) {
      append("\nmost frequent\n")
      val width = top.maxOf { shown(it.key).length }
      top.forEach { (v, k) -> append("  ").append(shown(v).padEnd(width)).append("  ").append(k).append(pct(k)).append('\n') }
    }
  }
}

/** Select Row (Shift+Space) and Select Column (Ctrl+Space), as in DataGrip. */
class DbGridSelectRowAction : DbGridAction() {
  override fun perform(grid: DataGrid) = grid.selectionModel.selectWholeRow()
}

class DbGridSelectColumnAction : DbGridAction() {
  override fun perform(grid: DataGrid) = grid.selectionModel.selectWholeColumn()
}

/** Edit Filter Criteria (Ctrl+Alt+Shift+F) and Edit Ordering: the caret into the WHERE or ORDER BY field over the grid. */
abstract class DbGridFilterAction(private val order: Boolean) : DbGridAction() {
  override fun isEnabled(grid: DataGrid): Boolean = bar(grid) != null

  override fun perform(grid: DataGrid) {
    val bar = bar(grid) ?: return
    if (order) bar.focusOrder() else bar.focusWhere()
  }

  companion object {
    /** The WHERE / ORDER BY fields over [grid]: a table's data view, or a select from one table. */
    fun bar(grid: DataGrid): dev.phantom.db.core.ui.DbFilterBar? =
      UIUtil.getParentOfType(dev.phantom.db.core.ui.DbTableDataView::class.java, grid.panel.component)?.filter
      ?: UIUtil.getParentOfType(DbResultPanel::class.java, grid.panel.component)?.filterBar
  }
}

/**
 * Filter by (DataGrip's): conditions on the selected cells' column made from their values, `= v`, `<> v`, `LIKE '%v%'`
 * for text, `IN (…)` for several values, `IS NULL` for NULL. The chosen one is added to WHERE (with AND) and run.
 */
object DbFilterByValue {
  /** At most this many distinct values go into an IN list. */
  private const val MAX_VALUES = 100

  fun group(): ActionGroup = object : ActionGroup(PhantomDbBundle.message("db.grid.filter.by"), true) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
      e.presentation.isEnabledAndVisible = e.getData(DatabaseDataKeys.DATA_GRID_KEY)?.let { conditions(it).isNotEmpty() } == true
    }

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
      val grid = e?.getData(DatabaseDataKeys.DATA_GRID_KEY) ?: return emptyArray()
      return conditions(grid).map { condition ->
        object : DumbAwareAction(condition.replace("_", "__")) {
          override fun actionPerformed(e: AnActionEvent) = apply(grid, condition)
        }
      }.toTypedArray()
    }
  }

  /** The conditions offered for [grid]'s selection: on the first selected column, from its selected cells. */
  fun conditions(grid: DataGrid): List<String> {
    if (DbGridFilterAction.bar(grid) == null) return emptyList()
    val panel = UIUtil.getParentOfType(DbResultPanel::class.java, grid.panel.component) ?: return emptyList()
    val dbms = panel.dataSource?.dialect() ?: return emptyList()
    val column = grid.selectionModel.selectedColumns.asIterable().firstOrNull { it.isValid(grid) } ?: return emptyList()
    val model = grid.getDataModel(DataAccessType.DATA_WITH_MUTATIONS)
    val name = model.getColumn(column)?.name ?: return emptyList()
    val values = grid.selectionModel.selectedRows.asIterable().filter { it.isValid(grid) }.take(MAX_VALUES + 1)
      .map { DbUnsetCells.stored(model.getValueAt(it, column)) }.distinct()
    if (values.isEmpty() || values.size > MAX_VALUES || values.any { it is ByteArray }) return emptyList()
    val c = DbNames.identifier(dbms, name)
    val value = values.singleOrNull()
    return when {
      values.size == 1 && value == null -> listOf("$c IS NULL", "$c IS NOT NULL")
      values.size == 1 -> {
        val literal = DbStatement.literal(value, dbms)
        listOf("$c = $literal", "$c <> $literal") +
        if (value is String) {
          val like = DbStatement.literal("%$value%", dbms)
          listOf("$c LIKE $like", "$c NOT LIKE $like")
        }
        else emptyList()
      }
      else -> {
        val list = values.filterNotNull().joinToString(", ") { DbStatement.literal(it, dbms) }
        val nulls = values.any { it == null }
        listOf("$c IN ($list)" + (if (nulls) " OR $c IS NULL" else ""), "$c NOT IN ($list)" + (if (nulls) " AND $c IS NOT NULL" else ""))
      }
    }
  }

  /** [condition] added to the WHERE field (with AND, the old filter in parentheses), then the filter is run. */
  fun apply(grid: DataGrid, condition: String) {
    val bar = DbGridFilterAction.bar(grid) ?: return
    val old = bar.where.trim()
    bar.where = if (old.isEmpty()) condition else "($old) AND ($condition)"
    bar.apply()
  }
}

class DbGridEditFilterAction : DbGridFilterAction(order = false)

class DbGridEditOrderingAction : DbGridFilterAction(order = true)

/** In a result grid its commands win over IDE actions on the same keys (Inline, Run, Code Completion, Commit). */
class DbGridActionPromoter : ActionPromoter {
  override fun promote(actions: List<AnAction>, context: DataContext): List<AnAction> {
    if (context.getData(DatabaseDataKeys.DATA_GRID_KEY) == null) return emptyList()
    return actions.filter { it is DbGridSetAction || it is DbGridAction }
  }
}
