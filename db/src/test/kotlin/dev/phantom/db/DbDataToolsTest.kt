// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.db.core.*
import dev.phantom.db.core.exec.*
import dev.phantom.db.core.ui.DbTableDataView
import kotlinx.coroutines.runBlocking

class DbDataToolsTest : BasePlatformTestCase() {
  companion object {
    init {
      System.setProperty("idea.load.plugins.id", "dev.phantom.db,org.jetbrains.kotlin")
    }
  }

  private var counter = 0

  private fun h2(manualCommit: Boolean = false, url: String? = null): DbDataSource {
    val name = "data${counter++}"
    val ds = DbDataSource().apply {
      this.name = name
      dbms = Dbms.H2
      this.url = url ?: "jdbc:h2:mem:${name}_${System.nanoTime()};DB_CLOSE_DELAY=-1"
      this.manualCommit = manualCommit
      user = "sa"
    }
    DbDataSourceService.getInstance(project).save(ds, CharArray(0))
    return ds
  }

  private fun exec(ds: DbDataSource, vararg sql: String): List<DbOutcome> = runBlocking { DbExecutor.execute(project, ds, sql.toList()) }

  private fun rows(ds: DbDataSource, sql: String): List<List<Any?>> =
    (exec(ds, sql).single() as? DbOutcome.Rows ?: error((exec(ds, sql).single() as DbOutcome.Failed).message)).rows.map { it.toList() }

  private fun table(ds: DbDataSource, name: String) = DbTableRef(ds.id, ds.name.uppercase().let { null }, "PUBLIC", name, "TABLE").let {
    runBlocking { DbMetadataCache.getInstance(project).tables(ds, DbMetadataCache.getInstance(project).schemas(ds).first { s -> s.name == "PUBLIC" }) }
      .first { t -> t.name == name }
  }

