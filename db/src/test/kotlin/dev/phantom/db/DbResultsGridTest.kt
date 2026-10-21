// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db

import com.intellij.database.datagrid.ModelIndex
import com.intellij.database.datagrid.ModelIndexSet
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.db.core.*
import dev.phantom.db.core.exec.*
import dev.phantom.db.core.ui.DbFilterContext
import dev.phantom.db.core.ui.DbTableDataView
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.sql.Types
import javax.swing.JTable

class DbResultsGridTest : BasePlatformTestCase() {
  companion object {
    init {
      System.setProperty("idea.load.plugins.id", "dev.phantom.db,org.jetbrains.kotlin")
    }
  }

  private var counter = 0

  private fun h2(): DbDataSource {
    val name = "grid${counter++}"
    val ds = DbDataSource().apply {
      this.name = name
      dbms = Dbms.H2
      url = "jdbc:h2:mem:${name}_${System.nanoTime()};DB_CLOSE_DELAY=-1"
      user = "sa"
    }
    DbDataSourceService.getInstance(project).save(ds, CharArray(0))
    return ds
  }

  private fun exec(ds: DbDataSource, vararg sql: String): List<DbOutcome> = runBlocking { DbExecutor.execute(project, ds, sql.toList()) }

  private fun table(ds: DbDataSource, name: String): DbTableRef = runBlocking {
    val cache = DbMetadataCache.getInstance(project)
    cache.tables(ds, cache.schemas(ds).first { it.name == "PUBLIC" }).first { it.name == name }
  }

  private val sample = DbOutcome.Rows(
    "SELECT * FROM T",
    listOf(DbColumn("a", Types.VARCHAR, "VARCHAR", "java.lang.String"), DbColumn("b b", Types.INTEGER, "INTEGER", "java.lang.Integer")),
    listOf(arrayOf("x,\"y\"", null), arrayOf("line1\nline2", 2)), truncated = false, elapsedMs = 1)

  override fun tearDown() {
    try {
      ToolWindowManager.getInstance(project).getToolWindow(DbResultsToolWindow.ID)?.contentManager?.removeAllContents(true)
      val service = DbDataSourceService.getInstance(project)
      service.dataSources.forEach {
        DbConnectionService.getInstance(project).disconnect(it.id)
        service.remove(it.id)
      }
    }
    catch (e: Throwable) {
      addSuppressedException(e)
    }
    finally {
      super.tearDown()
    }
  }

  fun testFilterBarBuildsTheSqlResetsPagingAndSortsOnTheServer() {
    val ds = h2()
    exec(ds, "CREATE TABLE guest (id INT PRIMARY KEY, name VARCHAR(20))", "INSERT INTO guest SELECT X, 'g' || X FROM SYSTEM_RANGE(1, 150)")
    PropertiesComponent.getInstance().setValue("phantom.db.pageSize", 100, 500)
    try {
      val view = DbTableDataView.open(project, ds, table(ds, "GUEST"))
      PlatformTestUtil.waitWithEventsDispatching("first page", { view.result.shownRows?.size == 100 }, 20)
      view.load(100)
      PlatformTestUtil.waitWithEventsDispatching("second page", { view.offset == 100 && view.result.shownRows?.size == 50 }, 20)

      view.filter.where = "id > 140"
      view.filter.orderBy = "id DESC"
      view.filter.apply()
      PlatformTestUtil.waitWithEventsDispatching("filtered", { view.offset == 0 && view.result.shownRows?.firstOrNull()?.firstOrNull() == 150 }, 20)
      assertEquals("SELECT * FROM PUBLIC.GUEST WHERE id > 140 ORDER BY id DESC LIMIT 101", view.shownSql)
      assertEquals(10, view.result.shownRows!!.size)
      assertEquals("id > 140", PropertiesComponent.getInstance().getList("phantom.db.where:${ds.id}|${view.table.catalog}|PUBLIC|GUEST")?.first())

      // a header click orders by that column on the server: ascending, then descending, then not at all
      view.apply("")
      PlatformTestUtil.waitWithEventsDispatching("unfiltered", { view.result.shownRows?.size == 100 }, 20)
      for (expected in listOf("NAME", "NAME DESC", "")) {
        val grid = view.result.shownGrid!!
        grid.toggleSortColumns(listOf(ModelIndex.forColumn(grid, 1)), false)
        PlatformTestUtil.waitWithEventsDispatching("sorted by $expected", { view.filter.orderBy == expected && view.result.shownGrid !== grid && view.result.shownRows != null }, 20)
      }
      assertEquals("SELECT * FROM PUBLIC.GUEST LIMIT 101", view.shownSql)
      assertEquals("NAME DESC", DbTableQuery.nextOrder(Dbms.H2, "NAME", "order by  name"))
    }
    finally {
      PropertiesComponent.getInstance().unsetValue("phantom.db.pageSize")
    }
  }

