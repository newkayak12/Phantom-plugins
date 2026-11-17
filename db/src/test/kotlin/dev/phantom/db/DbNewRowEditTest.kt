// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db

import com.intellij.database.datagrid.DataGrid
import com.intellij.database.datagrid.GridCellRequest
import com.intellij.database.datagrid.GridRequestSource
import com.intellij.database.datagrid.ModelIndex
import com.intellij.database.datagrid.ModelIndexSet
import com.intellij.database.run.ui.DataAccessType
import com.intellij.database.editor.DataGridColors
import com.intellij.database.run.ReservedCellValue
import com.intellij.database.run.ui.DataGridRequestPlace
import com.intellij.database.run.ui.grid.renderers.GridCellRenderer
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.EditorTextFieldCellRenderer
import dev.phantom.db.core.*
import dev.phantom.db.core.exec.*
import kotlinx.coroutines.runBlocking

/** Add Row: the new row's first editable cell is edited, and the cells nobody filled in show a grey `<default>`. */
class DbNewRowEditTest : BasePlatformTestCase() {
  companion object {
    init {
      System.setProperty("idea.load.plugins.id", "dev.phantom.db,org.jetbrains.kotlin")
    }
  }

  private var counter = 0
  private var wasImmediate = false

  override fun setUp() {
    super.setUp()
    wasImmediate = DbGridSettings.submitImmediately
    DbGridSettings.submitImmediately = false
  }

  override fun tearDown() {
    try {
      DbGridSettings.submitImmediately = wasImmediate
      val service = DbDataSourceService.getInstance(project)
      service.dataSources.forEach {
        DbConnectionService.getInstance(project).disconnect(it.id)
        service.remove(it.id)
      }
      ToolWindowManager.getInstance(project).getToolWindow(DbResultsToolWindow.ID)?.contentManager?.removeAllContents(true)
    }
    catch (e: Throwable) {
      addSuppressedException(e)
    }
    finally {
      super.tearDown()
    }
  }

  private fun h2(): DbDataSource {
    val name = "newrow${counter++}"
    return DbDataSource().apply {
      this.name = name
      dbms = Dbms.H2
      url = "jdbc:h2:mem:${name}_${System.nanoTime()};DB_CLOSE_DELAY=-1"
      user = "sa"
    }.also { DbDataSourceService.getInstance(project).save(it, CharArray(0)) }
  }

  private fun exec(ds: DbDataSource, vararg sql: String) = runBlocking { DbExecutor.execute(project, ds, sql.toList()) }

  private fun rows(ds: DbDataSource, sql: String): DbOutcome.Rows = exec(ds, sql).single() as DbOutcome.Rows

  /** The grid of `t (id key, name, note default 'n')` with one row (2, 'b', NULL), editable. */
  private fun open(): Pair<DbResultPanel, DbGridEdits> {
    val ds = h2()
    exec(ds, "CREATE TABLE t (id INT PRIMARY KEY, name VARCHAR(10), note VARCHAR(10) DEFAULT 'n')", "INSERT INTO t VALUES (2, 'b', NULL)")
    myFixture.configureByText("rows.sql", "SELECT * FROM t")
    DbRunner.start(project, myFixture.file.virtualFile, ds, listOf("SELECT * FROM t"))
    val panel = { ToolWindowManager.getInstance(project).getToolWindow(DbResultsToolWindow.ID)?.contentManager?.selectedContent?.component as? DbResultPanel }
    PlatformTestUtil.waitWithEventsDispatching("grid not editable; status: ${panel()?.shownStatus}", { panel()?.shownEditing != null }, 20)
    return panel()!! to panel()!!.shownEditing!!
  }

  private fun place(grid: DataGrid) = GridRequestSource(DataGridRequestPlace(grid))

  private fun cell(edits: DbGridEdits, row: Int, column: Int): Any? {
    val model = edits.hookUp.dataModel
    return model.getValueAt(ModelIndex.forRow(model, row), ModelIndex.forColumn(model, column))
  }

