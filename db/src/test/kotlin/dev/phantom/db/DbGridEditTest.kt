// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db

import com.intellij.database.datagrid.GridRequestSource
import com.intellij.database.datagrid.ModelIndex
import com.intellij.database.datagrid.ModelIndexSet
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.db.core.*
import dev.phantom.db.core.exec.*
import kotlinx.coroutines.runBlocking
import java.sql.Types

class DbGridEditTest : BasePlatformTestCase() {
  companion object {
    init {
      System.setProperty("idea.load.plugins.id", "dev.phantom.db,org.jetbrains.kotlin")
    }
  }

  private var counter = 0

  private fun h2(manualCommit: Boolean = false, readOnly: Boolean = false, url: String? = null): DbDataSource {
    val name = "edit${counter++}"
    val ds = DbDataSource().apply {
      this.name = name
      dbms = Dbms.H2
      this.url = url ?: "jdbc:h2:mem:${name}_${System.nanoTime()};DB_CLOSE_DELAY=-1"
      this.manualCommit = manualCommit
      this.readOnly = readOnly
      user = "sa"
    }
    DbDataSourceService.getInstance(project).save(ds, CharArray(0))
    return ds
  }

  private fun exec(ds: DbDataSource, vararg sql: String): List<DbOutcome> = runBlocking { DbExecutor.execute(project, ds, sql.toList()) }

  private fun rows(ds: DbDataSource, sql: String): DbOutcome.Rows = exec(ds, sql).single() as DbOutcome.Rows

  private fun target(ds: DbDataSource, sql: String): DbEditTarget? = runBlocking { DbEditTargets.resolve(project, ds, rows(ds, sql)) }

