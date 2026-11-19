// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.erd

import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.InplaceButton
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import dev.phantom.db.core.PhantomDbBundle
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridLayout
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import javax.swing.DefaultCellEditor
import javax.swing.DefaultListModel
import javax.swing.JPanel
import javax.swing.table.AbstractTableModel

/** What the panel of the selected table asks the editor to do. */
internal interface ErdDetailActions {
  /** Applies [change]; null from it means the edit is refused, and the panel shows the document again. */
  fun edit(change: (ErdDocument) -> ErdDocument?)
  fun close()
  fun deleteTable(name: String)
}

/** The selected table: its names and comment, then columns, indexes and foreign keys in tabs. */
internal class ErdDetailPanel(private val actions: ErdDetailActions) : JPanel(BorderLayout()) {
  private var document = ErdDocument()
  private var tableName: String? = null
  private val table: ErdTable? get() = tableName?.let { document.table(it) }
  private val nameField = field({ it.name }) { name, text -> actions.edit { it.renameTable(name, text) } }
  private val logicalField = field({ it.logicalName }) { name, text -> actions.edit { d -> d.updateTable(name) { it.copy(logicalName = text.trim()) } } }
  private val commentField = field({ it.comment }) { name, text -> actions.edit { d -> d.updateTable(name) { it.copy(comment = text.trim()) } } }
  private val columns = ColumnsModel()
  private val indexes = IndexesModel()
  private val columnTable = JBTable(columns).apply {
    putClientProperty("terminateEditOnFocusLost", true)
    columnModel.getColumn(TYPE).cellEditor = DefaultCellEditor(ComboBox(TYPES).apply { isEditable = true })
  }
  private val indexTable = JBTable(indexes).apply { putClientProperty("terminateEditOnFocusLost", true) }
  private val relationRows = DefaultListModel<String>()
  private val relationList = JBList(relationRows)
  private var relationsShown: List<ErdRelation> = emptyList()
  val closeButton = InplaceButton(PhantomDbBundle.message("db.erd.close.panel"), AllIcons.Actions.Close) { actions.close() }

  init {
    name = "erd.detail"
    add(JPanel(BorderLayout()).apply {
      border = JBUI.Borders.empty(4, 8)
      add(JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply {
        add(InplaceButton(PhantomDbBundle.message("db.erd.delete.table"), AllIcons.Actions.GC) { tableName?.let { actions.deleteTable(it) } })
        add(closeButton)
      }, BorderLayout.NORTH)
      add(JPanel(GridLayout(3, 2, JBUI.scale(6), JBUI.scale(2))).apply {
        add(JBLabel(PhantomDbBundle.message("db.erd.table.name"))); add(nameField)
        add(JBLabel(PhantomDbBundle.message("db.erd.table.logical"))); add(logicalField)
        add(JBLabel(PhantomDbBundle.message("db.erd.table.comment"))); add(commentField)
      }, BorderLayout.CENTER)
    }, BorderLayout.NORTH)
    add(JBTabbedPane().apply {
      addTab(PhantomDbBundle.message("db.erd.tab.columns"), ToolbarDecorator.createDecorator(columnTable)
        .setAddAction { tableName?.let { n -> actions.edit { it.addColumn(n) } } }
        .setRemoveAction { removeSelectedColumn() }
        .setMoveUpAction { moveColumn(-1) }
        .setMoveDownAction { moveColumn(1) }
        .createPanel())
      addTab(PhantomDbBundle.message("db.erd.tab.indexes"), ToolbarDecorator.createDecorator(indexTable)
        .setAddAction { addIndex() }
        .setRemoveAction { removeSelectedIndex() }
        .disableUpDownActions()
        .createPanel())
      addTab(PhantomDbBundle.message("db.erd.tab.relations"), ToolbarDecorator.createDecorator(relationList)
        .disableAddAction()
        .setRemoveAction { removeSelectedRelation() }
        .disableUpDownActions()
        .createPanel())
    }, BorderLayout.CENTER)
  }

  fun display(doc: ErdDocument, name: String?) {
    document = doc
    tableName = name
    val t = table ?: return
    nameField.text = t.name
    logicalField.text = t.logicalName
    commentField.text = t.comment
    val selectedColumn = columnTable.selectedRow
    val selectedIndex = indexTable.selectedRow
    columns.fireTableDataChanged()
    indexes.fireTableDataChanged()
    if (selectedColumn in 0 until columns.rowCount) columnTable.setRowSelectionInterval(selectedColumn, selectedColumn)
    if (selectedIndex in 0 until indexes.rowCount) indexTable.setRowSelectionInterval(selectedIndex, selectedIndex)
    relationsShown = doc.relations.filter { it.table == t.name || it.refTable == t.name }
    relationRows.clear()
    relationsShown.forEach { r ->
      relationRows.addElement("${r.table}(${r.columns.joinToString()}) → ${r.refTable}(${r.refColumns.joinToString()})  ${if (doc.isOneToOne(r)) "1:1" else "1:N"}")
    }
  }

  /** A text field that commits on Enter and on leaving it, when its text differs from [current]. */
  private fun field(current: (ErdTable) -> String, commit: (String, String) -> Unit) = JBTextField().apply {
    fun commitNow() {
      val t = table ?: return
      if (text != current(t)) commit(t.name, text)
    }
    addActionListener { commitNow() }
    addFocusListener(object : FocusAdapter() {
      override fun focusLost(e: FocusEvent) = commitNow()
    })
  }

  private fun removeSelectedColumn() {
    val name = tableName ?: return
    val column = table?.columns?.getOrNull(columnTable.selectedRow)?.name ?: return
    actions.edit { it.removeColumn(name, column) }
  }

