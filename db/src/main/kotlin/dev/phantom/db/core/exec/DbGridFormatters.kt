// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.exec

import com.intellij.database.datagrid.CoreGrid
import com.intellij.database.datagrid.FormatterCreatorProvider
import com.intellij.database.datagrid.GridColumn
import com.intellij.database.datagrid.GridRow
import com.intellij.database.datagrid.DataGrid
import com.intellij.database.extractors.BaseObjectFormatter
import com.intellij.database.extractors.FormatterCreator
import com.intellij.database.extractors.ObjectFormatterConfig
import com.intellij.database.datagrid.JdbcGridColumn
import com.intellij.database.run.ui.grid.editors.BoundaryValueResolver
import com.intellij.database.run.ui.grid.editors.DateAndTimeFormatter
import com.intellij.database.run.ui.grid.editors.FormatsCache
import com.intellij.database.run.ui.grid.editors.TimestampDelegate
import com.intellij.database.run.ui.grid.editors.Formatter
import com.intellij.util.Function
import java.sql.Types
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField

/**
 * Shows the fraction of a TIMESTAMP in the grid. The grid's own formatter prints a fixed number of fraction digits that
 * comes from the column, and the base creator gives every column 0, so no fraction showed at all. Here the fraction
 * is as long as the value needs, up to the column's scale: none for whole seconds, trailing zeros cut.
 * Only the Phantom DB grids use it; TIME and the zoned types keep the grid's display.
 */
class DbFormatterCreatorProvider : FormatterCreatorProvider {
  override fun isApplicable(grid: CoreGrid<GridRow, GridColumn>): Boolean = grid.dataHookup is DbEditableHookUp

  override fun isApplicable(target: FormatterCreatorProvider.FormatterCreatorTarget): Boolean = false

  override fun createCache(): Function<CoreGrid<GridRow, GridColumn>, FormatterCreator> {
    val creator = DbFormatterCreator()
    return Function { creator }
  }

  override fun create(target: FormatterCreatorProvider.FormatterCreatorTarget): FormatterCreator = DbFormatterCreator()
}

class DbFormatterCreator : FormatterCreator() {
  /** The column's scale for a TIMESTAMP, 0 for anything else or a scale the driver made up. */
  public override fun calculateScale(column: GridColumn?, config: ObjectFormatterConfig?): Int =
    if (column is JdbcGridColumn && column.type == Types.TIMESTAMP) column.scale.coerceIn(0, minOf(MAX_SCALE, MAX_FRACTION_DIGITS)) else 0

  public override fun newIntrinsicTimestampFormat(column: GridColumn?, scale: Int, resolver: BoundaryValueResolver,
                                                  config: ObjectFormatterConfig?, formatsCache: FormatsCache): Formatter {
    if (scale <= 0) return super.newIntrinsicTimestampFormat(column, scale, resolver, config, formatsCache)
    val fb = DateTimeFormatterBuilder()
    appendDate(fb)
    fb.appendPattern("[ ]")
    appendTime(fb)
    // parsing is lenient about the width, printing cuts trailing zeros and prints no dot for a zero fraction
    fb.parseLenient().appendFraction(ChronoField.NANO_OF_SECOND, 0, scale, true).parseStrict()
    return DateAndTimeFormatter("yyyy-MM-dd HH:mm:ss[." + "f".repeat(scale) + "]", toFormatter(fb), TimestampDelegate(formatsCache, this, config), resolver, null)
  }

  companion object {
    const val MAX_SCALE = 6
  }
}

/**
 * The grid's object formatter. The community CSV setup gives a grid a formatter with the base [FormatterCreator], which
 * does not ask the provider, so the grid is given one made with the provider's creator. A `java.time` date-time is shown
 * as `yyyy-MM-dd HH:mm:ss[.fff]`, not in its ISO form with the 'T'.
 */
class DbObjectFormatter(creator: FormatterCreator) : BaseObjectFormatter(FormatsCache(), creator) {
  override fun objectToString(o: Any?, column: GridColumn?, config: ObjectFormatterConfig): String? =
    if (o is LocalDateTime) LOCAL_DATE_TIME.format(o) else super.objectToString(o, column, config)

  companion object {
    private val LOCAL_DATE_TIME: DateTimeFormatter = DateTimeFormatterBuilder().appendPattern("yyyy-MM-dd HH:mm:ss")
      .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true).toFormatter()

    fun install(grid: DataGrid) {
      val formatter = DbObjectFormatter(FormatterCreator.get(grid))
      grid.setObjectFormatterProvider { formatter }
    }
  }
}