  fun testFilterFragmentCompletesTheColumns() {
    myFixture.configureByText("filter.sql", "na<caret>")
    DbFilterContext.attach(myFixture.file, DbFilterContext { listOf(DbColumnInfo("NAME", "VARCHAR", true, false), DbColumnInfo("ID", "INT", false, true)) })
    myFixture.completeBasic()
    val items = myFixture.lookupElementStrings
    // the only column starting with "na" is inserted right away, or offered next to keywords
    if (items == null) assertEquals("NAME", myFixture.editor.document.text) else assertContainsElements(items, "NAME")
  }

  fun testRefilterOfASingleTableSelect() {
    assertEquals("select id, name from t x WHERE (x.id > 1) AND (name = 'a') ORDER BY name DESC limit 10",
                 SingleTableSelect.refilter("select id, name from t x where x.id > 1 order by id limit 10;", "name = 'a'", "name DESC"))
    assertEquals("SELECT * FROM t WHERE a = 'order by' ORDER BY id", SingleTableSelect.refilter("SELECT * FROM t ORDER BY id", "where a = 'order by'", ""))
    assertEquals("SELECT * FROM t", SingleTableSelect.refilter("SELECT * FROM t", " ", ""))
    assertNull(SingleTableSelect.refilter("SELECT * FROM a JOIN b ON a.id = b.id", "x = 1", ""))
  }

