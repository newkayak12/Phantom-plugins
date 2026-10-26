// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.exec

import com.intellij.database.datagrid.*
import com.intellij.database.datagrid.mutating.CellMutation
import com.intellij.database.datagrid.mutating.MutationData
import com.intellij.ide.util.PropertiesComponent
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
import java.util.Collections
import java.util.IdentityHashMap

/**
 * A result grid that writes back to its table once [target] is known: edited cells become an UPDATE, deleted rows a
 * DELETE, by primary key. As in DataGrip they are shown as changes until Submit (Ctrl+Enter), or run right away with
 * Submit Changes Immediately on. In a manual-commit data source the statements then wait in Database Changes like any
 * other. Until the target is resolved, or when there is none, the grid stays read-only.
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

/** Data editor settings, as in DataGrip's Settings | Database | Data Editor. */
object DbGridSettings {
  private const val IMMEDIATE = "phantom.db.grid.submitImmediately"

  /** Off (DataGrip's default): edits wait in the grid for Submit. */
  var submitImmediately: Boolean
    get() = PropertiesComponent.getInstance().getBoolean(IMMEDIATE, false)
    set(value) = PropertiesComponent.getInstance().setValue(IMMEDIATE, value, false)
}

class DbGridEdits(
  private val project: Project,
  val dataSource: DbDataSource,
  val target: DbEditTarget,
  internal val hookUp: DbEditableHookUp,
) : GridMutator.DatabaseMutator<GridRow, GridColumn> {

  /** Runs the statement; tests replace it to run without the IDE's connection service. */
  internal var run: suspend (DbStatement) -> DbOutcome = { DbExecutor.execute(project, dataSource, it) }

  /** Reads a value back (Set DEFAULT); tests replace it like [run]. */
  internal var read: suspend (DbStatement) -> Any? = { DbExecutor.firstValue(project, dataSource, it) }

  /** Edited cells waiting for Submit: row → column → new value ([DEFAULT] for Set DEFAULT). EDT only. */
  private val changed = IdentityHashMap<GridRow, LinkedHashMap<Int, Any?>>()

  /** Rows marked deleted, waiting for Submit. EDT only. */
  private val deleted: MutableSet<GridRow> = Collections.newSetFromMap(IdentityHashMap())

  /** New rows (Add Row, Clone Row) waiting for Submit; their set cells are in [changed]. EDT only. */
  private val inserted: MutableSet<GridRow> = Collections.newSetFromMap(IdentityHashMap())

  @Volatile
  private var failed = false

  /** The value of a cell set to DEFAULT until it is submitted and read back. */
  object DEFAULT {
    override fun toString(): String = "DEFAULT"
  }

  private val immediate: Boolean get() = DbGridSettings.submitImmediately

  override fun isUpdateSafe(rowIndices: ModelIndexSet<GridRow>, columnIndices: ModelIndexSet<GridColumn>, newValue: Any?): Boolean =
    columnIndices.asIterable().all { target.isEditable(it.value) }

  override fun hasPendingChanges(): Boolean = live(changed.keys).isNotEmpty() || live(deleted).isNotEmpty() || live(inserted).isNotEmpty()

  override fun hasUnparsedValues(): Boolean = false

  override fun hasUnparsedValues(row: ModelIndex<GridRow>): Boolean = false

  override fun isUpdateImmediately(): Boolean = immediate

  override fun isFailed(): Boolean = failed

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
    if (immediate) apply(source, edits.map { Op.Cell(it) }) else keep(source, edits)
  }

  /**
   * Set DEFAULT: `UPDATE … SET column = DEFAULT` for the cells, then the value the database put there is read back by key.
   * A NULL default shows as NULL.
   */
  fun setDefault(source: GridRequestSource, rows: ModelIndexSet<GridRow>, columns: ModelIndexSet<GridColumn>) {
    val edits = rows.asIterable().flatMap { r ->
      val row = hookUp.row(r) ?: return@flatMap emptyList()
      columns.asIterable().filter { target.isEditable(it.value) }.map { c -> Edit(row, c.value, DEFAULT) }.toList()
    }.toList()
    if (immediate) apply(source, edits.map { Op.Cell(it) }) else keep(source, edits)
  }

  /** Records [edits] as changes shown in the grid; an edit back to the loaded value drops the change. */
  private fun keep(source: GridRequestSource, edits: List<Edit>) {
    if (edits.isEmpty()) return
    for (edit in edits) {
      val loaded = edit.row.getValue(edit.column)
      if (edit.row !in inserted && edit.value !== DEFAULT && edit.value == loaded) {
        changed[edit.row]?.let { cells -> cells.remove(edit.column); if (cells.isEmpty()) changed.remove(edit.row) }
      }
      else changed.getOrPut(edit.row) { LinkedHashMap() }[edit.column] = edit.value
    }
    edits.groupBy { it.row }.forEach { (row, cells) -> cellsUpdated(row, cells.map { it.column }, source) }
  }

  override fun deleteRows(source: GridRequestSource, rows: ModelIndexSet<GridRow>) {
    val selected = rows.asIterable().mapNotNull { hookUp.row(it) }.toList()
    if (selected.isEmpty()) return
    // a new row not submitted yet just goes away
    val fresh = selected.filter { it in inserted }
    if (fresh.isNotEmpty()) {
      fresh.forEach(::drop)
      if (fresh.size == selected.size) return
    }
    if (!immediate) {
      deleted.addAll(selected - fresh.toSet())
      selected.forEach { cellsUpdated(it, (0 until hookUp.dataModel.columnCount).toList(), source) }
      return
    }
    val existing = selected - fresh.toSet()
    if (!dataSource.manualCommit) {
      val answer = Messages.showYesNoDialog(project, PhantomDbBundle.message("db.edit.delete.confirm", existing.size, target.displayName),
                                            PhantomDbBundle.message("db.edit.delete.title"), Messages.getWarningIcon())
      if (answer != Messages.YES) return
    }
    apply(source, existing.map { Op.Delete(it) })
  }

  /** Takes a new, unsubmitted row out of the grid. */
  private fun drop(row: GridRow) {
    inserted.remove(row)
    changed.remove(row)
    val index = hookUp.rowIndex(row)
    if (index >= 0) hookUp.updater.removeRows(index, 1)
  }

  /** Submit (Ctrl+Enter): the waiting changes, updates first, then new rows, then deletions; stops at the first that fails. */
  override fun submit(source: GridRequestSource, unused: Boolean) {
    submit(source) {}
  }

  /** As [submit]; [then] gets whether everything was written (Submit and Commit commits only then). */
  fun submit(source: GridRequestSource, then: suspend (Boolean) -> Unit) {
    val ops = updatedRows().flatMap { row -> changed[row]!!.map { (c, v) -> Op.Cell(Edit(row, c, v)) } } +
              live(inserted).map { Op.Insert(it) } + live(deleted).map { Op.Delete(it) }
    apply(source, ops, then)
  }

  /** The statements Submit would run, one per line. */
  override fun getPendingChanges(): String =
    (updatedRows().flatMap { row -> changed[row]!!.map { (c, v) -> statement(Edit(row, c, v)).display } } +
     live(inserted).map { insertStatement(it).display } +
     live(deleted).map { target.delete(GridRow.getValues(it)).display }).joinToString(";\n", postfix = if (hasPendingChanges()) ";" else "")

  /** Loaded rows with edited cells, not deleted. */
  private fun updatedRows(): List<GridRow> = live(changed.keys).filter { it !in deleted && it !in inserted }

  /** A new row's INSERT: the cells set in it, DEFAULT ones left to the database. */
  private fun insertStatement(row: GridRow): DbStatement =
    target.insert(changed[row].orEmpty().filterValues { it !== DEFAULT })

  /** Revert Selected (Ctrl+Alt+Z): drops the changes of the selected cells and undeletes the selected rows. */
  override fun revert(source: GridRequestSource, rows: ModelIndexSet<GridRow>, columns: ModelIndexSet<GridColumn>) {
    val cols = columns.asIterable().map { it.value }.toSet()
    for (index in rows.asIterable().sortedByDescending { it.value }) {
      val row = hookUp.row(index) ?: continue
      if (row in inserted) {
        drop(row)
        continue
      }
      deleted.remove(row)
      changed[row]?.let { cells -> cells.keys.removeAll(cols); if (cells.isEmpty()) changed.remove(row) }
      cellsUpdated(row, (0 until hookUp.dataModel.columnCount).toList(), source)
    }
  }

  override fun getMutationType(row: ModelIndex<GridRow>): MutationType? {
    val r = hookUp.row(row) ?: return null
    return when {
      r in inserted -> MutationType.INSERT
      r in deleted -> MutationType.DELETE
      changed.containsKey(r) -> MutationType.MODIFY
      else -> null
    }
  }

  override fun getMutationType(row: ModelIndex<GridRow>, column: ModelIndex<GridColumn>): MutationType? {
    val r = hookUp.row(row) ?: return null
    return when {
      r in inserted -> MutationType.INSERT
      r in deleted -> MutationType.DELETE
      changed[r]?.containsKey(column.value) == true -> MutationType.MODIFY
      else -> null
    }
  }

  override fun getMutation(row: ModelIndex<GridRow>, column: ModelIndex<GridColumn>): MutationData? {
    val r = hookUp.row(row) ?: return null
    val cells = changed[r] ?: return null
    return if (cells.containsKey(column.value)) MutationData(cells[column.value]) else null
  }

  override fun hasMutatedRows(rows: ModelIndexSet<GridRow>, columns: ModelIndexSet<GridColumn>): Boolean {
    val cols = columns.asIterable().map { it.value }.toSet()
    return rows.asIterable().any { index ->
      val r = hookUp.row(index) ?: return@any false
      r in deleted || r in inserted || changed[r]?.keys?.any { it in cols } == true
    }
  }

  private fun statement(edit: Edit): DbStatement {
    val values = GridRow.getValues(edit.row)
    return if (edit.value === DEFAULT) target.updateToDefault(values, edit.column) else target.update(values, edit.column, edit.value)
  }

  /** Rows of [rows] still in the grid (a reload replaces them), in grid order. */
  private fun live(rows: Collection<GridRow>): List<GridRow> =
    rows.map { it to hookUp.rowIndex(it) }.filter { it.second >= 0 }.sortedBy { it.second }.map { it.first }

  private fun cellsUpdated(row: GridRow, columns: List<Int>, source: GridRequestSource) {
    val index = hookUp.rowIndex(row)
    if (index < 0 || columns.isEmpty()) return
    (hookUp.mutationModel as GridMutationModel).notifyCellsUpdated(
      ModelIndexSet.forRows(hookUp.dataModel, index), ModelIndexSet.forColumns(hookUp.dataModel, *columns.toIntArray()), source.place)
  }

  private sealed interface Op {
    class Cell(val edit: Edit) : Op
    class Delete(val row: GridRow) : Op
    class Insert(val row: GridRow) : Op
  }

  /** Runs each operation's statement in order, stopping at the first that fails, and shows what was written. */
  private fun apply(source: GridRequestSource, ops: List<Op>, then: suspend (Boolean) -> Unit = {}) {
    if (ops.isEmpty()) {
      DbConnectionService.getInstance(project).launch { then(true) }
      return
    }
    hookUp.notifyRequestStarted(source)
    DbConnectionService.getInstance(project).launch {
      var ok = true
      for (op in ops) {
        val error: String?
        var value: Any? = null
        when (op) {
          is Op.Cell -> {
            val outcome = run(statement(op.edit))
            error = problem(outcome)
            value = if (op.edit.value !== DEFAULT) op.edit.value
            else if (error == null) read(target.selectValue(GridRow.getValues(op.edit.row), op.edit.column)) else null
          }
          is Op.Delete -> error = problem(run(target.delete(GridRow.getValues(op.row))))
          is Op.Insert -> error = problem(run(insertStatement(op.row)))
        }
        withContext(Dispatchers.EDT) {
          if (error != null) {
            ok = false
            notifyError(error)
            return@withContext
          }
          when (op) {
            is Op.Cell -> {
              val row = op.edit.row
              changed[row]?.let { cells -> cells.remove(op.edit.column); if (cells.isEmpty()) changed.remove(row) }
              row.setValue(op.edit.column, value)
              cellsUpdated(row, listOf(op.edit.column), source)
            }
            is Op.Insert -> {
              inserted.remove(op.row)
              changed.remove(op.row)?.forEach { (c, v) -> if (v !== DEFAULT) op.row.setValue(c, v) }
              cellsUpdated(op.row, (0 until hookUp.dataModel.columnCount).toList(), source)
            }
            is Op.Delete -> {
              deleted.remove(op.row)
              changed.remove(op.row)
              val index = hookUp.rowIndex(op.row)
              if (index >= 0) hookUp.updater.removeRows(index, 1)
            }
          }
        }
        if (error != null) break
      }
      failed = !ok
      withContext(Dispatchers.EDT) { hookUp.notifyRequestFinished(source, ok) }
      then(ok)
    }
  }

  /**
   * Add Row: empty rows at the end of the grid, filled in place and inserted on Submit (columns left empty take their
   * defaults). With Submit Changes Immediately on, an INSERT to finish opens in a console instead.
   */
  override fun insertRows(source: GridRequestSource, amount: Int) {
    if (immediate) {
      DbConsoles.openConsole(project, dataSource, target.insertTemplate(null))
      return
    }
    addRows(List(amount.coerceAtLeast(1)) { arrayOfNulls<Any?>(hookUp.dataModel.columnCount) }, copied = false)
  }

  /** Clone Row: a new row with the values of [toClone], key included, to change before Submit. */
  override fun cloneRow(source: GridRequestSource, toClone: ModelIndex<GridRow>) {
    val row = hookUp.row(toClone) ?: return
    if (immediate) {
      DbConsoles.openConsole(project, dataSource, target.insertTemplate(GridRow.getValues(row)))
      return
    }
    addRows(listOf(GridRow.getValues(row).copyOf()), copied = true)
  }

  private fun addRows(values: List<Array<Any?>>, copied: Boolean) {
    val start = hookUp.dataModel.rowCount
    val rows = values.mapIndexed { i, v -> com.intellij.database.datagrid.DataConsumer.Row.create(start + i, v) }
    hookUp.updater.addRows(rows)
    for ((i, v) in values.withIndex()) {
      val row = hookUp.row(ModelIndex.forRow(hookUp.dataModel, start + i)) ?: continue
      inserted.add(row)
      if (copied) changed[row] = LinkedHashMap(v.indices.filter { target.isEditable(it) }.associateWith { v[it] })
    }
  }

  override fun isDeletedRow(row: ModelIndex<GridRow>): Boolean = hookUp.row(row)?.let { it in deleted } == true

  override fun isDeletedRows(rows: ModelIndexSet<GridRow>): Boolean = rows.asIterable().toList().let { list -> list.isNotEmpty() && list.all { isDeletedRow(it) } }

  override fun isInsertedRow(row: ModelIndex<GridRow>): Boolean = hookUp.row(row)?.let { it in inserted } == true

  /**
   * The grid counts this many rows past its model's; new rows here are rows of the model itself (so they edit, sort and
   * render like the others), hence none.
   */
  override fun getInsertedRowsCount(): Int = 0

  override fun getLastInsertedRow(): ModelIndex<GridRow> =
    ModelIndex.forRow(hookUp.dataModel, live(inserted).lastOrNull()?.let { hookUp.rowIndex(it) } ?: -1)

  override fun getAffectedRows(): ModelIndexSet<GridRow> =
    ModelIndexSet.forRows(hookUp.dataModel, *(live(changed.keys) + live(deleted) + live(inserted)).map { hookUp.rowIndex(it) }.distinct().sorted().toIntArray())

  override fun getInsertedRows(): JBIterable<ModelIndex<GridRow>> =
    JBIterable.from(live(inserted).map { ModelIndex.forRow(hookUp.dataModel, hookUp.rowIndex(it)) })

  // columns are the table's: not added, removed or renamed from a result
  override fun deleteColumns(source: GridRequestSource, columns: ModelIndexSet<GridColumn>) = Unit

  override fun insertColumn(source: GridRequestSource, name: String?) = Unit

  override fun cloneColumn(source: GridRequestSource, column: ModelIndex<GridColumn>) = Unit

  override fun renameColumn(source: GridRequestSource, column: ModelIndex<GridColumn>, name: String) = Unit

  override fun getInsertedColumnsCount(): Int = 0

  override fun getInsertedColumns(): JBIterable<ModelIndex<GridColumn>> = JBIterable.empty()

  override fun isInsertedColumn(column: ModelIndex<GridColumn>): Boolean = false

  override fun isDeletedColumn(column: ModelIndex<GridColumn>): Boolean = false

  override fun getInsertedColumn(column: ModelIndex<GridColumn>): GridColumn? = null

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
