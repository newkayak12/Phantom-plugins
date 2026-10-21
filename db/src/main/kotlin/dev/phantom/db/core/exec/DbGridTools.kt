// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.exec

import com.intellij.database.DatabaseDataKeys
import com.intellij.database.datagrid.DataGrid
import com.intellij.database.datagrid.GridColumn
import com.intellij.database.datagrid.GridRow
import com.intellij.database.datagrid.ModelIndex
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
import java.awt.datatransfer.StringSelection

/** The selected cells of a result grid. */
object DbGridSelection {
  /** At most this many cells are summed up in the status line. */
  const val AGGREGATE_LIMIT: Int = 200_000

  /** The selected rows × selected columns, in the order the grid shows them; [columns] are the result's columns. */
  fun rows(grid: DataGrid, columns: List<DbColumn>, sql: String): DbOutcome.Rows {
    val (rows, cols) = indices(grid)
    val model = grid.getDataModel(DataAccessType.DATA_WITH_MUTATIONS)
    val values = rows.map { r -> Array(cols.size) { c -> model.getValueAt(r, cols[c]) } }
    return DbOutcome.Rows(sql, cols.map { columns.getOrElse(it.value) { c -> DbColumn("column${c + 1}", java.sql.Types.OTHER, null, null) } }, values, false, 0)
  }

  /** The values of the selected cells, row by row. */
  fun values(grid: DataGrid): List<Any?> {
    val (rows, cols) = indices(grid)
    if (rows.size.toLong() * cols.size > AGGREGATE_LIMIT) return emptyList()
    val model = grid.getDataModel(DataAccessType.DATA_WITH_MUTATIONS)
    return rows.flatMap { r -> cols.map { c -> model.getValueAt(r, c) } }
  }

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
    DbExportFormat.entries.forEach { add(copy(it)) }
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