  fun testValueViewerFormatsJsonAndBytes() {
    val json = DbCellView.of("""{"a": [1, 2, {"b": "x,y"}], "c": {}, "d": "q\"}"}""")
    assertEquals(DbCellView.Kind.JSON, json.kind)
    assertEquals("""
      {
        "a": [
          1,
          2,
          {
            "b": "x,y"
          }
        ],
        "c": {},
        "d": "q\"}"
      }
    """.trimIndent(), json.text)
    assertEquals(DbCellView.Kind.TEXT, DbCellView.of("[not json").kind)
    assertEquals(DbCellView.Kind.TEXT, DbCellView.of("{} trailing").kind)
    assertEquals(DbCellView.Kind.XML, DbCellView.of("<a><b/></a>").kind)
    assertEquals(DbCellView.Kind.NULL, DbCellView.of(null).kind)

    val bytes = "Hello, binary\u0000\u0001world".toByteArray()
    val hex = DbCellView.of(bytes)
    assertEquals(DbCellView.Kind.BINARY, hex.kind)
    val lines = hex.text.lines()
    assertEquals("00000000  48 65 6c 6c 6f 2c 20 62  69 6e 61 72 79 00 01 77  |Hello, binary..w|", lines[0])
    assertTrue(lines[1], lines[1].startsWith("00000010  6f 72 6c 64   ") && lines[1].endsWith("  |orld|"))
    assertEquals("columns line up", lines[0].indexOf('|'), lines[1].indexOf('|'))
    assertTrue(DbCellView.of(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 0)).kind == DbCellView.Kind.IMAGE)
    assertTrue(DbCellView.hex(ByteArray(40), limit = 16).endsWith("… 24 more bytes\n"))
  }

  fun testValueViewerEditsGoThroughTheGrid() {
    val ds = h2()
    exec(ds, "CREATE TABLE t (id INT PRIMARY KEY, doc VARCHAR(200))", "INSERT INTO t VALUES (1, '{\"a\":1}')")
    myFixture.configureByText("v.sql", "SELECT * FROM t")
    DbRunner.start(project, myFixture.file.virtualFile, ds, listOf("SELECT * FROM t"))
    val panel = { ToolWindowManager.getInstance(project).getToolWindow(DbResultsToolWindow.ID)?.contentManager?.selectedContent?.component as? DbResultPanel }
    PlatformTestUtil.waitWithEventsDispatching("not editable", { panel()?.shownEditing != null }, 20)
    val p = panel()!!
    p.valueViewer = true
    val grid = p.shownGrid!!
    grid.selectionModel.setSelection(ModelIndex.forRow(grid, 0), ModelIndex.forColumn(grid, 1))
    val viewer = p.shownViewer!!
    viewer.update()
    assertEquals(DbCellView.Kind.JSON, viewer.shown!!.kind)
    assertEquals("{\n  \"a\": 1\n}", viewer.shown!!.text)
    viewer.applyText("{\"a\":2}")
    PlatformTestUtil.waitWithEventsDispatching("not written", {
      (exec(ds, "SELECT doc FROM t").single() as DbOutcome.Rows).rows.single()[0] == "{\"a\":2}"
    }, 20)
    p.valueViewer = false
    assertNull(p.shownViewer)
  }

  fun testTransposeAndSelectionAggregates() {
    val panel = DbResultPanel(project)
    Disposer.register(testRootDisposable, panel)
    val rows = DbOutcome.Rows("SELECT * FROM n", listOf(DbColumn("v", Types.DECIMAL, "DECIMAL", null), DbColumn("w", Types.INTEGER, "INT", null)),
                              listOf(arrayOf(BigDecimal("1"), 1), arrayOf(null, 2), arrayOf(2.5, 3)), false, 1)
    panel.showOutcomes(listOf(rows))
    val grid = panel.shownGrid!!
    val table = grid.resultView.component as JTable
    assertEquals(3, table.rowCount)
    panel.transposed = true
    assertTrue(grid.resultView.isTransposed)
    assertEquals("columns as rows", 2, table.rowCount)
    panel.transposed = false
    assertEquals(3, table.rowCount)

    // as a user selects: in the table (the selection model's own changes do not notify)
    PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    table.setColumnSelectionInterval(0, 0)
    table.setRowSelectionInterval(0, 2)
    assertEquals("3 cells · 2 distinct · sum 3.5 · avg 1.75 · min 1 · max 2.5", panel.shownAggregates)

    val mixed = DbAggregates.of(listOf(1, null, 2.5, 1))
    assertEquals(4, mixed.count)
    assertEquals(2, mixed.distinct)
    assertEquals(BigDecimal("4.5"), mixed.sum)
    assertEquals(BigDecimal("1.5"), mixed.avg)
    assertFalse(DbAggregates.of(listOf(1, "a", null)).numeric)
    assertEquals("2 cells · 0 distinct", DbAggregates.of(listOf(null, null)).text())
  }

  fun testRunsReplaceTheUnpinnedTabAndKeepPinnedOnes() {
    val ds = h2()
    val file = myFixture.configureByText("pin.sql", "SELECT 1").virtualFile
    val manager = { ToolWindowManager.getInstance(project).getToolWindow(DbResultsToolWindow.ID)!!.contentManager }
    val first = DbResultsToolWindow.tabFor(project, file, ds, "\n  SELECT 1\nFROM dual")
    assertEquals("SELECT 1", manager().selectedContent!!.displayName)
    assertSame(first, DbResultsToolWindow.tabFor(project, file, ds, "SELECT 2"))
    assertEquals(listOf("SELECT 2"), manager().contents.map { it.displayName })

    manager().selectedContent!!.isPinned = true
    val second = DbResultsToolWindow.tabFor(project, file, ds, "SELECT 3")
    assertNotSame(first, second)
    assertSame(second, DbResultsToolWindow.tabFor(project, file, ds, "SELECT 4"))
    assertEquals(listOf("SELECT 2", "SELECT 4"), manager().contents.map { it.displayName })
    assertTrue(DbResultsToolWindow.title("SELECT " + "x".repeat(60)).endsWith("…"))

    DbResultsToolWindow.closeAll(project)
    assertEquals("the pinned tab stays", listOf("SELECT 2"), manager().contents.map { it.displayName })
    manager().contents.single().isPinned = false
    DbResultsToolWindow.tabFor(project, file, ds, "SELECT 5")
    DbResultsToolWindow.closeOthers(project, manager().selectedContent)
    assertEquals(listOf("SELECT 5"), manager().contents.map { it.displayName })
  }

  fun testCopyFormatsOfASelection() {
    val panel = DbResultPanel(project)
    Disposer.register(testRootDisposable, panel)
    panel.showOutcomes(listOf(sample))
    val grid = panel.shownGrid!!
    grid.selectionModel.setSelection(ModelIndexSet.forRows(grid, 0, 1), ModelIndexSet.forColumns(grid, 0, 1))
    fun copy(format: DbExportFormat) = panel.copyText(grid, format)
    assertEquals("a\tb b\r\n\"x,\"\"y\"\"\"\t\r\n\"line1\nline2\"\t2\r\n", copy(DbExportFormat.TSV))
    assertEquals("a,b b\r\n\"x,\"\"y\"\"\",\r\n\"line1\nline2\",2\r\n", copy(DbExportFormat.CSV))
    assertEquals("[\n  {\"a\": \"x,\\\"y\\\"\", \"b b\": null},\n  {\"a\": \"line1\\nline2\", \"b b\": 2}\n]", copy(DbExportFormat.JSON))
    assertEquals("INSERT INTO T (a, `b b`) VALUES ('x,\"y\"', NULL);\nINSERT INTO T (a, `b b`) VALUES ('line1\nline2', 2);\n", copy(DbExportFormat.INSERT))
    assertEquals("| a | b b |\n| --- | --- |\n| x,\"y\" | NULL |\n| line1 line2 | 2 |\n", copy(DbExportFormat.MARKDOWN))
    assertEquals("<table>\n  <tr><th>a</th><th>b b</th></tr>\n  <tr><td>x,&quot;y&quot;</td><td></td></tr>\n  <tr><td>line1<br>line2</td><td>2</td></tr>\n</table>\n",
                 copy(DbExportFormat.HTML))

    // a part of the selection: the second column of the second row
    grid.selectionModel.setSelection(ModelIndex.forRow(grid, 1), ModelIndex.forColumn(grid, 1))
    assertEquals("b b\r\n2\r\n", copy(DbExportFormat.CSV))
  }

  fun testTableExportStreamsEveryRowPageByPage() {
    val ds = h2()
    exec(ds, "CREATE TABLE big (id INT PRIMARY KEY, name VARCHAR(20))", "INSERT INTO big SELECT X, 'n' || X FROM SYSTEM_RANGE(1, 150)")
    val history = DbQueryHistory.getInstance(project)
    history.clear()
    val out = StringBuilder()
    val progress = ArrayList<Long>()
    val count = runBlocking {
      DbTableExport.export(project, ds, table(ds, "BIG"), "", "id", DbExportFormat.CSV, out, pageSize = 40) { progress.add(it) }
    }
    assertEquals(150L, count)
    val lines = out.lines().filter { it.isNotEmpty() }
    assertEquals(151, lines.size)
    assertEquals("ID,NAME", lines.first())
    assertEquals("150,n150", lines.last())
    assertEquals(listOf(40L, 80L, 120L, 150L), progress)
    assertTrue("export pages stay out of the history", history.entries.isEmpty())

    val filtered = StringBuilder()
    runBlocking { DbTableExport.export(project, ds, table(ds, "BIG"), "id <= 3", "id DESC", DbExportFormat.JSON, filtered, pageSize = 2) }
    assertEquals("[\n  {\"ID\": 3, \"NAME\": \"n3\"},\n  {\"ID\": 2, \"NAME\": \"n2\"},\n  {\"ID\": 1, \"NAME\": \"n1\"}\n]", filtered.toString())
  }
}