  private fun moveColumn(by: Int) {
    val name = tableName ?: return
    val row = columnTable.selectedRow
    val target = row + by
    if (target !in 0 until columns.rowCount || row < 0) return
    actions.edit { d -> d.updateTable(name) { t -> t.copy(columns = t.columns.toMutableList().apply { add(target, removeAt(row)) }) } }
    columnTable.setRowSelectionInterval(target, target)
  }

  private fun addIndex() {
    val name = tableName ?: return
    val first = table?.columns?.firstOrNull()?.name ?: return
    actions.edit { d ->
      d.updateTable(name) { t ->
        val taken = t.indexes.map { it.name }
        t.copy(indexes = t.indexes + ErdIndex(generateSequence(1) { it + 1 }.map { "idx_${name}_$it" }.first { it !in taken }, false, listOf(first)))
      }
    }
  }

  private fun removeSelectedIndex() {
    val name = tableName ?: return
    val row = indexTable.selectedRow
    if (row !in 0 until indexes.rowCount) return
    actions.edit { d -> d.updateTable(name) { t -> t.copy(indexes = t.indexes.filterIndexed { i, _ -> i != row }) } }
  }

  private fun removeSelectedRelation() {
    val relation = relationsShown.getOrNull(relationList.selectedIndex) ?: return
    actions.edit { it.copy(relations = it.relations - relation) }
  }

  private inner class ColumnsModel : AbstractTableModel() {
    private val headers = listOf("name", "logical", "type", "pk", "notnull", "auto", "unique", "default", "comment").map { PhantomDbBundle.message("db.erd.column.$it") }

    override fun getRowCount() = table?.columns?.size ?: 0
    override fun getColumnCount() = headers.size
    override fun getColumnName(column: Int) = headers[column]
    override fun getColumnClass(column: Int): Class<*> = if (column in PK..UNIQUE) java.lang.Boolean::class.java else String::class.java
    override fun isCellEditable(row: Int, column: Int) = column != NOT_NULL || table?.columns?.getOrNull(row)?.primaryKey != true

    override fun getValueAt(row: Int, column: Int): Any? {
      val t = table ?: return null
      val c = t.columns.getOrNull(row) ?: return null
      return when (column) {
        NAME -> c.name
        LOGICAL -> c.logicalName
        TYPE -> c.type
        PK -> c.primaryKey
        NOT_NULL -> c.primaryKey || !c.nullable
        AUTO -> c.autoIncrement
        UNIQUE -> t.isUnique(listOf(c.name))
        DEFAULT -> c.default.orEmpty()
        else -> c.comment
      }
    }

    override fun setValueAt(value: Any?, row: Int, column: Int) {
      val t = tableName ?: return
      val c = table?.columns?.getOrNull(row)?.name ?: return
      if (value == getValueAt(row, column)) return
      val text = (value as? String).orEmpty().trim()
      val on = value == true
      actions.edit { d ->
        when (column) {
          NAME -> d.renameColumn(t, c, text)
          LOGICAL -> d.updateColumn(t, c) { it.copy(logicalName = text) }
          TYPE -> if (text.isEmpty()) null else d.updateColumn(t, c) { it.copy(type = text) }
          PK -> d.updateColumn(t, c) { it.copy(primaryKey = on, nullable = if (on) false else it.nullable) }
          NOT_NULL -> d.updateColumn(t, c) { it.copy(nullable = !on) }
          AUTO -> d.updateColumn(t, c) { it.copy(autoIncrement = on) }
          UNIQUE -> d.setUnique(t, c, on)
          DEFAULT -> d.updateColumn(t, c) { it.copy(default = text.ifEmpty { null }) }
          else -> d.updateColumn(t, c) { it.copy(comment = text) }
        }
      }
    }
  }

  private inner class IndexesModel : AbstractTableModel() {
    private val headers = listOf("name", "unique", "columns").map { PhantomDbBundle.message("db.erd.index.$it") }

    override fun getRowCount() = table?.indexes?.size ?: 0
    override fun getColumnCount() = headers.size
    override fun getColumnName(column: Int) = headers[column]
    override fun getColumnClass(column: Int): Class<*> = if (column == 1) java.lang.Boolean::class.java else String::class.java
    override fun isCellEditable(row: Int, column: Int) = true

    override fun getValueAt(row: Int, column: Int): Any? {
      val index = table?.indexes?.getOrNull(row) ?: return null
      return when (column) {
        0 -> index.name
        1 -> index.unique
        else -> index.columns.joinToString(", ")
      }
    }

    override fun setValueAt(value: Any?, row: Int, column: Int) {
      val t = tableName ?: return
      if (value == getValueAt(row, column)) return
      val text = (value as? String).orEmpty().trim()
      actions.edit { d ->
        val known = d.table(t)?.columns?.map { it.name }.orEmpty()
        val names = text.split(',').map { it.trim() }.filter { it in known }
        if ((column == 0 && text.isEmpty()) || (column == 2 && names.isEmpty())) null
        else d.updateTable(t) { current ->
          current.copy(indexes = current.indexes.mapIndexed { i, index ->
            when {
              i != row -> index
              column == 0 -> index.copy(name = text)
              column == 1 -> index.copy(unique = value == true)
              else -> index.copy(columns = names)
            }
          })
        }
      }
    }
  }

  private companion object {
    const val NAME = 0
    const val LOGICAL = 1
    const val TYPE = 2
    const val PK = 3
    const val NOT_NULL = 4
    const val AUTO = 5
    const val UNIQUE = 6
    const val DEFAULT = 7
    val TYPES = arrayOf("BIGINT", "INT", "SMALLINT", "BOOLEAN", "VARCHAR(255)", "CHAR(1)", "TEXT", "DECIMAL(19,4)", "DATE", "TIMESTAMP", "DATETIME", "JSON")
  }
}
