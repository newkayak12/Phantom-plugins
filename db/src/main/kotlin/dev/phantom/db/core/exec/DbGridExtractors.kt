// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.exec

import com.intellij.database.datagrid.CoreGrid
import com.intellij.database.datagrid.DataGrid
import com.intellij.database.datagrid.GridColumn
import com.intellij.database.datagrid.GridRow
import com.intellij.database.extractors.BaseExtractorConfig
import com.intellij.database.extractors.BaseExtractorsHelper
import com.intellij.database.extractors.DataExtractor
import com.intellij.database.extractors.DataExtractorFactory
import com.intellij.database.extractors.ExtractionConfig
import com.intellij.database.extractors.ExtractorConfig
import com.intellij.database.extractors.GridExtractorsUtilCore
import com.intellij.database.extractors.ObjectFormatter
import com.intellij.database.util.Out
import com.intellij.util.ui.UIUtil
import dev.phantom.db.core.Dbms

/** A result's columns, where its INSERTs and UPDATEs go (null: unknown) and the columns an UPDATE finds a row by. */
class DbExtractionContext(val columns: List<DbColumn>, val table: String?, val dbms: Dbms, val keys: List<Int>)

/**
 * The Copy As formats in a result grid's Data Extractor list, as in DataGrip: the one chosen there is what Copy (⌘C)
 * puts on the clipboard, and the grid's own CSV / TSV / Excel extractors stay next to them.
 */
class DbExtractorsHelper : BaseExtractorsHelper() {
  override fun isApplicable(grid: CoreGrid<GridRow, GridColumn>?): Boolean = grid != null && context(grid) != null

  override fun getBuiltInFactories(): List<DataExtractorFactory> = DbExportFormat.entries.filter { it.text }.map(::DbExtractorFactory) + super.getBuiltInFactories()

  override fun createExtractorConfig(grid: CoreGrid<GridRow, GridColumn>, formatter: ObjectFormatter): ExtractorConfig =
    DbExtractorConfig(formatter, grid, context(grid))

  private fun context(grid: CoreGrid<GridRow, GridColumn>): DbExtractionContext? {
    val dataGrid = grid as? DataGrid ?: return null
    return UIUtil.getParentOfType(DbResultPanel::class.java, dataGrid.panel.component)?.extractionContext(dataGrid)
  }
}

class DbExtractorConfig(formatter: ObjectFormatter, grid: CoreGrid<GridRow, GridColumn>, val context: DbExtractionContext?) :
  BaseExtractorConfig(formatter, grid.project)

class DbExtractorFactory(private val format: DbExportFormat) : DataExtractorFactory {
  override fun getName(): String = format.title
  override fun getId(): String = id(format)
  override fun supportsText(): Boolean = true
  override fun getFileExtension(): String = format.extension
  override fun createExtractor(config: ExtractorConfig): DataExtractor = DbDataExtractor(format, (config as? DbExtractorConfig)?.context)

  companion object {
    fun id(format: DbExportFormat): String = "PhantomDb.${format.name}"
  }
}

/** Takes the rows the grid hands over and writes them in [format] once they are all there. */
class DbDataExtractor(private val format: DbExportFormat, private val context: DbExtractionContext?) : DataExtractor {
  override fun getFileExtension(): String = format.extension
  override fun supportsText(): Boolean = true

  override fun startExtraction(out: Out, columns: List<GridColumn>, query: String, config: ExtractionConfig, vararg selectedColumns: Int): DataExtractor.Extraction =
    object : DataExtractor.Extraction {
      private var all: List<GridColumn> = columns
      private val rows = ArrayList<Array<Any?>>()

      /** The columns to write, by column number; none selected means all. */
      private fun picked(): List<GridColumn> {
        val numbers = GridExtractorsUtilCore.getNonEmptySelection(all, selectedColumns).toList()
        return numbers.mapNotNull { n -> all.firstOrNull { it.columnNumber == n } }
      }

      override fun updateColumns(columns: Array<out GridColumn>) {
        all = columns.toList()
      }

      override fun addData(rows: List<GridRow>) {
        val picked = picked()
        rows.forEach { row -> this.rows.add(Array(picked.size) { DbUnsetCells.stored(picked[it].getValue(row)) }) }
      }

      override fun complete() {
        val picked = picked()
        val numbers = picked.map { it.columnNumber }
        val columns = picked.map { c ->
          context?.columns?.getOrNull(c.columnNumber) ?: DbColumn(c.name, c.type, c.typeName, null)
        }
        val keys = context?.keys.orEmpty().let { if (it.isNotEmpty() && numbers.containsAll(it)) it.map(numbers::indexOf) else emptyList() }
        out.appendText(format.render(DbOutcome.Rows(query, columns, rows, false, 0), context?.table, context?.dbms ?: Dbms.MYSQL, keys))
      }
    }
}