  override fun tearDown() {
    try {
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

  fun testSingleTableSelects() {
    assertEquals(DbTableName(null, "t"), SingleTableSelect.parse("SELECT * FROM t"))
    assertEquals(DbTableName(null, "t"), SingleTableSelect.parse("select id, name from t where id > 1 order by id limit 10;"))
    assertEquals(DbTableName(null, "t"), SingleTableSelect.parse("SELECT x.* FROM t x WHERE x.id = 1"))
    assertEquals(DbTableName(null, "t"), SingleTableSelect.parse("-- note\nSELECT * FROM t AS a /* c */"))
    assertEquals(DbTableName("app", "Users", tableQuoted = true), SingleTableSelect.parse("SELECT * FROM app.\"Users\""))
    assertEquals(DbTableName(null, "order"), SingleTableSelect.parse("SELECT * FROM `order` FOR UPDATE").let { it?.copy(tableQuoted = false) })
    for (sql in listOf("SELECT * FROM a JOIN b ON a.id = b.id", "SELECT * FROM a, b", "SELECT * FROM (SELECT 1) s",
                       "SELECT name, COUNT(*) FROM t GROUP BY name", "SELECT DISTINCT name FROM t", "SELECT 1 FROM a UNION SELECT 2 FROM b",
                       "SELECT * FROM t WHERE id IN (SELECT id FROM u)", "UPDATE t SET x = 1", "SELECT 1")) {
      assertNull(sql, SingleTableSelect.parse(sql))
    }
  }

  fun testEditTargetNeedsTheWholePrimaryKey() {
    val ds = h2()
    exec(ds, "CREATE TABLE t (id INT PRIMARY KEY, name VARCHAR(10), note VARCHAR(10))", "INSERT INTO t VALUES (1, 'a', 'x')",
         "CREATE TABLE nokey (name VARCHAR(10))", "CREATE VIEW v AS SELECT * FROM t")
    val all = target(ds, "SELECT * FROM t")!!
    assertEquals("\"T\"", all.tableSql)
    assertEquals(listOf(0), all.keyColumns)
    assertEquals(listOf("\"ID\"", "\"NAME\"", "\"NOTE\""), all.columnSql)

    val some = target(ds, "SELECT name, id, UPPER(note) AS loud FROM t")!!
    assertEquals(listOf(1), some.keyColumns)
    assertTrue(some.isEditable(0))
    assertFalse("an expression is not a table column", some.isEditable(2))

    assertNull("no key in the result", target(ds, "SELECT name FROM t"))
    assertNull("no primary key", target(ds, "SELECT * FROM nokey"))
    assertNull("views are not edited", target(ds, "SELECT * FROM v"))
    assertNull("read-only data source", target(h2(readOnly = true, url = ds.url), "SELECT * FROM t"))
  }

  fun testStatementsAndTemplates() {
    val target = DbEditTargets.build(Dbms.MYSQL, "`", DbTableName("shop", "item"), "shop", "item", listOf("id"), listOf("id", "name", "price"),
                                     listOf(DbColumn("id", Types.INTEGER, "INT", null), DbColumn("name", Types.VARCHAR, "VARCHAR", null),
                                            DbColumn("price", Types.DECIMAL, "DECIMAL", null)))!!
    val update = target.update(arrayOf(7, "pen", 1.5), 1, "it's")
    assertEquals("UPDATE `shop`.`item` SET `name` = ? WHERE `id` = ?", update.sql)
    assertEquals("UPDATE `shop`.`item` SET `name` = 'it''s' WHERE `id` = 7", update.display)
    assertEquals("DELETE FROM `shop`.`item` WHERE `id` = 7", target.delete(arrayOf(7, "pen", 1.5)).display)
    assertEquals("INSERT INTO `shop`.`item` (`id`, `name`, `price`) VALUES (NULL, NULL, NULL);", target.insertTemplate(null))
    assertEquals("INSERT INTO `shop`.`item` (`id`, `name`, `price`) VALUES (7, 'pen', 1.5);", target.insertTemplate(arrayOf(7, "pen", 1.5)))
  }

  private fun immediately(on: Boolean, body: () -> Unit) {
    val was = DbGridSettings.submitImmediately
    DbGridSettings.submitImmediately = on
    try {
      body()
    }
    finally {
      DbGridSettings.submitImmediately = was
    }
  }

  /** As in DataGrip: edits and deletions are shown as changes and run on Submit, then wait in a manual-commit transaction. */
  fun testGridEditsWaitForSubmitThenForCommit() = immediately(false) {
    val ds = h2(manualCommit = true)
    val other = h2(url = ds.url)
    exec(ds, "CREATE TABLE t (id INT PRIMARY KEY, name VARCHAR(10))", "INSERT INTO t VALUES (1, 'a'), (2, 'b'), (3, 'c')", "COMMIT")
    myFixture.configureByText("edit.sql", "SELECT * FROM t ORDER BY id")
    DbRunner.start(project, myFixture.file.virtualFile, ds, listOf("SELECT * FROM t ORDER BY id"))
    val panel = { ToolWindowManager.getInstance(project).getToolWindow(DbResultsToolWindow.ID)?.contentManager?.selectedContent?.component as? DbResultPanel }
    PlatformTestUtil.waitWithEventsDispatching("grid not editable; status: ${panel()?.shownStatus}", { panel()?.shownEditing != null }, 20)
    val edits = panel()!!.shownEditing!!
    val model = edits.hookUp.dataModel
    val shown = edits.hookUp.mutationModel
    val pending = DbPendingChanges.getInstance(project)
    fun cell(r: Int, c: Int) = shown.getValueAt(ModelIndex.forRow(model, r), ModelIndex.forColumn(model, c))
    val source = GridRequestSource(null)

    edits.mutate(source, ModelIndexSet.forRows(model, 0), ModelIndexSet.forColumns(model, 1), "z", true)
    edits.mutate(source, ModelIndexSet.forRows(model, 2), ModelIndexSet.forColumns(model, 1), "y", true)
    edits.deleteRows(source, ModelIndexSet.forRows(model, 1))
    assertTrue(edits.hasPendingChanges())
    assertEquals("z", cell(0, 1))
    assertEquals("loaded value stays until Submit", "a", model.getValueAt(ModelIndex.forRow(model, 0), ModelIndex.forColumn(model, 1)))
    assertEquals(com.intellij.database.datagrid.MutationType.MODIFY, edits.getMutationType(ModelIndex.forRow(model, 0), ModelIndex.forColumn(model, 1)))
    assertTrue(edits.isDeletedRow(ModelIndex.forRow(model, 1)))
    // changed, new and deleted cells are colored by the grid's mutation layer, which asks these edits (the test color scheme has no such colors)
    val colors = panel()!!.shownGrid!!.colorModel
    val layers = colors.javaClass.getDeclaredField("myLayers").apply { isAccessible = true }.get(colors) as Array<*>
    assertTrue(layers.filterIsInstance<com.intellij.database.datagrid.color.MutationsColorLayer>()
                 .any { it.javaClass.getDeclaredField("myMutator").apply { isAccessible = true }.get(it) === edits })
    assertEquals("""
      UPDATE "T" SET "NAME" = 'z' WHERE "ID" = 1;
      UPDATE "T" SET "NAME" = 'y' WHERE "ID" = 3;
      DELETE FROM "T" WHERE "ID" = 2;
    """.trimIndent(), edits.pendingChanges)
    assertEmpty("nothing run before Submit", pending.of(ds.id))

    // Revert Selected drops the third row's change
    edits.revert(source, ModelIndexSet.forRows(model, 2), ModelIndexSet.forColumns(model, 0, 1))
    assertEquals("c", cell(2, 1))
    assertNull(edits.getMutationType(ModelIndex.forRow(model, 2)))

    var submitted: Boolean? = null
    edits.submit(source) { submitted = it }
    PlatformTestUtil.waitWithEventsDispatching("not submitted", { submitted != null && model.rowCount == 2 }, 20)
    assertTrue(submitted!!)
    assertFalse(edits.hasPendingChanges())
    assertEquals(listOf("UPDATE \"T\" SET \"NAME\" = 'z' WHERE \"ID\" = 1", "DELETE FROM \"T\" WHERE \"ID\" = 2"), pending.of(ds.id).map { it.sql })
    assertEquals("z", model.getValueAt(ModelIndex.forRow(model, 0), ModelIndex.forColumn(model, 1)))
    assertEquals("not committed yet", "a", rows(other, "SELECT name FROM t WHERE id = 1").rows.single()[0])

    runBlocking { pending.commit(ds) }
    assertEquals(listOf(listOf<Any?>(1, "z"), listOf<Any?>(3, "c")), rows(other, "SELECT * FROM t ORDER BY id").rows.map { it.toList() })
  }

  /** Add Row and Clone Row add rows in the grid, filled in place and inserted on Submit; columns left empty take their defaults. */
  fun testNewRowsAreInsertedOnSubmit() = immediately(false) {
    val ds = h2()
    exec(ds, "CREATE TABLE t (id INT PRIMARY KEY, name VARCHAR(10), note VARCHAR(10) DEFAULT 'n')", "INSERT INTO t VALUES (1, 'a', 'x')")
    myFixture.configureByText("ins.sql", "SELECT * FROM t")
    DbRunner.start(project, myFixture.file.virtualFile, ds, listOf("SELECT * FROM t"))
    val panel = { ToolWindowManager.getInstance(project).getToolWindow(DbResultsToolWindow.ID)?.contentManager?.selectedContent?.component as? DbResultPanel }
    PlatformTestUtil.waitWithEventsDispatching("grid not editable; status: ${panel()?.shownStatus}", { panel()?.shownEditing != null }, 20)
    val edits = panel()!!.shownEditing!!
    val model = edits.hookUp.dataModel
    val source = GridRequestSource(null)

    edits.insertRows(source, 1)
    assertEquals(2, model.rowCount)
    assertTrue(edits.isInsertedRow(ModelIndex.forRow(model, 1)))
    assertEquals(com.intellij.database.datagrid.MutationType.INSERT, edits.getMutationType(ModelIndex.forRow(model, 1)))
    edits.mutate(source, ModelIndexSet.forRows(model, 1), ModelIndexSet.forColumns(model, 0), 2, true)
    edits.mutate(source, ModelIndexSet.forRows(model, 1), ModelIndexSet.forColumns(model, 1), "b", true)
    edits.cloneRow(source, ModelIndex.forRow(model, 0))
    edits.mutate(source, ModelIndexSet.forRows(model, 2), ModelIndexSet.forColumns(model, 0), 3, true)
    edits.insertRows(source, 1)
    // a new row deleted before Submit just goes away; Revert does the same
    edits.deleteRows(source, ModelIndexSet.forRows(model, 3))
    assertEquals(3, model.rowCount)
    assertEquals("""
      INSERT INTO "T" ("ID", "NAME") VALUES (2, 'b');
      INSERT INTO "T" ("ID", "NAME", "NOTE") VALUES (3, 'a', 'x');
    """.trimIndent(), edits.pendingChanges)
    assertEmpty(rows(ds, "SELECT * FROM t WHERE id > 1").rows)

    var submitted: Boolean? = null
    edits.submit(source) { submitted = it }
    PlatformTestUtil.waitWithEventsDispatching("not submitted", { submitted != null }, 20)
    assertTrue(submitted!!)
    assertFalse(edits.hasPendingChanges())
    assertFalse(edits.isInsertedRow(ModelIndex.forRow(model, 1)))
    assertEquals(listOf(listOf<Any?>(1, "a", "x"), listOf<Any?>(2, "b", "n"), listOf<Any?>(3, "a", "x")),
                 rows(ds, "SELECT * FROM t ORDER BY id").rows.map { it.toList() })
    assertEquals("the default the database put there is shown", "n", model.getValueAt(ModelIndex.forRow(model, 1), ModelIndex.forColumn(model, 2)))

    edits.insertRows(source, 1)
    edits.revert(source, ModelIndexSet.forRows(model, 3), ModelIndexSet.forColumns(model, 0))
    assertEquals(3, model.rowCount)
  }

  /** A new row without its generated key shows the key the database made, so it can be edited again. */
  fun testNewRowShowsItsGeneratedKey() = immediately(false) {
    val ds = h2()
    exec(ds, "CREATE TABLE t (id INT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(10), status VARCHAR(10) DEFAULT 'active')", "INSERT INTO t (name) VALUES ('a')")
    myFixture.configureByText("gen.sql", "SELECT * FROM t")
    DbRunner.start(project, myFixture.file.virtualFile, ds, listOf("SELECT * FROM t"))
    val panel = { ToolWindowManager.getInstance(project).getToolWindow(DbResultsToolWindow.ID)?.contentManager?.selectedContent?.component as? DbResultPanel }
    PlatformTestUtil.waitWithEventsDispatching("grid not editable; status: ${panel()?.shownStatus}", { panel()?.shownEditing != null }, 20)
    val edits = panel()!!.shownEditing!!
    val model = edits.hookUp.dataModel
    val source = GridRequestSource(null)
    fun cell(c: Int) = model.getValueAt(ModelIndex.forRow(model, 1), ModelIndex.forColumn(model, c))

    edits.insertRows(source, 1)
    edits.mutate(source, ModelIndexSet.forRows(model, 1), ModelIndexSet.forColumns(model, 1), "b", true)
    var submitted: Boolean? = null
    edits.submit(source) { submitted = it }
    PlatformTestUtil.waitWithEventsDispatching("not submitted", { submitted != null }, 20)
    assertTrue(submitted!!)
    assertEquals(listOf<Any?>(2, "b", "active"), (0..2).map { cell(it) })

    edits.mutate(source, ModelIndexSet.forRows(model, 1), ModelIndexSet.forColumns(model, 1), "c", true)
    submitted = null
    edits.submit(source) { submitted = it }
    PlatformTestUtil.waitWithEventsDispatching("not submitted", { submitted != null }, 20)
    assertEquals(listOf<Any?>("a", "c"), rows(ds, "SELECT name FROM t ORDER BY id").rows.map { it[0] })
  }

  /** Submit Changes Immediately: each edit runs as it is made. */
  fun testGridEditsGoToTheTableAndWaitForCommit() = immediately(true) {
    val ds = h2(manualCommit = true)
    val other = h2(url = ds.url)
    exec(ds, "CREATE TABLE t (id INT PRIMARY KEY, name VARCHAR(10))", "INSERT INTO t VALUES (1, 'a'), (2, 'b')", "COMMIT")
    myFixture.configureByText("edit.sql", "SELECT * FROM t ORDER BY id")
    DbRunner.start(project, myFixture.file.virtualFile, ds, listOf("SELECT * FROM t ORDER BY id"))
    val panel = { ToolWindowManager.getInstance(project).getToolWindow(DbResultsToolWindow.ID)?.contentManager?.selectedContent?.component as? DbResultPanel }
    PlatformTestUtil.waitWithEventsDispatching("grid not editable; status: ${panel()?.shownStatus}", { panel()?.shownEditing != null }, 20)
    val edits = panel()!!.shownEditing!!
    val model = edits.hookUp.dataModel
    val pending = DbPendingChanges.getInstance(project)

    edits.mutate(GridRequestSource(null), ModelIndexSet.forRows(model, 0), ModelIndexSet.forColumns(model, 1), "z", true)
    PlatformTestUtil.waitWithEventsDispatching("update not run", { pending.of(ds.id).size == 1 }, 20)
    assertEquals("UPDATE \"T\" SET \"NAME\" = 'z' WHERE \"ID\" = 1", pending.of(ds.id).single().sql)
    PlatformTestUtil.waitWithEventsDispatching("grid not updated", { model.getValueAt(ModelIndex.forRow(model, 0), ModelIndex.forColumn(model, 1)) == "z" }, 20)
    assertEquals("not committed yet", "a", rows(other, "SELECT name FROM t WHERE id = 1").rows.single()[0])

    edits.deleteRows(GridRequestSource(null), ModelIndexSet.forRows(model, 1))
    PlatformTestUtil.waitWithEventsDispatching("delete not run", { pending.of(ds.id).size == 2 && model.rowCount == 1 }, 20)
    assertEquals("DELETE FROM \"T\" WHERE \"ID\" = 2", pending.of(ds.id)[1].sql)

    runBlocking { pending.commit(ds) }
    assertEquals(listOf(listOf<Any?>(1, "z")), rows(other, "SELECT * FROM t ORDER BY id").rows.map { it.toList() })
  }

  /**
   * East of UTC a TIMESTAMP showed hours early and a DATE the day before: the grid reads java.sql values as UTC wall clock.
   * Shown as stored, and a value the grid parses (UTC wall clock) is written as typed.
   */
  fun testDatesAndTimesShowAndSaveAsStored() = immediately(true) {
    val zone = java.util.TimeZone.getDefault()
    java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Seoul"))
    try {
      val ds = h2()
      exec(ds, "CREATE TABLE t (id INT PRIMARY KEY, ts TIMESTAMP, d DATE, tm TIME)",
           "INSERT INTO t VALUES (1, TIMESTAMP '2026-10-26 09:49:28', DATE '2000-01-15', TIME '13:45:00')")
      myFixture.configureByText("time.sql", "SELECT * FROM t")
      DbRunner.start(project, myFixture.file.virtualFile, ds, listOf("SELECT * FROM t"))
      val panel = { ToolWindowManager.getInstance(project).getToolWindow(DbResultsToolWindow.ID)?.contentManager?.selectedContent?.component as? DbResultPanel }
      PlatformTestUtil.waitWithEventsDispatching("grid not editable; status: ${panel()?.shownStatus}", { panel()?.shownEditing != null }, 20)
      val grid = panel()!!.shownGrid!!
      val model = panel()!!.shownEditing!!.hookUp.dataModel
      fun shown(column: Int): String {
        val c = ModelIndex.forColumn(model, column)
        return grid.objectFormatter.objectToString(model.getValueAt(ModelIndex.forRow(model, 0), c), model.getColumn(c), grid.getFormatterConfig(c)!!)!!
      }
      assertEquals("2026-10-26 09:49:28", shown(1))
      assertEquals("2000-01-15", shown(2))
      assertEquals("13:45:00", shown(3))

      val edits = panel()!!.shownEditing!!
      val typed = java.sql.Timestamp.from(java.time.Instant.parse("2026-10-27T08:00:00Z"))
      edits.mutate(GridRequestSource(null), ModelIndexSet.forRows(model, 0), ModelIndexSet.forColumns(model, 1), typed, true)
      edits.mutate(GridRequestSource(null), ModelIndexSet.forRows(model, 0), ModelIndexSet.forColumns(model, 2),
                   java.sql.Date(java.time.LocalDate.of(2001, 2, 3).toEpochDay() * 86_400_000L), true)
      PlatformTestUtil.waitWithEventsDispatching("not saved", {
        shown(2) == "2001-02-03" &&
        rows(ds, "SELECT CAST(ts AS VARCHAR), CAST(d AS VARCHAR) FROM t").rows.single().toList() == listOf<Any?>("2026-10-27 08:00:00", "2001-02-03")
      }, 20)
      assertEquals("2026-10-27 08:00:00", shown(1))
      assertEquals(listOf<Any?>(java.sql.Timestamp.valueOf("2026-10-27 08:00:00"), java.sql.Date.valueOf("2001-02-03")),
                   DbGridTime.fromGrid(model.getValueAt(ModelIndex.forRow(model, 0), ModelIndex.forColumn(model, 1))).let { listOf(it, DbGridTime.fromGrid(model.getValueAt(ModelIndex.forRow(model, 0), ModelIndex.forColumn(model, 2)))) })
    }
    finally {
      java.util.TimeZone.setDefault(zone)
    }
  }

  fun testEditOfAVanishedRowIsReported() {
    val ds = h2()
    exec(ds, "CREATE TABLE t (id INT PRIMARY KEY, name VARCHAR(10))", "INSERT INTO t VALUES (1, 'a')")
    val target = target(ds, "SELECT * FROM t")!!
    exec(ds, "DELETE FROM t")
    val outcome = runBlocking { DbExecutor.execute(project, ds, target.update(arrayOf(1, "a"), 1, "b")) }
    assertEquals(0L, (outcome as DbOutcome.Updated).count)
  }

  /** Set NULL and Set DEFAULT (Ctrl+Alt+N / D): an UPDATE by key, and the default read back into the cell. */
  fun testSetNullAndDefault() = immediately(true) {
    val ds = h2()
    exec(ds, "CREATE TABLE t (id INT PRIMARY KEY, name VARCHAR(10) DEFAULT 'dflt')", "INSERT INTO t VALUES (1, 'a')")
    myFixture.configureByText("set.sql", "SELECT * FROM t")
    DbRunner.start(project, myFixture.file.virtualFile, ds, listOf("SELECT * FROM t"))
    val panel = { ToolWindowManager.getInstance(project).getToolWindow(DbResultsToolWindow.ID)?.contentManager?.selectedContent?.component as? DbResultPanel }
    PlatformTestUtil.waitWithEventsDispatching("grid not editable; status: ${panel()?.shownStatus}", { panel()?.shownEditing != null }, 20)
    val edits = panel()!!.shownEditing!!
    val model = edits.hookUp.dataModel
    fun cell() = model.getValueAt(ModelIndex.forRow(model, 0), ModelIndex.forColumn(model, 1))

    edits.mutate(GridRequestSource(null), ModelIndexSet.forRows(model, 0), ModelIndexSet.forColumns(model, 1), null, true)
    PlatformTestUtil.waitWithEventsDispatching("not set to NULL", { rows(ds, "SELECT name FROM t").rows.single()[0] == null && cell() == null }, 20)

    edits.setDefault(GridRequestSource(null), ModelIndexSet.forRows(model, 0), ModelIndexSet.forColumns(model, 1))
    PlatformTestUtil.waitWithEventsDispatching("default not read back", { cell() == "dflt" }, 20)
    assertEquals("dflt", rows(ds, "SELECT name FROM t").rows.single()[0])
    // the key column is not editable: nothing to set
    edits.setDefault(GridRequestSource(null), ModelIndexSet.forRows(model, 0), ModelIndexSet.forColumns(model, 0))
    assertEquals(1, rows(ds, "SELECT id FROM t").rows.single()[0])
  }

  fun testSqlGenerator() {
    val table = DbTableRef("ds", null, "PUBLIC", "ORDERS", "TABLE")
    val columns = listOf(DbColumnInfo("ID", "INT", false, true), DbColumnInfo("NAME", "VARCHAR", true, false))
    val text = DbSqlGenerator.text(Dbms.H2, table, columns, "CREATE TABLE ORDERS (ID INT PRIMARY KEY, NAME VARCHAR(10));")
    assertEquals("""
      CREATE TABLE ORDERS (ID INT PRIMARY KEY, NAME VARCHAR(10));

      SELECT ID,
             NAME
      FROM PUBLIC.ORDERS;

      INSERT INTO PUBLIC.ORDERS (ID, NAME)
      VALUES (?, ?);

      UPDATE PUBLIC.ORDERS
      SET NAME = ?
      WHERE ID = ?;

      DELETE FROM PUBLIC.ORDERS
      WHERE ID = ?;

    """.trimIndent(), text)
    val view = DbSqlGenerator.text(Dbms.H2, DbTableRef("ds", null, "PUBLIC", "V", "VIEW"), columns, null)
    assertFalse(view, view.contains("INSERT"))
  }
}
