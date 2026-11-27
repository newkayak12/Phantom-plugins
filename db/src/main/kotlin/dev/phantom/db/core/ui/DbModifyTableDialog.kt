// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.ui

import com.intellij.openapi.application.EDT
import dev.phantom.db.core.dialect
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.Splitter
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import dev.phantom.db.core.DbDataSource
import dev.phantom.db.core.PhantomDbBundle
import dev.phantom.db.core.exec.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Modify Table: the table's columns (name, type, nullable, default, comment) and indexes, edited in place, with the ALTER
 * statements that make the change shown underneath as they are typed. OK runs them on the console connection.
 */
class DbModifyTableDialog(
  private val project: Project,
  private val dataSource: DbDataSource,
  private val table: DbTableRef,
  private val columnsBefore: List<DbAlter.ColumnDef>,
  private val indexesBefore: List<DbAlter.IndexDef>,
) : DialogWrapper(project) {
  private class ColumnRow(val original: String?, var name: String, var type: String, var nullable: Boolean, var default: String,
                          var comment: String, val autoIncrement: Boolean)
  private class IndexRow(val original: String?, var name: String, var columns: String, var unique: Boolean)

  private val columns = ListTableModel<ColumnRow>(
    text("db.modify.column.name", { it.name }) { r, v -> r.name = v },
    text("db.modify.column.type", { it.type }) { r, v -> r.type = v },
    object : ColumnInfo<ColumnRow, Boolean>(PhantomDbBundle.message("db.modify.column.nullable")) {
      override fun valueOf(item: ColumnRow): Boolean = item.nullable
      override fun isCellEditable(item: ColumnRow): Boolean = true
      override fun getColumnClass(): Class<*> = java.lang.Boolean::class.java
      override fun setValue(item: ColumnRow, value: Boolean?) { item.nullable = value == true; changed() }
    },
    text("db.modify.column.default", { it.default }) { r, v -> r.default = v },
    text("db.modify.column.comment", { it.comment }) { r, v -> r.comment = v },
  ).apply { items = columnsBefore.map { ColumnRow(it.original, it.name, it.type, it.nullable, it.default.orEmpty(), it.comment.orEmpty(), it.autoIncrement) } }

  private val indexes = ListTableModel<IndexRow>(
    textIndex("db.modify.index.name", { it.name }) { r, v -> r.name = v },
    textIndex("db.modify.index.columns", { it.columns }) { r, v -> r.columns = v },
    object : ColumnInfo<IndexRow, Boolean>(PhantomDbBundle.message("db.modify.index.unique")) {
      override fun valueOf(item: IndexRow): Boolean = item.unique
      override fun isCellEditable(item: IndexRow): Boolean = true
      override fun getColumnClass(): Class<*> = java.lang.Boolean::class.java
      override fun setValue(item: IndexRow, value: Boolean?) { item.unique = value == true; changed() }
    },
  ).apply { items = indexesBefore.map { IndexRow(it.original, it.name, it.columns.joinToString(", "), it.unique) } }

  private val preview = JBTextArea(6, 80).apply {
    isEditable = false
    font = JBUI.Fonts.create(java.awt.Font.MONOSPACED, font.size)
  }

  init {
    title = PhantomDbBundle.message("db.modify.title", table.name)
    setOKButtonText(PhantomDbBundle.message("db.modify.run"))
    columns.addTableModelListener { changed() }
    indexes.addTableModelListener { changed() }
    init()
    changed()
  }

  fun statements(): List<String> = DbAlter.statements(
    dataSource.dialect(), table, columnsBefore,
    columns.items.filter { it.name.isNotBlank() && it.type.isNotBlank() }.map { DbAlter.ColumnDef(it.original, it.name.trim(), it.type.trim(), it.nullable, it.default.trim().ifEmpty { null },
                                                                                                     it.comment.trim().ifEmpty { null }, it.autoIncrement) },
    indexesBefore,
    indexes.items.filter { it.name.isNotBlank() && it.columns.isNotBlank() }
      .map { DbAlter.IndexDef(it.original, it.name.trim(), it.columns.split(',').map(String::trim).filter(String::isNotEmpty), it.unique) },
  )

  private fun changed() {
    // a database without a dialect refuses changes it has no standard statement for; the preview says which
    val sql = try {
      statements()
    }
    catch (e: DbException) {
      preview.text = e.message
      isOKActionEnabled = false
      return
    }
    preview.text = if (sql.isEmpty()) PhantomDbBundle.message("db.modify.nothing") else sql.joinToString(";\n", postfix = ";")
    isOKActionEnabled = sql.isNotEmpty()
  }

  override fun createCenterPanel(): JComponent {
    val columnTable = TableView(columns).apply { setShowGrid(false); widths(this, 240, 130, 70, 130, 260) }
    val columnPanel = ToolbarDecorator.createDecorator(columnTable)
      .setAddAction {
        columns.addRow(ColumnRow(null, "new_column", "VARCHAR(255)", true, "", "", false))
        columnTable.editCellAt(columns.rowCount - 1, 0)
      }
      .setRemoveAction { columnTable.selectedRows.sortedDescending().forEach { columns.removeRow(it) } }
      .disableUpDownActions()
      .createPanel()
    val indexTable = TableView(indexes).apply { setShowGrid(false); widths(this, 340, 420, 70) }
    val indexPanel = ToolbarDecorator.createDecorator(indexTable)
      .setAddAction { indexes.addRow(IndexRow(null, "idx_${table.name.lowercase()}_${indexes.rowCount + 1}", "", false)) }
      .setRemoveAction { indexTable.selectedRows.sortedDescending().forEach { indexes.removeRow(it) } }
      .disableUpDownActions()
      .createPanel()
    val top = Splitter(true, 0.65f).apply {
      firstComponent = labeled("db.modify.columns", columnPanel)
      secondComponent = labeled("db.modify.indexes", indexPanel)
    }
    return JPanel(BorderLayout(0, JBUI.scale(6))).apply {
      add(top, BorderLayout.CENTER)
      add(labeled("db.modify.preview", JBScrollPane(preview)), BorderLayout.SOUTH)
      preferredSize = JBUI.size(1000, 600)
    }
  }

  /** Long names (overseas_shipment_oms_info_id) need the room; the checkbox columns don't */
  private fun widths(table: TableView<*>, vararg px: Int) {
    for ((i, w) in px.withIndex()) table.columnModel.getColumn(i).preferredWidth = JBUI.scale(w)
  }

  private fun labeled(key: String, component: JComponent) = JPanel(BorderLayout()).apply {
    add(JBLabel(PhantomDbBundle.message(key)).apply { border = JBUI.Borders.empty(2, 0) }, BorderLayout.NORTH)
    add(component, BorderLayout.CENTER)
  }

  private fun text(key: String, get: (ColumnRow) -> String, set: (ColumnRow, String) -> Unit) =
    object : ColumnInfo<ColumnRow, String>(PhantomDbBundle.message(key)) {
      override fun valueOf(item: ColumnRow): String = get(item)
      override fun isCellEditable(item: ColumnRow): Boolean = true
      override fun setValue(item: ColumnRow, value: String?) { set(item, value.orEmpty()); changed() }
    }

  private fun textIndex(key: String, get: (IndexRow) -> String, set: (IndexRow, String) -> Unit) =
    object : ColumnInfo<IndexRow, String>(PhantomDbBundle.message(key)) {
      override fun valueOf(item: IndexRow): String = get(item)
      override fun isCellEditable(item: IndexRow): Boolean = true
      override fun setValue(item: IndexRow, value: String?) { set(item, value.orEmpty()); changed() }
    }

  companion object {
    /** Reads the table's columns and indexes, shows the dialog, runs the statements and calls [afterRun]. */
    fun show(project: Project, dataSource: DbDataSource, table: DbTableRef, afterRun: () -> Unit) {
      DbConnectionService.getInstance(project).launch {
        val objects = DbObjectCache.getInstance(project)
        val loaded = runCatching { objects.columnDetails(dataSource, table) to objects.indexes(dataSource, table) }
        withContext(Dispatchers.EDT) {
          val (details, idx) = loaded.getOrElse {
            Messages.showErrorDialog(project, (it as? java.sql.SQLException)?.let(DbExecutor::message) ?: it.message, PhantomDbBundle.message("db.action.modify.text"))
            return@withContext
          }
          val dialog = DbModifyTableDialog(project, dataSource, table,
                                           details.map { DbAlter.ColumnDef(it.name, it.name, it.typeName, it.nullable, it.default, it.comment, it.autoIncrement) },
                                           idx.map { DbAlter.IndexDef(it.name, it.name, it.columns, it.unique) })
          if (!dialog.showAndGet()) return@withContext
          run(project, dataSource, dialog.statements(), afterRun)
        }
      }
    }

    private fun run(project: Project, dataSource: DbDataSource, statements: List<String>, afterRun: () -> Unit) {
      val console = DbConsoles.consoleFile(dataSource) ?: return
      DbResultsToolWindow.tabForGuarded(project, console, dataSource, statements.first()) { panel -> runIn(project, dataSource, statements, afterRun, panel) }
    }

    private fun runIn(project: Project, dataSource: DbDataSource, statements: List<String>, afterRun: () -> Unit, panel: DbResultPanel) {
      panel.showRunning(SqlSafety.preview(statements.first()) + if (statements.size > 1) " (+${statements.size - 1})" else "")
      panel.job = DbConnectionService.getInstance(project).launch {
        val outcomes = runCatching { DbExecutor.execute(project, dataSource, statements) }
        DbMetadataCache.getInstanceIfCreated(project)?.invalidate(dataSource.id)
        withContext(Dispatchers.EDT) {
          outcomes.fold({ panel.showOutcomes(it) }, { panel.showError((it as? java.sql.SQLException)?.let(DbExecutor::message) ?: it.message.orEmpty()) })
          afterRun()
        }
      }
    }
  }
}
