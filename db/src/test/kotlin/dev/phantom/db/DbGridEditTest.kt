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
      System.setProperty("idea.load.plugins.id", "dev.phantom.db")
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

  fun testGridEditsGoToTheTableAndWaitForCommit() {
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

  fun testEditOfAVanishedRowIsReported() {
    val ds = h2()
    exec(ds, "CREATE TABLE t (id INT PRIMARY KEY, name VARCHAR(10))", "INSERT INTO t VALUES (1, 'a')")
    val target = target(ds, "SELECT * FROM t")!!
    exec(ds, "DELETE FROM t")
    val outcome = runBlocking { DbExecutor.execute(project, ds, target.update(arrayOf(1, "a"), 1, "b")) }
    assertEquals(0L, (outcome as DbOutcome.Updated).count)
  }
}
