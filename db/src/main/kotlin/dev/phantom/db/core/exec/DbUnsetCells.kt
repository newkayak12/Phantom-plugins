// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.exec

import com.intellij.database.datagrid.DataGrid
import com.intellij.database.datagrid.GridCellRequest
import com.intellij.database.datagrid.GridColumn
import com.intellij.database.datagrid.GridRow
import com.intellij.database.datagrid.ModelIndex
import com.intellij.database.run.ReservedCellValue
import com.intellij.database.run.ui.grid.renderers.DefaultBooleanRendererFactory
import com.intellij.database.run.ui.grid.renderers.DefaultNumericRendererFactory
import com.intellij.database.run.ui.grid.renderers.DefaultTextRendererFactory
import com.intellij.database.run.ui.grid.renderers.GridCellRenderer
import com.intellij.database.run.ui.grid.renderers.GridCellRendererFactories
import com.intellij.database.run.ui.grid.renderers.GridCellRendererFactory
import com.intellij.openapi.util.Disposer

/**
 * The cells of a new row nobody filled in hold [UNSET], so the grid draws them the way it draws `<null>` (grey, its
 * GRID_NULL_VALUE), and the text it draws is `<default>`: the database fills them when the row is inserted.
 */
internal object DbUnsetCells {
  val UNSET: ReservedCellValue = ReservedCellValue.UNSET

  /** The value of a cell as stored: the marker is nothing, not a value. */
  fun stored(value: Any?): Any? = if (value === UNSET) null else DbGridTime.fromGrid(value)

  /** Draws [UNSET] cells before the grid's own renderers get a say. */
  fun install(grid: DataGrid) {
    GridCellRendererFactories.set(grid, GridCellRendererFactories(listOf(
      UnsetFactory(grid), DefaultBooleanRendererFactory(grid), DefaultNumericRendererFactory(grid), DefaultTextRendererFactory(grid))))
  }

  private class UnsetFactory(private val grid: DataGrid) : GridCellRendererFactory {
    private val renderer: GridCellRenderer by lazy { UnsetRenderer(grid).also { Disposer.register(grid, it) } }

    override fun supports(request: GridCellRequest<GridRow, GridColumn>): Boolean = request.getValue() === UNSET

    override fun getOrCreateRenderer(request: GridCellRequest<GridRow, GridColumn>): GridCellRenderer = renderer
  }

  private class UnsetRenderer(grid: DataGrid) : DefaultTextRendererFactory.TextRenderer(grid) {
    override fun getSuitability(request: GridCellRequest<GridRow, GridColumn>): Int =
      if (request.getValue() === UNSET) SUITABILITY_MAX else SUITABILITY_UNSUITABLE

    override fun getValueText(column: ModelIndex<GridColumn>, row: ModelIndex<GridRow>?, value: Any): String =
      if (value === UNSET) ReservedCellValue.DEFAULT.displayName else super.getValueText(column, row, value)
  }
}