  override fun tearDown() {
    try {
      TestDialogManager.setTestDialog(TestDialog.DEFAULT)
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

  fun testPagingAndFilterSql() {
    val t = DbTableRef("x", "shop", null, "order", "TABLE")
    assertEquals("SELECT * FROM shop.`order` WHERE status = 'OPEN' ORDER BY id DESC LIMIT 501 OFFSET 1000",
                 DbTableQuery.select(Dbms.MYSQL, t, "where status = 'OPEN';", "ORDER  BY id DESC", 501, 1000))
    val pg = DbTableRef("x", null, "public", "users", "TABLE")
    assertEquals("SELECT * FROM public.users LIMIT 101", DbTableQuery.select(Dbms.POSTGRESQL, pg, " ", "", 101, 0))
    assertEquals("SELECT COUNT(*) FROM public.users WHERE age > 3", DbTableQuery.count(Dbms.POSTGRESQL, pg, "age > 3"))
    // a filter word that starts like the keyword is not cut
    assertEquals("whereabouts = 1", DbTableQuery.clause("whereabouts = 1", "WHERE"))
    assertEquals(0, DbTableQuery.lastPageOffset(0, 500))
    assertEquals(0, DbTableQuery.lastPageOffset(500, 500))
    assertEquals(500, DbTableQuery.lastPageOffset(501, 500))
    // the edit target still finds the table in a paged, filtered select
    assertEquals(DbTableName("shop", "order", tableQuoted = true),
                 SingleTableSelect.parse(DbTableQuery.select(Dbms.MYSQL, t, "a = 1", "id", 11, 10)))
  }

  fun testAlterStatementsPerDialect() {
    val t = DbTableRef("x", "shop", "public", "users", "TABLE")
    val before = listOf(DbAlter.ColumnDef("id", "id", "BIGINT", false, null),
                        DbAlter.ColumnDef("name", "name", "VARCHAR(40)", true, null),
                        DbAlter.ColumnDef("old", "old", "INT", true, null),
                        DbAlter.ColumnDef("state", "state", "VARCHAR(10)", true, "'NEW'"))
    val after = listOf(before[0],
                       DbAlter.ColumnDef("name", "full_name", "VARCHAR(80)", false, null),
                       before[3].copy(default = null),
                       DbAlter.ColumnDef(null, "email", "VARCHAR(100)", false, "''"))
    val idxBefore = listOf(DbAlter.IndexDef("idx_old", "idx_old", listOf("old"), false), DbAlter.IndexDef("ux_name", "ux_name", listOf("name"), true))
    val idxAfter = listOf(DbAlter.IndexDef("ux_name", "ux_name", listOf("full_name"), true), DbAlter.IndexDef(null, "idx_email", listOf("email"), false))
    assertEquals(listOf(
      "DROP INDEX `idx_old` ON shop.users".replace("`idx_old`", "idx_old"),
      "DROP INDEX ux_name ON shop.users",
      "ALTER TABLE shop.users DROP COLUMN old",
      "ALTER TABLE shop.users RENAME COLUMN name TO full_name",
      "ALTER TABLE shop.users MODIFY COLUMN full_name VARCHAR(80) NOT NULL",
      "ALTER TABLE shop.users MODIFY COLUMN state VARCHAR(10) NULL",
      "ALTER TABLE shop.users ADD COLUMN email VARCHAR(100) DEFAULT '' NOT NULL",
      "CREATE UNIQUE INDEX ux_name ON shop.users (full_name)",
      "CREATE INDEX idx_email ON shop.users (email)",
    ), DbAlter.statements(Dbms.MYSQL, t, before, after, idxBefore, idxAfter))
    assertEquals(listOf(
      "DROP INDEX public.idx_old",
      "DROP INDEX public.ux_name",
      "ALTER TABLE public.users DROP COLUMN old",
      "ALTER TABLE public.users RENAME COLUMN name TO full_name",
      "ALTER TABLE public.users ALTER COLUMN full_name TYPE VARCHAR(80)",
      "ALTER TABLE public.users ALTER COLUMN full_name SET NOT NULL",
      "ALTER TABLE public.users ALTER COLUMN state DROP DEFAULT",
      "ALTER TABLE public.users ADD COLUMN email VARCHAR(100) DEFAULT '' NOT NULL",
      "CREATE UNIQUE INDEX ux_name ON public.users (full_name)",
      "CREATE INDEX idx_email ON public.users (email)",
    ), DbAlter.statements(Dbms.POSTGRESQL, t, before, after, idxBefore, idxAfter))
    assertContainsElements(DbAlter.statements(Dbms.H2, t, before, after),
                           "ALTER TABLE public.users ALTER COLUMN name RENAME TO full_name",
                           "ALTER TABLE public.users ALTER COLUMN full_name SET DATA TYPE VARCHAR(80)")
    assertEmpty(DbAlter.statements(Dbms.H2, t, before, before.map { it.copy(type = it.type.lowercase()) }, idxBefore, idxBefore))
    // MySQL reports literal defaults without quotes
    assertEquals("'NEW'", DbObjectCache.defaultExpression(Dbms.MYSQL, "NEW"))
    assertEquals("0", DbObjectCache.defaultExpression(Dbms.MYSQL, "0"))
    assertEquals("CURRENT_TIMESTAMP(6)", DbObjectCache.defaultExpression(Dbms.MYSQL, "CURRENT_TIMESTAMP(6)"))
    assertEquals("NEW", DbObjectCache.defaultExpression(Dbms.POSTGRESQL, "NEW"))
  }

  fun testParametersSkipStringsCommentsAndUserVariables() {
    val sql = "SELECT * FROM \${tbl} WHERE a = :id AND b = ? AND c = '?' AND d = ':no' -- :x ?\n AND e = @v AND f::text = ? /* :y */ AND g = :id"
    assertEquals(listOf("tbl", "id", "?1", "?2", "id"), DbParameters.find(sql).map { it.name })
    assertEquals(listOf("tbl", "id", "?1", "?2", "?3"), DbParameters.names(listOf(sql, "SELECT ?")))
    val values = mapOf("tbl" to "users", "id" to "42", "?1" to "'kim'", "?2" to "NULL", "?3" to "7")
    assertEquals(listOf("SELECT * FROM users WHERE a = 42 AND b = 'kim' AND c = '?' AND d = ':no' -- :x ?\n AND e = @v AND f::text = NULL /* :y */ AND g = 42",
                        "SELECT 7"),
                 DbParameters.substitute(listOf(sql, "SELECT ?"), values))
    assertEmpty(DbParameters.find("SELECT '\${x}', \"a:b\" FROM t -- \${y}"))
  }

  fun testExplainPerDialect() {
    assertEquals("EXPLAIN SELECT 1", DbExplain.sql(Dbms.MYSQL, "SELECT 1;", analyze = false))
    assertEquals("EXPLAIN ANALYZE SELECT 1", DbExplain.sql(Dbms.MYSQL, "SELECT 1", analyze = true))
    assertEquals("ANALYZE SELECT 1", DbExplain.sql(Dbms.MARIADB, "SELECT 1", analyze = true))
    assertEquals("EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) SELECT 1", DbExplain.sql(Dbms.POSTGRESQL, "SELECT 1", analyze = true))
    val ds = h2()
    exec(ds, "CREATE TABLE t (id INT PRIMARY KEY)")
    val plan = rows(ds, DbExplain.sql(Dbms.H2, "SELECT * FROM t WHERE id = 1", analyze = false)!!)
    assertTrue(plan.toString(), plan.single().single().toString().contains("PRIMARY_KEY", ignoreCase = true))
    assertNotEmpty(rows(ds, DbExplain.sql(Dbms.H2, "SELECT * FROM t", analyze = true)!!))
  }

  fun testKeysIndexesDdlOnH2() {
    val ds = h2()
    exec(ds,
         "CREATE TABLE guest (id BIGINT PRIMARY KEY, email VARCHAR(80) NOT NULL, status VARCHAR(10) DEFAULT 'NEW')",
         "CREATE UNIQUE INDEX ux_guest_email ON guest (email)",
         "CREATE TABLE booking (id BIGINT, line INT, guest_id BIGINT, price DECIMAL(10,2), CONSTRAINT pk_booking PRIMARY KEY (id, line), " +
         "CONSTRAINT fk_booking_guest FOREIGN KEY (guest_id) REFERENCES guest (id))",
         "CREATE INDEX idx_booking_price ON booking (price)",
         "CREATE VIEW cheap AS SELECT id FROM booking WHERE price < 10")
    val objects = DbObjectCache.getInstance(project)
    val guest = table(ds, "GUEST")
    val booking = table(ds, "BOOKING")
    runBlocking {
      assertEquals(listOf("ID", "LINE"), objects.primaryKey(ds, booking).columns)
      val fk = objects.foreignKeys(ds, booking).single()
      assertEquals(listOf("GUEST_ID"), fk.columns)
      assertEquals("GUEST", fk.refTable.name)
      assertEquals(listOf("ID"), fk.refColumns)
      assertEquals(listOf("BOOKING"), objects.referencingKeys(ds, guest).map { it.table.name })
      assertEquals(listOf("UX_GUEST_EMAIL"), objects.indexes(ds, guest).map { it.name })
      assertTrue(objects.indexes(ds, guest).single().unique)
      assertContainsElements(objects.indexes(ds, booking).map { it.name }, "IDX_BOOKING_PRICE")
      assertEquals("'NEW'", objects.columnDetails(ds, guest).first { it.name == "STATUS" }.default)
      assertEquals("DECIMAL(10,2)", objects.columnDetails(ds, booking).first { it.name == "PRICE" }.typeName.replace("NUMERIC", "DECIMAL"))

      // the generated DDL recreates the same tables in an empty database
      val ddlGuest = DbDdl.ddl(project, ds, DbDdl.Target.Table(guest))
      val ddlBooking = DbDdl.ddl(project, ds, DbDdl.Target.Table(booking))
      assertTrue(ddlBooking, ddlBooking.contains("FOREIGN KEY (GUEST_ID) REFERENCES PUBLIC.GUEST (ID)"))
      val copy = h2()
      val created = exec(copy, *(DbStatementsSplit(ddlGuest) + DbStatementsSplit(ddlBooking)).toTypedArray())
      assertTrue(created.joinToString { (it as? DbOutcome.Failed)?.message ?: "ok" }, created.none { it is DbOutcome.Failed })
      assertEquals(listOf("GUEST_ID"), objects.foreignKeys(copy, table(copy, "BOOKING")).single().columns)

      val view = DbDdl.ddl(project, ds, DbDdl.Target.Table(table(ds, "CHEAP")))
      assertTrue(view, view.startsWith("CREATE VIEW PUBLIC.CHEAP AS"))
    }
  }

  private fun DbStatementsSplit(text: String): List<String> = SqlStatements.split(text)

  fun testModifyTableStatementsRunOnH2() {
    val ds = h2()
    exec(ds, "CREATE TABLE item (id INT PRIMARY KEY, name VARCHAR(20), legacy INT)", "CREATE INDEX idx_legacy ON item (legacy)")
    val item = table(ds, "ITEM")
    val objects = DbObjectCache.getInstance(project)
    val before = runBlocking { objects.columnDetails(ds, item) }.map { DbAlter.ColumnDef(it.name, it.name, it.typeName, it.nullable, it.default) }
    val indexes = runBlocking { objects.indexes(ds, item) }.map { DbAlter.IndexDef(it.name, it.name, it.columns, it.unique) }
    val after = listOf(before[0], before[1].copy(name = "TITLE", type = "VARCHAR(60)", nullable = false, default = "'x'"),
                       DbAlter.ColumnDef(null, "PRICE", "INT", true, "0"))
    val sql = DbAlter.statements(Dbms.H2, item, before, after, indexes, listOf(DbAlter.IndexDef(null, "IDX_PRICE", listOf("PRICE"), false)))
    val outcomes = exec(ds, *sql.toTypedArray())
    assertTrue(outcomes.joinToString { (it as? DbOutcome.Failed)?.message ?: "ok" }, outcomes.none { it is DbOutcome.Failed })
    // DDL from a console drops the cached metadata, so the tree and the next dialog see the new table
    val now = runBlocking { objects.columnDetails(ds, item) }
    assertEquals(listOf("ID", "TITLE", "PRICE"), now.map { it.name })
    assertFalse(now[1].nullable)
    assertEquals("'x'", now[1].default)
    assertEquals(listOf("IDX_PRICE"), runBlocking { objects.indexes(ds, item) }.map { it.name })
  }

  fun testTableDataViewPagesFiltersAndFollowsForeignKeys() {
    val ds = h2()
    exec(ds, "CREATE TABLE guest (id INT PRIMARY KEY, name VARCHAR(20))",
         "CREATE TABLE stay (id INT PRIMARY KEY, guest_id INT REFERENCES guest (id))",
         "INSERT INTO guest SELECT X, 'g' || X FROM SYSTEM_RANGE(1, 150)",
         "INSERT INTO stay VALUES (1, 7), (2, 7), (3, 8)")
    PropertiesComponent.getInstance().setValue("phantom.db.pageSize", 100, 500)
    try {
      val view = DbTableDataView.open(project, ds, table(ds, "GUEST"))
      PlatformTestUtil.waitWithEventsDispatching({ "no rows: " + view.result.shownStatus }, { view.result.shownRows != null }, 20)
      assertEquals(100, view.result.shownRows!!.size)
      assertTrue(view.hasNext)
      // the paged result is still editable: the table and its key are found
      PlatformTestUtil.waitWithEventsDispatching("not editable", { view.result.shownEditing != null }, 20)

      view.load(100)
      PlatformTestUtil.waitWithEventsDispatching("second page", { view.offset == 100 && view.result.shownRows?.size == 50 }, 20)
      assertFalse(view.hasNext)

      view.apply("id > 140", "id DESC")
      PlatformTestUtil.waitWithEventsDispatching("filtered", { view.offset == 0 && view.result.shownRows?.firstOrNull()?.firstOrNull() == 150 }, 20)
      assertEquals(10, view.result.shownRows!!.size)

      // a stay's guest_id leads to its guest, and the guest back to its stays
      val fk = runBlocking { DbObjectCache.getInstance(project).foreignKeys(ds, table(ds, "STAY")) }.single()
      val values = mapOf("ID" to 3, "GUEST_ID" to 8)
      val toGuest = DbForeignKeyNavigation.referenced(Dbms.H2, fk) { name -> values.entries.firstOrNull { it.key.equals(name, true) }?.value ?: DbForeignKeyNavigation.Missing }!!
      assertEquals("ID = 8", toGuest)
      val guestView = DbTableDataView.open(project, ds, fk.refTable, toGuest)
      assertSame(view, guestView)
      PlatformTestUtil.waitWithEventsDispatching("referenced row", { guestView.result.shownRows == listOf(listOf(8, "g8")) }, 20)
      val back = DbForeignKeyNavigation.referencing(Dbms.H2, fk) { if (it.equals("ID", true)) 7 else DbForeignKeyNavigation.Missing }!!
      assertEquals("GUEST_ID = 7", back)
      val stays = DbTableDataView.open(project, ds, fk.table, back)
      PlatformTestUtil.waitWithEventsDispatching("referencing rows", { stays.result.shownRows?.map { it[0] } == listOf(1, 2) }, 20)
      assertNull(DbForeignKeyNavigation.referenced(Dbms.H2, fk) { DbForeignKeyNavigation.Missing })
    }
    finally {
      PropertiesComponent.getInstance().unsetValue("phantom.db.pageSize")
    }
  }

  fun testCommitModeSwitchesAndAsksBeforeLeavingManual() {
    val ds = h2()
    val other = h2(url = ds.url)
    exec(ds, "CREATE TABLE t (id INT)")
    assertTrue(DbTransactions.setManual(project, ds, true))
    val manual = DbDataSourceService.getInstance(project).find(ds.id)!!
    assertTrue(manual.manualCommit)
    runBlocking { DbConnectionService.getInstance(project).applyCommitMode(manual) }
    exec(manual, "INSERT INTO t VALUES (1)")
    assertEquals(1, DbPendingChanges.getInstance(project).of(ds.id).size)
    assertEquals(listOf(listOf(0L)), rows(other, "SELECT COUNT(*) FROM t"))

    // leaving manual mode asks; Cancel keeps everything as it was
    TestDialogManager.setTestDialog { Messages_CANCEL }
    assertFalse(DbTransactions.setManual(project, manual, false))
    assertTrue(DbDataSourceService.getInstance(project).find(ds.id)!!.manualCommit)
    // Commit (the first button) ends the transaction, then the mode changes
    TestDialogManager.setTestDialog(TestDialog.OK)
    assertTrue(DbTransactions.setManual(project, manual, false))
    assertFalse(DbDataSourceService.getInstance(project).find(ds.id)!!.manualCommit)
    assertEquals(listOf(listOf(1L)), rows(other, "SELECT COUNT(*) FROM t"))
    assertFalse(DbPendingChanges.getInstance(project).hasPending(ds.id))
  }

  private val Messages_CANCEL: Int get() = com.intellij.openapi.ui.Messages.CANCEL
}