  /** The text and attributes the grid's renderer gives the cell. */
  private fun rendered(grid: DataGrid, row: Int, column: Int): Pair<String, TextAttributes?> {
    val model = grid.getDataModel(DataAccessType.DATA_WITH_MUTATIONS)
    val request = GridCellRequest.request(grid, ModelIndex.forRow(model, row), ModelIndex.forColumn(model, column))
    val renderer = GridCellRenderer.getRenderer(request)
    val component = renderer.getComponent(ModelIndex.forRow(model, row).toView(grid), ModelIndex.forColumn(model, column).toView(grid), request.getValue())
    val rendererComponent = component as EditorTextFieldCellRenderer.RendererComponent
    // the attributes the renderer gave the text, which the component keeps for its editor's highlighter
    val attributes = EditorTextFieldCellRenderer.RendererComponent::class.java.getDeclaredField("myTextAttributes").apply { isAccessible = true }
    // the text reaches the editor's document only when the cell is painted
    val text = rendererComponent.javaClass.getDeclaredField("myRawText").apply { isAccessible = true }
    return text.get(rendererComponent) as String to attributes.get(rendererComponent) as TextAttributes?
  }

  fun testAddRowSelectsAndEditsFirstEditableCell() {
    val (panel, edits) = open()
    val grid = panel.shownGrid!!
    val started = mutableListOf<Pair<Int, Int>>()
    val real = edits.startEdit
    try {
      edits.startEdit = { g, row, column -> started += row to column; assertSame(grid, g) }
      edits.insertRows(place(grid), 1)
      PlatformTestUtil.waitWithEventsDispatching("editing not started", { started.isNotEmpty() }, 20)
      assertEquals(listOf(1 to 0), started)
    }
    finally {
      edits.startEdit = real
    }
    // the real thing selects the cell and opens its editor
    edits.insertRows(place(grid), 1)
    PlatformTestUtil.waitWithEventsDispatching("cell not selected", { grid.selectionModel.selectedRow.value == 2 }, 20)
    assertEquals(0, grid.selectionModel.selectedColumn.value)
    PlatformTestUtil.waitWithEventsDispatching("not editing", { grid.resultView.isEditing }, 20)
    grid.resultView.cancelEditing()
  }

  fun testUnsetNewRowCellShowsDefault() {
    val (panel, edits) = open()
    edits.insertRows(GridRequestSource(null), 1)
    assertSame(ReservedCellValue.UNSET, cell(edits, 1, 2))
    assertEquals("<default>", rendered(panel.shownGrid!!, 1, 2).first)
    assertEquals("a NULL read from the database stays <null>", "<null>", rendered(panel.shownGrid!!, 0, 2).first)
  }

  fun testUnsetNewRowCellIsGreyLikeNull() {
    val (panel, edits) = open()
    val grid = panel.shownGrid!!
    edits.insertRows(GridRequestSource(null), 1)
    val (nullText, nullAttributes) = rendered(grid, 0, 2)
    val (defaultText, defaultAttributes) = rendered(grid, 1, 2)
    assertEquals("<null>", nullText)
    assertEquals("<default>", defaultText)
    val grey = grid.colorsScheme.getAttributes(DataGridColors.GRID_NULL_VALUE)
    assertEquals(grey.foregroundColor, EditorColorsManager.getInstance().globalScheme.getAttributes(DataGridColors.GRID_NULL_VALUE).foregroundColor)
    assertNotNull(defaultAttributes)
    assertEquals(nullAttributes!!.foregroundColor, defaultAttributes!!.foregroundColor)
    assertEquals(grey.foregroundColor, defaultAttributes.foregroundColor)
    assertNotNull("the scheme draws <null> in its own color", grey.foregroundColor)
    assertFalse("a stored value is not grey", rendered(grid, 0, 1).second?.foregroundColor == grey.foregroundColor)
  }

