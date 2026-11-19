// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.exec

import com.intellij.database.datagrid.*
import com.intellij.database.run.ui.DataAccessType
import com.intellij.database.run.ui.DataGridRequestPlace
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.openapi.project.Project
import com.intellij.ui.EditorTextField
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.phantom.db.core.PhantomDbBundle
import org.jetbrains.annotations.TestOnly
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.FlowLayout
import java.nio.file.Files
import javax.imageio.ImageIO
import javax.swing.ImageIcon
import javax.swing.JPanel

/**
 * The selected cell of a grid at full size, next to the grid: text with optional wrapping, JSON pretty-printed and
 * XML highlighted (when those languages are installed), bytes as a hex dump that can be saved to a file, PNG and JPEG
 * as a picture. In an editable grid a text cell can be changed here and applied like an edit in the grid.
 */
class DbValueViewer(private val project: Project, private val grid: DataGrid, private val hookUp: DbEditableHookUp) : JPanel(BorderLayout()), Disposable {
  private val kind = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
  private val wrap = JBCheckBox(PhantomDbBundle.message("db.value.wrap"), true).apply { addActionListener { applyWrap() } }
  private val save = ActionLink(PhantomDbBundle.message("db.value.save")) { saveBytes() }
  private val applyLink = ActionLink(PhantomDbBundle.message("db.value.apply")) { applyEdit() }
  private val text = EditorTextField("", project, PlainTextFileType.INSTANCE).apply {
    setOneLineMode(false)
    addSettingsProvider { e ->
      e.settings.isUseSoftWraps = wrap.isSelected
      e.settings.isLineNumbersShown = true
      e.setVerticalScrollbarVisible(true)
      e.setHorizontalScrollbarVisible(true)
    }
  }
  private val image = JBLabel()
  private val cards = CardLayout()
  private val center = JPanel(cards).apply {
    add(text, TEXT)
    add(JBScrollPane(image), IMAGE)
  }

  private var cell: Pair<ModelIndex<GridRow>, ModelIndex<GridColumn>>? = null
  private var bytes: ByteArray? = null
  private var shownText = ""

  @get:TestOnly
  var shown: DbCellView? = null
    private set

  init {
    border = JBUI.Borders.customLineLeft(JBUI.CurrentTheme.ToolWindow.borderColor())
    add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(2))).apply {
      add(kind)
      add(wrap)
      add(save)
      add(applyLink)
    }, BorderLayout.NORTH)
    add(center, BorderLayout.CENTER)
    // selection changes come from the result panel, which owns the one listener on the grid
    grid.addDataGridListener(object : DataGridListener {
      override fun onContentChanged(dataGrid: DataGrid?, place: GridRequestSource.RequestPlace?) = update()
    }, this)
    update()
  }

  /** Shows the cell at the lead of the grid's selection. */
  fun update() {
    val model = grid.getDataModel(DataAccessType.DATA_WITH_MUTATIONS)
    val row = grid.selectionModel.leadSelectionRow
    val column = grid.selectionModel.leadSelectionColumn
    if (!row.isValid(grid) || !column.isValid(grid)) {
      cell = null
      show(DbCellView.of(null), null)
      kind.text = PhantomDbBundle.message("db.value.none")
      return
    }
    // an edit not applied yet stays while the same cell is selected
    if (cell == (row to column) && text.text != shownText) return
    cell = row to column
    val value = DbUnsetCells.stored(model.getValueAt(row, column))
    show(DbCellView.of(value), value as? ByteArray)
  }

  private fun show(view: DbCellView, value: ByteArray?) {
    shown = view
    bytes = value
    shownText = view.text
    kind.text = when (view.kind) {
      DbCellView.Kind.NULL -> "NULL"
      DbCellView.Kind.TEXT -> PhantomDbBundle.message("db.value.text")
      DbCellView.Kind.JSON -> "JSON"
      DbCellView.Kind.XML -> "XML"
      DbCellView.Kind.BINARY, DbCellView.Kind.IMAGE -> PhantomDbBundle.message("db.value.bytes", value?.size ?: 0)
    }
    save.isVisible = value != null
    val editable = editable(view)
    applyLink.isVisible = editable
    val type = when (view.kind) {
      DbCellView.Kind.JSON -> fileType("json")
      DbCellView.Kind.XML -> fileType("xml")
      else -> PlainTextFileType.INSTANCE
    }
    text.setNewDocumentAndFileType(type, EditorFactory.getInstance().createDocument(view.text))
    text.isViewer = !editable
    applyWrap()
    val picture = if (view.kind == DbCellView.Kind.IMAGE && value != null) runCatching { ImageIO.read(value.inputStream()) }.getOrNull() else null
    if (picture != null) {
      image.icon = ImageIcon(picture)
      cards.show(center, IMAGE)
    }
    else {
      image.icon = null
      cards.show(center, TEXT)
    }
  }

  /**
   * A cell of a character column (the text is written as it is, so numbers, dates and others are edited in the grid,
   * which converts them), in a grid whose table is known.
   */
  private fun editable(view: DbCellView): Boolean {
    val column = cell?.second ?: return false
    if (view.kind == DbCellView.Kind.BINARY || view.kind == DbCellView.Kind.IMAGE) return false
    val target = hookUp.editing?.target ?: return false
    return target.isEditable(column.value) && target.sqlTypes.getOrNull(column.value) in CHARACTER_TYPES
  }

  /** Writes the changed text to the cell through the grid's edit (an UPDATE by primary key). */
  @TestOnly
  fun applyText(newText: String) {
    text.text = newText
    applyEdit()
  }

  private fun applyEdit() {
    val (row, column) = cell ?: return
    val edits = hookUp.editing ?: return
    val newText = text.text
    if (newText == shownText) return
    val model = hookUp.dataModel
    // the edit is the shown text from now on: a refresh of the same cell may replace it
    shownText = newText
    edits.mutate(GridRequestSource(DataGridRequestPlace(grid)), ModelIndexSet.forRows(model, row.value), ModelIndexSet.forColumns(model, column.value),
                 newText, true)
  }

  private fun applyWrap() {
    text.editor?.settings?.isUseSoftWraps = wrap.isSelected
  }

  private fun saveBytes() {
    val data = bytes ?: return
    val ext = if (DbCellView.isImage(data)) (if (data[0] == 0x89.toByte()) "png" else "jpg") else "bin"
    val descriptor = FileSaverDescriptor(PhantomDbBundle.message("db.value.save.title"), "", ext)
    val target = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project).save("value.$ext") ?: return
    Files.write(target.file.toPath(), data)
  }

  private fun fileType(extension: String): FileType =
    FileTypeManager.getInstance().getFileTypeByExtension(extension).takeIf { it !is UnknownFileType } ?: PlainTextFileType.INSTANCE

  override fun dispose() {}

  private companion object {
    val CHARACTER_TYPES = setOf(java.sql.Types.CHAR, java.sql.Types.VARCHAR, java.sql.Types.LONGVARCHAR, java.sql.Types.NCHAR,
                                java.sql.Types.NVARCHAR, java.sql.Types.LONGNVARCHAR, java.sql.Types.CLOB, java.sql.Types.NCLOB)
    const val TEXT = "text"
    const val IMAGE = "image"
  }
}
