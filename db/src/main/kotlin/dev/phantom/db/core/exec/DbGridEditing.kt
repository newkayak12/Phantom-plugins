// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.exec

import com.intellij.database.datagrid.*
import com.intellij.database.datagrid.mutating.CellMutation
import com.intellij.database.run.ui.grid.GridMutationModel
import com.intellij.database.run.ui.grid.GridStorageAndModelUpdater
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.util.containers.JBIterable
import dev.phantom.db.core.DbDataSource
import dev.phantom.db.core.PhantomDbBundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A result grid that writes back to its table once [target] is known: edited cells run an UPDATE, deleted rows a
 * DELETE, each right away by primary key. In a manual-commit data source they wait in Database Changes like any
 * other statement. Until the target is resolved, or when there is none, the grid stays read-only.
 */
class DbEditableHookUp(project: Project, private val model: DataGridListModel) : CachedGridDataHookUp(project, model) {
  @Volatile
  var editing: DbGridEdits? = null

  lateinit var updater: GridStorageAndModelUpdater

  fun createUpdater(): GridStorageAndModelUpdater =
    GridStorageAndModelUpdater(model, mutationModel as GridMutationModel, null).also { updater = it }

  override fun getMutator(): GridMutator<GridRow, GridColumn>? = editing

  override fun isReadOnly(): Boolean = editing == null

  internal fun row(index: ModelIndex<GridRow>): GridRow? = model.getRow(index)

  internal fun rowIndex(row: GridRow): Int = model.rows.indexOf(row)
}