  fun testUnsetCellsTakeDatabaseDefaults() {
    val (panel, edits) = open()
    val source = GridRequestSource(null)
    val model = edits.hookUp.dataModel
    edits.insertRows(source, 1)
    edits.mutate(source, ModelIndexSet.forRows(model, 1), ModelIndexSet.forColumns(model, 0), 3, true)
    assertEquals("INSERT INTO \"T\" (\"ID\") VALUES (3);", edits.pendingChanges)
    var submitted: Boolean? = null
    edits.submit(source) { submitted = it }
    PlatformTestUtil.waitWithEventsDispatching("not submitted", { submitted != null }, 20)
    assertTrue(submitted!!)
    assertEquals(listOf<Any?>(3, null, "n"), rows(panel.shownEditing!!.dataSource, "SELECT * FROM t WHERE id = 3").rows.single().toList())
    assertEquals("no marker is left in the row", "n", cell(edits, 1, 2))
    assertNotSame(ReservedCellValue.UNSET, cell(edits, 1, 1))
  }

  fun testSetNullOnNewRowInsertsNull() {
    val (_, edits) = open()
    val source = GridRequestSource(null)
    val model = edits.hookUp.dataModel
    edits.insertRows(source, 1)
    edits.mutate(source, ModelIndexSet.forRows(model, 1), ModelIndexSet.forColumns(model, 0), 3, true)
    edits.mutate(source, ModelIndexSet.forRows(model, 1), ModelIndexSet.forColumns(model, 2), null, true)
    assertEquals("INSERT INTO \"T\" (\"ID\", \"NOTE\") VALUES (3, NULL);", edits.pendingChanges)
    var submitted: Boolean? = null
    edits.submit(source) { submitted = it }
    PlatformTestUtil.waitWithEventsDispatching("not submitted", { submitted != null }, 20)
    assertTrue(submitted!!)
    assertEquals(listOf<Any?>(3, null, null), rows(edits.dataSource, "SELECT * FROM t WHERE id = 3").rows.single().toList())
  }

  fun testClonedRowCellsAreNotDefault() {
    val (_, edits) = open()
    val source = GridRequestSource(null)
    val model = edits.hookUp.dataModel
    edits.cloneRow(source, ModelIndex.forRow(model, 0))
    for (c in 0..2) assertNotSame(ReservedCellValue.UNSET, cell(edits, 1, c))
    assertEquals(listOf<Any?>(2, "b", null), (0..2).map { cell(edits, 1, it) })
    assertEquals("INSERT INTO \"T\" (\"ID\", \"NAME\", \"NOTE\") VALUES (2, 'b', NULL);", edits.pendingChanges)
  }

  fun testPendingChangesNeverContainMarker() {
    val (panel, edits) = open()
    val source = GridRequestSource(null)
    val model = edits.hookUp.dataModel
    edits.insertRows(source, 2)
    edits.mutate(source, ModelIndexSet.forRows(model, 1), ModelIndexSet.forColumns(model, 0), 3, true)
    edits.mutate(source, ModelIndexSet.forRows(model, 1), ModelIndexSet.forColumns(model, 1), "x", true)
    val text = edits.pendingChanges
    for (marker in listOf("<default>", "<unset>", "UNSET", "ReservedCellValue", "<null>")) assertFalse("$marker in: $text", text.contains(marker))
    // nor do the value viewer's, Copy As's and export's reads of a cell
    val grid = panel.shownGrid!!
    grid.selectionModel.setSelection(ModelIndex.forRow(model, 2), ModelIndex.forColumn(model, 2))
    assertEquals(listOf<Any?>(null), DbGridSelection.values(grid))
    assertNull(DbGridSelection.rows(grid, emptyList(), "").rows.single().single())
    // revert drops the new row
    edits.revert(source, ModelIndexSet.forRows(model, 2), ModelIndexSet.forColumns(model, 0))
    assertEquals(2, model.rowCount)
  }

  fun testAddRowWithoutGridDoesNotFail() {
    val (_, edits) = open()
    edits.insertRows(GridRequestSource(null), 1)
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    assertEquals(2, edits.hookUp.dataModel.rowCount)
    assertTrue(edits.isInsertedRow(ModelIndex.forRow(edits.hookUp.dataModel, 1)))
  }
}
