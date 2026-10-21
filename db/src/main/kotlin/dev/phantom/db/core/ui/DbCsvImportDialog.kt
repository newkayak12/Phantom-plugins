// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.ui

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.AlignY
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import dev.phantom.db.core.DbDataSource
import dev.phantom.db.core.PhantomDbBundle
import dev.phantom.db.core.exec.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.table.DefaultTableModel

/**
 * Import Data from CSV: the format is guessed from the file's start and can be changed, the first [PREVIEW] records are
 * shown, and each table column is filled from a CSV column (by header name, else position) or left out.
 */
class DbCsvImportDialog internal constructor(
  private val project: Project,
  private val table: DbTableRef,
  private val file: Path,
  private val sample: String,
  private val columns: List<String>,
) : DialogWrapper(project) {
  internal val delimiterCombo = ComboBox(arrayOf(',', ';', '\t', '|')).apply {
    renderer = com.intellij.ui.dsl.listCellRenderer.textListCellRenderer { if (it == '\t') "Tab" else it?.toString() }
  }
  internal val quoteCombo = ComboBox(arrayOf('"', '\''))
  internal val headerBox = JBCheckBox(PhantomDbBundle.message("db.csv.header"))
  private val previewModel = DefaultTableModel()
  private val preview = JBTable(previewModel).apply { autoResizeMode = JBTable.AUTO_RESIZE_OFF }
  internal val mappingCombos: List<ComboBox<String>> = columns.map { ComboBox<String>() }
  private val status = JBLabel(" ")
  private var csvColumns: List<String> = emptyList()

  init {
    title = PhantomDbBundle.message("db.csv.title", table.name)
    val format = DbCsv.detect(sample, columns)
    delimiterCombo.selectedItem = format.delimiter
    quoteCombo.selectedItem = format.quote
    headerBox.isSelected = format.header
    for (c in listOf(delimiterCombo, quoteCombo)) c.addActionListener { refresh(remap = true) }
    headerBox.addActionListener { refresh(remap = true) }
    init()
    refresh(remap = true)
  }

  val format: DbCsvFormat get() = DbCsvFormat(delimiterCombo.selectedItem as Char, quoteCombo.selectedItem as Char, headerBox.isSelected)

  /** For each table column, its CSV column index or `null`. */
  val mapping: List<Int?> get() = mappingCombos.map { combo -> combo.selectedIndex.takeIf { it > 0 }?.minus(1) }

  override fun createCenterPanel(): JComponent {
    val map = JPanel(GridBagLayout())
    val gc = GridBagConstraints().apply { insets = JBUI.insets(2, 4); anchor = GridBagConstraints.WEST }
    columns.forEachIndexed { i, name ->
      gc.gridy = i
      gc.gridx = 0; gc.weightx = 0.0; gc.fill = GridBagConstraints.NONE
      map.add(JBLabel(name), gc)
      gc.gridx = 1; gc.weightx = 1.0; gc.fill = GridBagConstraints.HORIZONTAL
      map.add(mappingCombos[i], gc)
    }
    return panel {
      row(PhantomDbBundle.message("db.csv.file")) { label(file.toString()) }
      row(PhantomDbBundle.message("db.csv.delimiter")) {
        cell(delimiterCombo)
        label(PhantomDbBundle.message("db.csv.quote"))
        cell(quoteCombo)
        cell(headerBox)
      }
      row { cell(JBScrollPane(preview).apply { preferredSize = JBUI.size(640, 220) }).align(AlignX.FILL).align(AlignY.FILL) }.resizableRow()
      row(PhantomDbBundle.message("db.csv.mapping")) {}
      row { cell(JBScrollPane(map).apply { preferredSize = JBUI.size(640, 160) }).align(AlignX.FILL) }
      row { cell(status) }
    }
  }

  override fun doValidate(): ValidationInfo? =
    if (mapping.none { it != null }) ValidationInfo(PhantomDbBundle.message("db.csv.no.columns")) else null

  private fun refresh(remap: Boolean) {
    val f = format
    val records = try {
      val r = DbCsvReader(sample.reader(), f.delimiter, f.quote)
      generateSequence { r.next() }.take(PREVIEW + 1).toList()
    }
    catch (e: DbException) {
      status.text = e.message
      emptyList()
    }
    val width = records.maxOfOrNull { it.size } ?: 0
    val headers = if (f.header) records.firstOrNull().orEmpty().map { it.orEmpty() } else null
    csvColumns = (0 until width).map { i -> headers?.getOrNull(i)?.takeIf { it.isNotBlank() } ?: PhantomDbBundle.message("db.csv.column", i + 1) }
    val data = (if (f.header) records.drop(1) else records).take(PREVIEW)
    previewModel.setDataVector(data.map { r -> Array<Any?>(width) { r.getOrNull(it) ?: "" } }.toTypedArray(), csvColumns.toTypedArray())
    if (remap) {
      val auto = DbCsv.mapping(headers, width, columns)
      mappingCombos.forEachIndexed { i, combo ->
        combo.model = javax.swing.DefaultComboBoxModel((listOf(PhantomDbBundle.message("db.csv.skip")) + csvColumns).toTypedArray())
        combo.selectedIndex = auto[i]?.plus(1) ?: 0
      }
    }
    status.text = PhantomDbBundle.message("db.csv.preview", data.size)
  }

  companion object {
    const val PREVIEW: Int = 50
    private const val SAMPLE_CHARS = 256 * 1024

    /** Chooses a file, reads its start and the table's columns off the EDT, then shows the dialog and imports in the background. */
    fun start(project: Project, dataSource: DbDataSource, table: DbTableRef) {
      val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor().withExtensionFilter("CSV", "csv", "tsv", "txt")
      val chosen = FileChooser.chooseFile(descriptor, project, null) ?: return
      val path = chosen.toNioPath()
      DbConnectionService.getInstance(project).launch {
        val (sample, columns) = try {
          val text = Files.newBufferedReader(path, StandardCharsets.UTF_8).use { r ->
            val buf = CharArray(SAMPLE_CHARS)
            val n = r.read(buf).coerceAtLeast(0)
            String(buf, 0, n).removePrefix("﻿")
          }
          text to DbMetadataCache.getInstance(project).columns(dataSource, table).map { it.name }
        }
        catch (e: CancellationException) {
          throw e
        }
        catch (e: Exception) {
          notify(project, (e as? java.sql.SQLException)?.let(DbExecutor::message) ?: e.message ?: e.javaClass.simpleName, NotificationType.ERROR)
          return@launch
        }
        withContext(Dispatchers.EDT) {
          val dialog = DbCsvImportDialog(project, table, path, sample, columns)
          if (dialog.showAndGet()) importInBackground(project, dataSource, table, path, columns, dialog.format, dialog.mapping)
        }
      }
    }

    private fun importInBackground(project: Project, dataSource: DbDataSource, table: DbTableRef, path: Path, columns: List<String>,
                                   format: DbCsvFormat, mapping: List<Int?>) {
      ProgressManager.getInstance().run(object : Task.Backgroundable(project, PhantomDbBundle.message("db.csv.importing", path.fileName, table.name), true) {
        override fun run(indicator: ProgressIndicator) {
          val message = try {
            val result = runBlockingCancellable {
              Files.newBufferedReader(path, StandardCharsets.UTF_8).use { r ->
                if (peekBom(r)) r.read()
                DbCsvImport.run(project, dataSource, table, columns, mapping, DbCsvReader(r, format.delimiter, format.quote), format.header,
                                indicator, "(${path.fileName})")
              }
            }
            PhantomDbBundle.message(if (result.pending) "db.csv.done.pending" else "db.csv.done", result.rows, table.name) to NotificationType.INFORMATION
          }
          catch (e: CancellationException) {
            PhantomDbBundle.message("db.csv.cancelled", table.name) to NotificationType.WARNING
          }
          catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
            PhantomDbBundle.message("db.csv.cancelled", table.name) to NotificationType.WARNING
          }
          catch (e: DbException) {
            e.message.orEmpty() to NotificationType.ERROR
          }
          catch (e: java.sql.SQLException) {
            DbExecutor.message(e) to NotificationType.ERROR
          }
          catch (e: java.io.IOException) {
            (e.message ?: e.javaClass.simpleName) to NotificationType.ERROR
          }
          notify(project, message.first, message.second)
        }
      })
    }

    private fun peekBom(r: java.io.BufferedReader): Boolean {
      r.mark(1)
      val c = r.read()
      r.reset()
      return c == 0xFEFF
    }

    private fun notify(project: Project, text: String, type: NotificationType) {
      NotificationGroupManager.getInstance().getNotificationGroup("Phantom Database")
        .createNotification(PhantomDbBundle.message("db.csv.notification"), text, type).notify(project)
    }
  }
}
