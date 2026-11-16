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
    assertEquals("SELECT * FROM shop.`order` WHERE (\nstatus = 'OPEN'\n) ORDER BY id DESC\n LIMIT 501 OFFSET 1000",
                 DbTableQuery.select(Dbms.MYSQL, t, "where status = 'OPEN';", "ORDER  BY id DESC", 501, 1000))
    val pg = DbTableRef("x", null, "public", "users", "TABLE")
    assertEquals("SELECT * FROM public.users LIMIT 101", DbTableQuery.select(Dbms.POSTGRESQL, pg, " ", "", 101, 0))
    assertEquals("SELECT COUNT(*) FROM public.users WHERE (\nage > 3\n)", DbTableQuery.count(Dbms.POSTGRESQL, pg, "age > 3"))
    // SQL Server needs an ORDER BY to page; Oracle pages without one
    assertEquals("SELECT * FROM public.users ORDER BY (SELECT NULL) OFFSET 20 ROWS FETCH NEXT 10 ROWS ONLY",
                 DbTableQuery.select(Dbms.GENERIC, pg, "", "", 10, 20, DbPaging.FETCH_ORDERED))
    assertEquals("SELECT * FROM public.users ORDER BY id\n OFFSET 0 ROWS FETCH NEXT 10 ROWS ONLY",
                 DbTableQuery.select(Dbms.GENERIC, pg, "", "id", 10, 0, DbPaging.FETCH))
    assertEquals(DbPaging.FETCH_ORDERED, DbPaging.of(DbDataSource().apply { dbms = Dbms.GENERIC; url = "jdbc:sqlserver://h;databaseName=x" }))
    assertEquals(DbPaging.FETCH, DbPaging.of(DbDataSource().apply { dbms = Dbms.GENERIC; url = "jdbc:oracle:thin:@h:1521/x" }))
    assertEquals(DbPaging.LIMIT, DbPaging.of(DbDataSource().apply { dbms = Dbms.H2; url = "jdbc:h2:mem:x" }))
    // a ';' would start a second statement; a trailing one, or one in a string or comment, does not
    assertNotNull(DbTableQuery.problem("1=1; DELETE FROM t"))
    assertNull(DbTableQuery.problem("a = ';' -- x; y", "id;"))
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

  fun testModifyTableKeepsCommentsAndAutoIncrement() {
    val t = DbTableRef("x", "shop", null, "orders", "TABLE")
    val pg = DbTableRef("x", null, "public", "orders", "TABLE")
    val id = DbAlter.ColumnDef("id", "id", "BIGINT", false, null, "주문 번호", autoIncrement = true)
    val memo = DbAlter.ColumnDef("memo", "memo", "VARCHAR(20)", true, null, "it's \\ memo")
    // MySQL restates the column: a type change keeps its comment and AUTO_INCREMENT
    assertEquals(listOf("ALTER TABLE shop.orders MODIFY COLUMN id INT NOT NULL AUTO_INCREMENT COMMENT '주문 번호'"),
                 DbAlter.statements(Dbms.MYSQL, t, listOf(id), listOf(id.copy(type = "INT"))))
    assertEquals(listOf("ALTER TABLE shop.orders MODIFY COLUMN memo VARCHAR(20) NULL COMMENT 'it''s \\\\ memo'"),
                 DbAlter.statements(Dbms.MYSQL, t, listOf(memo.copy(comment = null)), listOf(memo)))
    assertEquals(listOf("COMMENT ON COLUMN public.orders.memo IS 'it''s \\ memo'"),
                 DbAlter.statements(Dbms.POSTGRESQL, pg, listOf(memo.copy(comment = null)), listOf(memo)))
    assertEquals(listOf("COMMENT ON COLUMN public.orders.memo IS NULL"),
                 DbAlter.statements(Dbms.POSTGRESQL, pg, listOf(memo), listOf(memo.copy(comment = ""))))
    assertEquals(listOf("ALTER TABLE public.orders ADD COLUMN note TEXT", "COMMENT ON COLUMN public.orders.note IS '비고'"),
                 DbAlter.statements(Dbms.POSTGRESQL, pg, emptyList(), listOf(DbAlter.ColumnDef(null, "note", "TEXT", true, null, "비고"))))
    assertEmpty(DbAlter.statements(Dbms.MYSQL, t, listOf(id, memo), listOf(id, memo)))
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
    assertEquals("EXPLAIN FORMAT=JSON SELECT 1", DbExplain.sql(Dbms.MYSQL, "SELECT 1;", analyze = false))
    assertEquals("EXPLAIN ANALYZE SELECT 1", DbExplain.sql(Dbms.MYSQL, "SELECT 1", analyze = true))
    assertEquals("ANALYZE FORMAT=JSON SELECT 1", DbExplain.sql(Dbms.MARIADB, "SELECT 1", analyze = true))
    assertEquals("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) SELECT 1", DbExplain.sql(Dbms.POSTGRESQL, "SELECT 1", analyze = true))
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

  /** Copy Table To: into another data source, creating the table, then into an existing table with fewer columns. */
  fun testCopyTableToAnotherDataSource() {
    val source = h2()
    val target = h2()
    val made = exec(source, "CREATE TABLE guest (id INT PRIMARY KEY, name VARCHAR(20), born DATE, note VARCHAR(20) DEFAULT 'n')",
                    "INSERT INTO guest SELECT X, 'g' || X, DATEADD(DAY, X, DATE '2000-01-01'), NULL FROM SYSTEM_RANGE(1, 2500)")
    assertTrue(made.toString(), made.none { it is DbOutcome.Failed })
    val schema = runBlocking { DbMetadataCache.getInstance(project).schemas(target) }.first { it.name == "PUBLIC" }
    val result = runBlocking { DbTableCopy.run(project, source, table(source, "GUEST"), target, schema) }
    assertEquals(2500L, result.rows)
    assertTrue(result.created)
    assertEquals(listOf(listOf<Any?>(2500L, 1, 2500)), rows(target, "SELECT COUNT(*), MIN(id), MAX(id) FROM guest"))
    assertEquals(listOf(listOf<Any?>("g7", java.sql.Date.valueOf("2000-01-08"), null)), rows(target, "SELECT name, born, note FROM guest WHERE id = 7"))
    // the primary key came along
    assertTrue(exec(target, "INSERT INTO guest (id) VALUES (7)").single() is DbOutcome.Failed)

    exec(target, "CREATE TABLE other (NAME VARCHAR(20), id INT, extra INT DEFAULT 5)")
    exec(source, "CREATE TABLE other (id INT PRIMARY KEY, name VARCHAR(20), gone INT)", "INSERT INTO other VALUES (1, 'a', 9), (2, 'b', 9)")
    val into = runBlocking { DbTableCopy.run(project, source, table(source, "OTHER"), target, schema) }
    assertFalse(into.created)
    assertEquals(listOf(listOf<Any?>("a", 1, 5), listOf<Any?>("b", 2, 5)), rows(target, "SELECT name, id, extra FROM other ORDER BY id"))
  }

  fun testCopyTableTranslatesTypes() {
    assertEquals("INTEGER", DbTableCopy.type(Dbms.POSTGRESQL, "INT UNSIGNED"))
    assertEquals("BOOLEAN", DbTableCopy.type(Dbms.POSTGRESQL, "TINYINT(1)"))
    assertEquals("TIMESTAMP(3)", DbTableCopy.type(Dbms.POSTGRESQL, "DATETIME(3)"))
    assertEquals("DATETIME", DbTableCopy.type(Dbms.MYSQL, "timestamp without time zone"))
    assertEquals("LONGBLOB", DbTableCopy.type(Dbms.MYSQL, "bytea"))
    assertEquals("TEXT", DbTableCopy.type(Dbms.MYSQL, "character varying"))
    assertEquals("VARCHAR(20)", DbTableCopy.type(Dbms.POSTGRESQL, "VARCHAR(20)"))
    assertEquals("DECIMAL(10,2)", DbTableCopy.type(Dbms.H2, "NUMERIC(10,2)"))
    assertEquals("guest", DbTableCopy.targetName(Dbms.H2, Dbms.POSTGRESQL, "GUEST"))
    assertEquals("GUEST", DbTableCopy.targetName(Dbms.MYSQL, Dbms.H2, "guest"))
  }

  /** Compare Content: rows matched by primary key across data sources; the sync SQL makes the right table equal the left. */
  fun testCompareContent() {
    val left = h2()
    val right = h2()
    val made = exec(left, "CREATE TABLE guest (id INT PRIMARY KEY, name VARCHAR(20), score DECIMAL(5,2), born DATE)",
                    "INSERT INTO guest VALUES (1, 'kim', 1.50, DATE '2000-01-01'), (2, 'lee', 2, NULL), (3, 'park', 3, NULL), (5, 'new', NULL, NULL)") +
               exec(right, "CREATE TABLE guest (id BIGINT PRIMARY KEY, name VARCHAR(30), score DOUBLE PRECISION, born DATE, extra INT)",
                    "INSERT INTO guest VALUES (1, 'kim', 1.5, DATE '2000-01-01', 9), (2, 'LEE', 2, NULL, 9), (3, 'park', 3, NULL, 9), (4, 'gone', NULL, NULL, 9)")
    assertTrue(made.toString(), made.none { it is DbOutcome.Failed })
    val diff = runBlocking { DbDataCompare.run(project, left, table(left, "GUEST"), right, table(right, "GUEST")) }
    assertTrue(diff.keyed)
    assertEquals(listOf("ID", "NAME", "SCORE", "BORN"), diff.columns.map { it.first })
    assertEquals(listOf(listOf<Any?>(5, "new", null, null)), diff.onlyLeft.map { it.toList() })
    assertEquals(listOf(4L), diff.onlyRight.map { it[0] })
    assertEquals(listOf("lee" to "LEE"), diff.changed.map { it.first[1] to it.second[1] })
    // 1.50 (DECIMAL) is 1.5 (DOUBLE), INT 1 is BIGINT 1
    assertEquals(2L, diff.same)

    val sync = DbDataCompare.sync(diff, Dbms.H2, "guest")
    assertTrue(sync.toString(), sync.contains("DELETE FROM guest WHERE ID = 4;"))
    assertTrue(sync.toString(), sync.contains("UPDATE guest SET NAME = 'lee' WHERE ID = 2;"))
    assertTrue(sync.toString(), sync.contains("INSERT INTO guest (ID, NAME, SCORE, BORN) VALUES (5, 'new', NULL, NULL);"))
    val ran = exec(right, *sync.map { it.removeSuffix(";") }.toTypedArray())
    assertTrue(ran.toString(), ran.none { it is DbOutcome.Failed })
    val again = runBlocking { DbDataCompare.run(project, left, table(left, "GUEST"), right, table(right, "GUEST")) }
    assertTrue("${again.onlyLeft.size} ${again.onlyRight.size} ${again.changed.size}", again.identical)

    val (l, r) = DbDataCompare.text(diff)
    assertTrue(l, l.startsWith("ID | NAME | SCORE | BORN\n"))
    assertTrue(r, r.contains("4 | gone | NULL | NULL"))
  }

  /** Full-text Search: text columns ignoring case, LIKE wildcards in the text taken literally, numbers in numeric columns. */
  fun testFullTextSearch() {
    val ds = h2()
    val made = exec(ds, "CREATE TABLE guest (id INT PRIMARY KEY, name VARCHAR(20), note CLOB, photo BLOB)",
                    "INSERT INTO guest VALUES (1, 'Kim', NULL, NULL), (2, 'lee', 'met kim twice', NULL), (3, 'park', '50% off', NULL), (42, 'x_y', NULL, NULL)",
                    "CREATE TABLE room (no INT PRIMARY KEY, label VARCHAR(20))", "INSERT INTO room VALUES (42, 'k'), (7, 'kim''s')",
                    "CREATE VIEW kims AS SELECT * FROM guest")
    assertTrue(made.toString(), made.none { it is DbOutcome.Failed })
    val schema = runBlocking { DbMetadataCache.getInstance(project).schemas(ds) }.filter { it.name == "PUBLIC" }
    fun search(text: String) = runBlocking { DbFullTextSearch.run(project, ds, schema, text) }.also { assertEquals(it.failed.toString(), 0, it.failed.size) }
      .hits.associate { it.table.name to it.count }
    assertEquals(mapOf("GUEST" to 2L, "ROOM" to 1L), search("KIM"))
    assertEquals(mapOf("GUEST" to 1L), search("50%"))
    assertEquals(mapOf("GUEST" to 1L), search("x_y"))
    assertEquals(mapOf("GUEST" to 1L, "ROOM" to 1L), search("42"))
    assertEquals(emptyMap<String, Long>(), search("nobody"))
    // the hit's WHERE shows the rows
    val hit = runBlocking { DbFullTextSearch.run(project, ds, schema, "kim") }.hits.first { it.table.name == "GUEST" }
    assertEquals(listOf(listOf<Any?>(1), listOf<Any?>(2)), rows(ds, "SELECT id FROM guest WHERE ${hit.where} ORDER BY id"))
  }

  /** Filter by on a cell: conditions from the selected values, added to WHERE with AND and run. */
  fun testFilterByValue() {
    val ds = h2()
    exec(ds, "CREATE TABLE guest (id INT PRIMARY KEY, name VARCHAR(20), note VARCHAR(20))",
         "INSERT INTO guest VALUES (1, 'kim', NULL), (2, 'lee', 'x'), (3, 'kim_2', 'y'), (4, 'park', NULL)")
    val view = DbTableDataView.open(project, ds, table(ds, "GUEST"))
    PlatformTestUtil.waitWithEventsDispatching({ "no rows: " + view.result.shownStatus }, { view.result.shownRows?.size == 4 }, 20)
    val grid = view.result.shownGrid!!
    fun ids() = view.result.shownRows?.map { it[0] }

    grid.selectionModel.setSelection(com.intellij.database.datagrid.ModelIndex.forRow(grid, 0), com.intellij.database.datagrid.ModelIndex.forColumn(grid, 1))
    assertEquals(listOf("NAME = 'kim'", "NAME <> 'kim'", "NAME LIKE '%kim%'", "NAME NOT LIKE '%kim%'"), DbFilterByValue.conditions(grid))
    DbFilterByValue.apply(grid, "NAME LIKE '%kim%'")
    PlatformTestUtil.waitWithEventsDispatching({ "not filtered: ${ids()}" }, { ids() == listOf(1, 3) }, 20)

    // a second condition is added to the first
    val g2 = view.result.shownGrid!!
    g2.selectionModel.setSelection(com.intellij.database.datagrid.ModelIndex.forRow(g2, 0), com.intellij.database.datagrid.ModelIndex.forColumn(g2, 2))
    assertEquals(listOf("NOTE IS NULL", "NOTE IS NOT NULL"), DbFilterByValue.conditions(g2))
    DbFilterByValue.apply(g2, "NOTE IS NULL")
    assertEquals("(NAME LIKE '%kim%') AND (NOTE IS NULL)", view.filter.where)
    PlatformTestUtil.waitWithEventsDispatching({ "not filtered: ${ids()}" }, { ids() == listOf(1) }, 20)

    // several values of a column: IN
    view.filter.where = ""
    view.filter.apply()
    PlatformTestUtil.waitWithEventsDispatching({ "not reset: ${ids()}" }, { ids()?.size == 4 }, 20)
    val g3 = view.result.shownGrid!!
    g3.selectionModel.setSelection(com.intellij.database.datagrid.ModelIndexSet.forRows(g3, 0, 1, 3), com.intellij.database.datagrid.ModelIndexSet.forColumns(g3, 2))
    assertEquals(listOf("NOTE IN ('x') OR NOTE IS NULL", "NOTE NOT IN ('x') AND NOTE IS NOT NULL"), DbFilterByValue.conditions(g3))
    DbFilterByValue.apply(g3, DbFilterByValue.conditions(g3)[1])
    PlatformTestUtil.waitWithEventsDispatching({ "not filtered: ${ids()}" }, { ids() == listOf(3) }, 20)
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
