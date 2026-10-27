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

  private fun bar(grid: DataGrid): dev.phantom.db.core.ui.DbFilterBar? =
    UIUtil.getParentOfType(dev.phantom.db.core.ui.DbTableDataView::class.java, grid.panel.component)?.filter
    ?: UIUtil.getParentOfType(DbResultPanel::class.java, grid.panel.component)?.filterBar
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