class DbGridEdits(
  private val project: Project,
  private val dataSource: DbDataSource,
  val target: DbEditTarget,
  internal val hookUp: DbEditableHookUp,
) : GridMutator.RowsMutator<GridRow, GridColumn> {

  /** Runs the statement; tests replace it to run without the IDE's connection service. */
  internal var run: suspend (DbStatement) -> DbOutcome = { DbExecutor.execute(project, dataSource, it) }

  /** Reads a value back (Set DEFAULT); tests replace it like [run]. */
  internal var read: suspend (DbStatement) -> Any? = { DbExecutor.firstValue(project, dataSource, it) }

  override fun isUpdateSafe(rowIndices: ModelIndexSet<GridRow>, columnIndices: ModelIndexSet<GridColumn>, newValue: Any?): Boolean =
    columnIndices.asIterable().all { target.isEditable(it.value) }

  override fun hasPendingChanges(): Boolean = false

  override fun hasUnparsedValues(): Boolean = false

  override fun isUpdateImmediately(): Boolean = true

  override fun mutate(source: GridRequestSource, row: ModelIndexSet<GridRow>, column: ModelIndexSet<GridColumn>, newValue: Any?,
                      allowImmediateUpdate: Boolean) {
    val cells = row.asIterable().flatMap { r -> column.asIterable().map { c -> CellMutation(r, c, newValue) } }.toList()
    mutate(source, cells, allowImmediateUpdate)
  }

  override fun mutate(source: GridRequestSource, mutations: List<CellMutation>, allowImmediateUpdate: Boolean) {
    val edits = mutations.mapNotNull { m ->
      val row = hookUp.row(m.row) ?: return@mapNotNull null
      if (!target.isEditable(m.column.value)) return@mapNotNull null
      Edit(row, m.column.value, m.value)
    }
    apply(source, edits) { edit -> run(target.update(GridRow.getValues(edit.row), edit.column, edit.value)) to edit.value }
  }

  /**
   * Set DEFAULT: `UPDATE … SET column = DEFAULT` for the cells, then the value the database put there is read back by key.
   * A NULL default shows as NULL.
   */
  fun setDefault(source: GridRequestSource, rows: ModelIndexSet<GridRow>, columns: ModelIndexSet<GridColumn>) {
    val edits = rows.asIterable().flatMap { r ->
      val row = hookUp.row(r) ?: return@flatMap emptyList()
      columns.asIterable().filter { target.isEditable(it.value) }.map { c -> Edit(row, c.value, null) }.toList()
    }.toList()
    apply(source, edits) { edit ->
      val values = GridRow.getValues(edit.row)
      val outcome = run(target.updateToDefault(values, edit.column))
      outcome to if (outcome is DbOutcome.Updated && outcome.count == 1L) read(target.selectValue(values, edit.column)) else null
    }
  }

  /** Runs each edit's statement in order, stopping at the first that fails; [statement] gives its outcome and the cell's new value. */
  private fun apply(source: GridRequestSource, edits: List<Edit>, statement: suspend (Edit) -> Pair<DbOutcome, Any?>) {
    if (edits.isEmpty()) return
    hookUp.notifyRequestStarted(source)
    DbConnectionService.getInstance(project).launch {
      var ok = true
      for (edit in edits) {
        val (outcome, value) = statement(edit)
        val error = problem(outcome)
        withContext(Dispatchers.EDT) {
          if (error == null) {
            edit.row.setValue(edit.column, value)
            val index = ModelIndex.forRow(hookUp.dataModel, hookUp.rowIndex(edit.row))
            (hookUp.mutationModel as GridMutationModel).notifyCellsUpdated(
              ModelIndexSet.forRows(hookUp.dataModel, index.value), ModelIndexSet.forColumns(hookUp.dataModel, edit.column), source.place)
          }
          else {
            ok = false
            notifyError(error)
          }
        }
        if (error != null) break
      }
      withContext(Dispatchers.EDT) { hookUp.notifyRequestFinished(source, ok) }
    }
  }

  override fun deleteRows(source: GridRequestSource, rows: ModelIndexSet<GridRow>) {
    val selected = rows.asIterable().mapNotNull { hookUp.row(it) }.toList()
    if (selected.isEmpty()) return
    if (!dataSource.manualCommit) {
      val answer = Messages.showYesNoDialog(project, PhantomDbBundle.message("db.edit.delete.confirm", selected.size, target.displayName),
                                            PhantomDbBundle.message("db.edit.delete.title"), Messages.getWarningIcon())
      if (answer != Messages.YES) return
    }
    hookUp.notifyRequestStarted(source)
    DbConnectionService.getInstance(project).launch {
      var ok = true
      for (row in selected) {
        val error = problem(run(target.delete(GridRow.getValues(row))))
        withContext(Dispatchers.EDT) {
          if (error == null) {
            val index = hookUp.rowIndex(row)
            if (index >= 0) hookUp.updater.removeRows(index, 1)
          }
          else {
            ok = false
            notifyError(error)
          }
        }
        if (error != null) break
      }
      withContext(Dispatchers.EDT) { hookUp.notifyRequestFinished(source, ok) }
    }
  }

  /** New rows are written in a console, where the values and defaults can be filled in before running. */
  override fun insertRows(source: GridRequestSource, amount: Int) {
    DbConsoles.openConsole(project, dataSource, target.insertTemplate(null))
  }

  override fun cloneRow(source: GridRequestSource, toClone: ModelIndex<GridRow>) {
    val row = hookUp.row(toClone) ?: return
    DbConsoles.openConsole(project, dataSource, target.insertTemplate(GridRow.getValues(row)))
  }

  override fun isDeletedRow(row: ModelIndex<GridRow>): Boolean = false

  override fun isDeletedRows(rows: ModelIndexSet<GridRow>): Boolean = false

  override fun isInsertedRow(row: ModelIndex<GridRow>): Boolean = false

  override fun getInsertedRowsCount(): Int = 0

  override fun getLastInsertedRow(): ModelIndex<GridRow> = ModelIndex.forRow(hookUp.dataModel, -1)

  override fun getAffectedRows(): ModelIndexSet<GridRow> = ModelIndexSet.forRows(hookUp.dataModel, *IntArray(0))

  override fun getInsertedRows(): JBIterable<ModelIndex<GridRow>> = JBIterable.empty()

  /** Null when the statement changed exactly one row. */
  private fun problem(outcome: DbOutcome): String? = when (outcome) {
    is DbOutcome.Failed -> outcome.message
    is DbOutcome.Updated -> when (outcome.count) {
      1L -> null
      0L -> PhantomDbBundle.message("db.edit.row.gone", target.displayName)
      else -> PhantomDbBundle.message("db.edit.many.rows", outcome.count, target.displayName)
    }
    is DbOutcome.Rows -> null
  }

  private fun notifyError(message: String) {
    NotificationGroupManager.getInstance().getNotificationGroup("Phantom Database")
      .createNotification(PhantomDbBundle.message("db.edit.failed", target.displayName), message, NotificationType.ERROR)
      .notify(project)
  }

  private class Edit(val row: GridRow, val column: Int, val value: Any?)
}
