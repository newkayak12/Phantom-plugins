// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db

import com.intellij.database.datagrid.FormatterCreatorProvider
import com.intellij.database.datagrid.GridRequestSource
import com.intellij.database.datagrid.ModelIndex
import com.intellij.database.datagrid.ModelIndexSet
import com.intellij.database.extractors.FormatterCreator
import com.intellij.database.run.ui.grid.editors.FormatsCache
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.db.core.*
import dev.phantom.db.core.exec.*
import kotlinx.coroutines.runBlocking
import dev.phantom.db.core.exec.DbGridTime
import java.sql.Timestamp
import java.sql.Types
import java.time.LocalDateTime
import java.util.TimeZone

/** The grid shows the fraction of a TIMESTAMP up to the column's scale, trimmed, and shows a java.time value without the 'T'. */
class DbTimestampDisplayTest : BasePlatformTestCase() {
  companion object {
    init {
      System.setProperty("idea.load.plugins.id", "dev.phantom.db,org.jetbrains.kotlin")
    }
  }

  private var counter = 0
  private lateinit var zone: TimeZone

  override fun setUp() {
    super.setUp()
    zone = TimeZone.getDefault()
    TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"))
  }

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
      TimeZone.setDefault(zone)
      super.tearDown()
    }
  }

  private fun h2(): DbDataSource {
    val name = "ts${counter++}"
    val ds = DbDataSource().apply {
      this.name = name
      dbms = Dbms.H2
      // H2 keeps the JVM zone it first saw (UTC on CI, from earlier tests), so the session is given the one set up here
      url = "jdbc:h2:mem:${name}_${System.nanoTime()};DB_CLOSE_DELAY=-1;TIME ZONE=Asia/Seoul"
      user = "sa"
    }
    DbDataSourceService.getInstance(project).save(ds, CharArray(0))
    return ds
  }

  private fun exec(ds: DbDataSource, vararg sql: String): List<DbOutcome> = runBlocking { DbExecutor.execute(project, ds, sql.toList()) }

  private fun rows(ds: DbDataSource, sql: String) = exec(ds, sql).single() as DbOutcome.Rows

  private fun classOf(type: Int) = when (type) {
    Types.TIMESTAMP -> "java.sql.Timestamp"
    Types.TIME -> "java.sql.Time"
    Types.DATE -> "java.sql.Date"
    else -> null
  }

  private fun panelOf(type: Int, scale: Int, values: List<Any?>, precision: Int = 0): DbResultPanel {
    val panel = DbResultPanel(project)
    Disposer.register(testRootDisposable, panel)
    panel.showOutcomes(listOf(DbOutcome.Rows("SELECT v FROM t", listOf(DbColumn("v", type, null, classOf(type), precision, scale)), values.map { arrayOf(it) }, false, 1)))
    return panel
  }

  /** [values] shown in one column of [type] and [scale] of a Phantom grid, as text. */
  private fun shown(type: Int, scale: Int, vararg values: Any?, precision: Int = 0): List<String?> {
    val grid = panelOf(type, scale, values.toList(), precision).shownGrid!!
    val model = grid.dataHookup.dataModel
    val c = ModelIndex.forColumn(model, 0)
    return values.indices.map { r -> grid.objectFormatter.objectToString(model.getValueAt(ModelIndex.forRow(model, r), c), model.getColumn(c), grid.getFormatterConfig(c)!!) }
  }

  private fun scaleOf(type: Int, scale: Int): Int {
    val grid = panelOf(type, scale, listOf<Any?>(null)).shownGrid!!
    return DbFormatterCreator().calculateScale(grid.dataHookup.dataModel.getColumn(ModelIndex.forColumn(grid.dataHookup.dataModel, 0)), null)
  }

  private fun ts(text: String) = Timestamp.valueOf(text)

  fun testMillisecondTimestampShowsFraction() {
    assertEquals(listOf("2026-10-26 09:49:28.123"), shown(Types.TIMESTAMP, 3, ts("2026-10-26 09:49:28.123")))
  }

  fun testMicrosecondTimestampShowsFraction() {
    assertEquals(listOf("2026-10-26 09:49:28.123456"), shown(Types.TIMESTAMP, 6, ts("2026-10-26 09:49:28.123456")))
  }

  /** MySQL Connector/J gives a TIMESTAMP(6) scale 0 and precision 26 (19 + the dot + 6 digits). */
  fun testScaleFromPrecisionWhenDriverGivesNone() {
    assertEquals(listOf("2026-10-26 09:49:28.123456"), shown(Types.TIMESTAMP, 0, ts("2026-10-26 09:49:28.123456"), precision = 26))
    assertEquals(listOf("2026-10-26 09:49:28"), shown(Types.TIMESTAMP, 0, ts("2026-10-26 09:49:28.5"), precision = 19))
  }

  fun testZeroFractionShowsNoDigits() {
    assertEquals(listOf("2026-10-26 09:49:28", "2026-10-26 09:49:28"),
                 shown(Types.TIMESTAMP, 6, ts("2026-10-26 09:49:28"), ts("2026-10-26 09:49:28.0")))
  }

  fun testTrailingZerosTrimmed() {
    assertEquals(listOf("2026-10-26 09:49:28.12", "2026-10-26 09:49:28.1"),
                 shown(Types.TIMESTAMP, 3, ts("2026-10-26 09:49:28.120"), ts("2026-10-26 09:49:28.100")))
  }

  fun testParsesZeroToSixFractionDigits() {
    val grid = panelOf(Types.TIMESTAMP, 6, listOf<Any?>(null)).shownGrid!!
    val column = grid.dataHookup.dataModel.getColumn(ModelIndex.forColumn(grid.dataHookup.dataModel, 0))!!
    val formatter = DbFormatterCreator().create(FormatterCreator.getTimestampKey(column, null, FormatsCache()))!!
    fun parsed(text: String) = formatter.parse(text) as Timestamp
    val whole = parsed("2026-10-26 09:49:28")
    assertEquals(whole.time + 100, parsed("2026-10-26 09:49:28.1").time)
    for (digits in 0..6) {
      val text = "2026-10-26 09:49:28" + if (digits == 0) "" else "." + "123456".take(digits)
      assertEquals(text, formatter.format(parsed(text)))
    }
    assertEquals(123456000, parsed("2026-10-26 09:49:28.123456").nanos)
  }

  fun testTimeColumnsUnchanged() {
    assertEquals(listOf("13:45:00"), shown(Types.TIME, 3, java.sql.Time.valueOf("13:45:00")))
    for (type in listOf(Types.TIME, Types.TIME_WITH_TIMEZONE, Types.TIMESTAMP_WITH_TIMEZONE, Types.DATE, Types.VARCHAR)) {
      assertEquals("type $type", 0, scaleOf(type, 3))
    }
    assertEquals(3, scaleOf(Types.TIMESTAMP, 3))
    assertEquals("capped", 6, scaleOf(Types.TIMESTAMP, 99))
    assertEquals("nonsense", 0, scaleOf(Types.TIMESTAMP, -1))
  }

  fun testScaleReadFromMetadata() {
    val ds = h2()
    exec(ds, "CREATE TABLE t (a TIMESTAMP(3), b TIMESTAMP, c TIMESTAMP(0), d INT)")
    assertEquals(listOf(3, 6, 0), rows(ds, "SELECT * FROM t").columns.take(3).map { it.scale })
  }

  fun testLocalDateTimeShownWithoutT() {
    assertEquals(listOf("2026-10-26 09:49:28.123", "2026-10-26 09:49:28"),
                 shown(Types.TIMESTAMP, 3, LocalDateTime.of(2026, 10, 26, 9, 49, 28, 123_000_000), LocalDateTime.of(2026, 10, 26, 9, 49, 28)))
  }

  /** MySQL's DATETIME comes as a LocalDateTime: in the grid it is a TIMESTAMP (so the calendar opens on it), out of it the same time. */
  fun testLocalDateTimeIsATimestampInTheGrid() {
    val value = LocalDateTime.of(2026, 10, 26, 9, 49, 28, 123_000_000)
    val inGrid = DbGridTime.toGrid(value)
    assertTrue(inGrid is Timestamp)
    assertEquals(Timestamp.valueOf(value), DbGridTime.fromGrid(inGrid))
  }

  fun testFractionalEditRoundTrips() {
    val ds = h2()
    exec(ds, "CREATE TABLE t (id INT PRIMARY KEY, ts TIMESTAMP(3))", "INSERT INTO t VALUES (1, TIMESTAMP '2026-10-26 09:49:28.123')")
    val was = DbGridSettings.submitImmediately
    DbGridSettings.submitImmediately = true
    try {
      myFixture.configureByText("frac.sql", "SELECT * FROM t")
      DbRunner.start(project, myFixture.file.virtualFile, ds, listOf("SELECT * FROM t"))
      val panel = { ToolWindowManager.getInstance(project).getToolWindow(DbResultsToolWindow.ID)?.contentManager?.selectedContent?.component as? DbResultPanel }
      PlatformTestUtil.waitWithEventsDispatching("grid not editable; status: ${panel()?.shownStatus}", { panel()?.shownEditing != null }, 20)
      val grid = panel()!!.shownGrid!!
      val model = panel()!!.shownEditing!!.hookUp.dataModel
      val c = ModelIndex.forColumn(model, 1)
      fun text() = grid.objectFormatter.objectToString(model.getValueAt(ModelIndex.forRow(model, 0), c), model.getColumn(c), grid.getFormatterConfig(c)!!)
      assertEquals("2026-10-26 09:49:28.123", text())
      val typed = Timestamp.from(java.time.Instant.parse("2026-10-27T08:00:00.456Z"))
      panel()!!.shownEditing!!.mutate(GridRequestSource(null), ModelIndexSet.forRows(model, 0), ModelIndexSet.forColumns(model, 1), typed, true)
      PlatformTestUtil.waitWithEventsDispatching("not saved", {
        text() == "2026-10-27 08:00:00.456" && rows(ds, "SELECT CAST(ts AS VARCHAR) FROM t").rows.single().single() == "2026-10-27 08:00:00.456"
      }, 20)
      assertEquals("2026-10-27 08:00:00.456", text())
    }
    finally {
      DbGridSettings.submitImmediately = was
    }
  }

  fun testOtherGridsKeepDefaultFormatter() {
    val target = object : FormatterCreatorProvider.FormatterCreatorTarget {}
    assertFalse(FormatterCreatorProvider.getCreator(target) is DbFormatterCreator)
    assertFalse(DbFormatterCreatorProvider().isApplicable(target))
    assertTrue(FormatterCreatorProvider.get(panelOf(Types.TIMESTAMP, 3, listOf<Any?>(null)).shownGrid!!) is DbFormatterCreatorProvider)
  }
}
