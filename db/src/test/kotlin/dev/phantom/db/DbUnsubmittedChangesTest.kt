// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db

import com.intellij.database.datagrid.GridRequestSource
import com.intellij.database.datagrid.ModelIndexSet
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.db.core.*
import dev.phantom.db.core.exec.*
import dev.phantom.db.core.ui.DbTableDataView
import kotlinx.coroutines.runBlocking

/** Result with changes the user did not Submit: asked about before it is replaced or its tab closes. */
class DbUnsubmittedChangesTest : BasePlatformTestCase() {
  companion object {
    init {
      System.setProperty("idea.load.plugins.id", "dev.phantom.db,org.jetbrains.kotlin")
    }
  }

  private var counter = 0
  private val savedAsk = DbUnsubmittedChanges.ask
  private val asked = mutableListOf<DbResultPanel>()

  private fun answer(answer: DbUnsubmittedAnswer) {
    DbUnsubmittedChanges.ask = { panel ->
      asked.add(panel)
      answer
    }
  }

  private fun h2(): DbDataSource {
    val name = "unsub${counter++}"
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

  private fun values(ds: DbDataSource, sql: String): List<List<Any?>> = (exec(ds, sql).single() as DbOutcome.Rows).rows.map { it.toList() }

  private fun contents() = ToolWindowManager.getInstance(project).getToolWindow(DbResultsToolWindow.ID)?.contentManager

  private fun panel() = contents()?.selectedContent?.component as? DbResultPanel

  override fun tearDown() {
    try {
      DbUnsubmittedChanges.ask = savedAsk
      val service = DbDataSourceService.getInstance(project)
      service.dataSources.forEach {
        DbConnectionService.getInstance(project).disconnect(it.id)
        service.remove(it.id)
      }
      contents()?.removeAllContents(true)
    }
    catch (e: Throwable) {
      addSuppressedException(e)
    }
    finally {
      super.tearDown()
    }
  }

  private fun table(ds: DbDataSource) = exec(ds, "CREATE TABLE t (id INT PRIMARY KEY, name VARCHAR(10), code VARCHAR(5) UNIQUE)",
                                             "INSERT INTO t VALUES (1, 'a', 'x'), (2, 'b', 'y')")

  /** A run of the select in its tab, shown and editable. */
  private fun run(ds: DbDataSource, sql: String = "SELECT * FROM t ORDER BY id"): DbResultPanel {
    myFixture.configureByText("unsub.sql", sql)
    DbRunner.start(project, myFixture.file.virtualFile, ds, listOf(sql))
    PlatformTestUtil.waitWithEventsDispatching("grid not editable; status: ${panel()?.shownStatus}", { panel()?.shownEditing != null }, 20)
    return panel()!!
  }

  private fun edit(panel: DbResultPanel, column: Int, value: String, row: Int = 0) {
    val edits = panel.shownEditing!!
    val model = edits.hookUp.dataModel
    edits.mutate(GridRequestSource(null), ModelIndexSet.forRows(model, row), ModelIndexSet.forColumns(model, column), value, true)
    assertTrue(edits.hasPendingChanges())
  }

  fun testCancelKeepsResultAndPendingChanges() {
    val ds = h2()
    table(ds)
    val panel = run(ds)
    val grid = panel.shownGrid
    val edits = panel.shownEditing!!
    edit(panel, 1, "z")
    answer(DbUnsubmittedAnswer.CANCEL)

    DbRunner.start(project, myFixture.file.virtualFile, ds, listOf("SELECT * FROM t ORDER BY id"))
    assertEquals(1, asked.size)
    assertSame(grid, panel.shownGrid)
    assertSame(edits, panel.shownEditing)
    assertTrue(edits.hasPendingChanges())
    assertEquals(2, panel.shownRows!!.size)

    // the filter bar's run is guarded the same way
    panel.refilter()
    assertEquals(2, asked.size)
    assertSame(grid, panel.shownGrid)
    assertTrue(edits.hasPendingChanges())
  }

  fun testDiscardRerunsWithoutWriting() {
    val ds = h2()
    table(ds)
    val panel = run(ds)
    val grid = panel.shownGrid
    edit(panel, 1, "z")
    answer(DbUnsubmittedAnswer.DISCARD)

    DbRunner.start(project, myFixture.file.virtualFile, ds, listOf("SELECT * FROM t ORDER BY id"))
    assertEquals(1, asked.size)
    PlatformTestUtil.waitWithEventsDispatching("not run again", { panel.shownGrid != null && panel.shownGrid !== grid && panel.shownEditing != null }, 20)
    assertFalse(panel.shownEditing!!.hasPendingChanges())
    assertEquals(listOf(listOf<Any?>(1, "a", "x"), listOf<Any?>(2, "b", "y")), values(ds, "SELECT * FROM t ORDER BY id"))
  }

  fun testSubmitWritesThenReruns() {
    val ds = h2()
    table(ds)
    val panel = run(ds)
    val grid = panel.shownGrid
    edit(panel, 1, "z")
    answer(DbUnsubmittedAnswer.SUBMIT)

    DbRunner.start(project, myFixture.file.virtualFile, ds, listOf("SELECT * FROM t ORDER BY id"))
    PlatformTestUtil.waitWithEventsDispatching("not run again", { panel.shownGrid != null && panel.shownGrid !== grid && panel.shownEditing != null }, 20)
    assertEquals(1, asked.size)
    assertEquals(listOf<Any?>(1, "z", "x"), values(ds, "SELECT * FROM t ORDER BY id")[0])
    assertEquals("z", panel.shownRows!![0][1])
  }

  fun testFailedSubmitDoesNotProceed() {
    val ds = h2()
    table(ds)
    val panel = run(ds)
    val grid = panel.shownGrid
    val edits = panel.shownEditing!!
    // a second row with the first one's unique code
    edit(panel, 2, "y")
    answer(DbUnsubmittedAnswer.SUBMIT)

    var ran = false
    val original = edits.run
    edits.run = { statement -> original(statement).also { ran = true } }
    var proceeded = false
    panel.leaveEdits { proceeded = true }
    PlatformTestUtil.waitWithEventsDispatching("not submitted", { ran }, 20)
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    assertEquals(1, asked.size)
    assertFalse(proceeded)
    assertSame(grid, panel.shownGrid)
    assertTrue(edits.hasPendingChanges())
    assertEquals(listOf<Any?>(1, "a", "x"), values(ds, "SELECT * FROM t ORDER BY id")[0])
  }

  fun testTabCloseCancelKeepsTab() {
    val ds = h2()
    table(ds)
    val panel = run(ds)
    edit(panel, 1, "z")
    answer(DbUnsubmittedAnswer.CANCEL)

    val content = contents()!!.getContent(panel)!!
    assertFalse(contents()!!.removeContent(content, true))
    assertEquals(1, asked.size)
    assertEquals(1, contents()!!.contentCount)
    assertTrue(panel.shownEditing!!.hasPendingChanges())
  }

  fun testTabCloseDiscardRemovesTabAskingOnce() {
    val ds = h2()
    table(ds)
    val panel = run(ds)
    edit(panel, 1, "z")
    answer(DbUnsubmittedAnswer.DISCARD)

    contents()!!.removeContent(contents()!!.getContent(panel)!!, true)
    assertEquals(1, asked.size)
    assertEquals(0, contents()!!.contentCount)
    assertEquals(listOf<Any?>(1, "a", "x"), values(ds, "SELECT * FROM t ORDER BY id")[0])
  }

  fun testTableDataViewPagingIsGuarded() {
    val ds = h2()
    exec(ds, "CREATE TABLE t (id INT PRIMARY KEY, name VARCHAR(10))", "INSERT INTO t SELECT X, 'n' || X FROM SYSTEM_RANGE(1, 150)")
    val cache = DbMetadataCache.getInstance(project)
    val ref = runBlocking { cache.tables(ds, cache.schemas(ds).first { it.name == "PUBLIC" }) }.first { it.name == "T" }
    com.intellij.ide.util.PropertiesComponent.getInstance().setValue("phantom.db.pageSize", 100, 500)
    try {
      val view = DbTableDataView.open(project, ds, ref)
      PlatformTestUtil.waitWithEventsDispatching({ "no rows: " + view.result.shownStatus }, { view.result.shownRows != null }, 20)
      PlatformTestUtil.waitWithEventsDispatching("not editable", { view.result.shownEditing != null }, 20)
      val grid = view.result.shownGrid
      edit(view.result, 1, "z")
      answer(DbUnsubmittedAnswer.CANCEL)

      view.load(100)
      view.load(0)
      assertEquals(2, asked.size)
      assertEquals(0, view.offset)
      assertSame(grid, view.result.shownGrid)
      assertTrue(view.result.shownEditing!!.hasPendingChanges())

      answer(DbUnsubmittedAnswer.DISCARD)
      view.load(100)
      PlatformTestUtil.waitWithEventsDispatching("second page", { view.offset == 100 && view.result.shownRows?.size == 50 }, 20)
    }
    finally {
      com.intellij.ide.util.PropertiesComponent.getInstance().unsetValue("phantom.db.pageSize")
    }
  }

  fun testNoPendingChangesDoesNotAsk() {
    val ds = h2()
    table(ds)
    val panel = run(ds)
    answer(DbUnsubmittedAnswer.CANCEL)

    DbRunner.start(project, myFixture.file.virtualFile, ds, listOf("SELECT * FROM t ORDER BY id"))
    panel.refilter()
    contents()!!.removeContent(contents()!!.getContent(panel)!!, true)
    assertEquals(0, asked.size)
    assertEquals(0, contents()!!.contentCount)
  }

  fun testBadFilterAsksFirstAndCancelKeepsGrid() {
    val ds = h2()
    table(ds)
    val panel = run(ds)
    val grid = panel.shownGrid
    val edits = panel.shownEditing!!
    edit(panel, 1, "z")
    answer(DbUnsubmittedAnswer.CANCEL)
    panel.filterBar!!.where = "id = 1; DROP TABLE t"

    panel.refilter()
    assertEquals(1, asked.size)
    assertSame(grid, panel.shownGrid)
    assertTrue(edits.hasPendingChanges())
  }

  fun testCancelKeepsTabTitleAndBlockedRunAsks() {
    val ds = h2()
    table(ds)
    val panel = run(ds)
    val content = contents()!!.getContent(panel)!!
    val title = content.displayName
    val edits = panel.shownEditing!!
    edit(panel, 1, "z")
    answer(DbUnsubmittedAnswer.CANCEL)

    DbRunner.start(project, myFixture.file.virtualFile, ds, listOf("SELECT id FROM t WHERE id = 2"))
    assertEquals(1, asked.size)
    assertEquals(title, content.displayName)
    assertSame(edits, panel.shownEditing)
    assertTrue(edits.hasPendingChanges())
  }
}
