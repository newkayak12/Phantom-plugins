// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.db.core.*
import dev.phantom.db.core.exec.*
import kotlinx.coroutines.runBlocking

class DbConsoleSchemaTest : BasePlatformTestCase() {
  companion object {
    init {
      System.setProperty("idea.load.plugins.id", "dev.phantom.db,org.jetbrains.kotlin")
    }
  }

  private lateinit var ds: DbDataSource

  override fun setUp() {
    super.setUp()
    ds = DbDataSource().apply {
      name = "two"
      dbms = Dbms.H2
      url = "jdbc:h2:mem:schemas_${System.nanoTime()};DB_CLOSE_DELAY=-1"
      user = "sa"
    }
    DbDataSourceService.getInstance(project).save(ds, CharArray(0))
    val setup = exec(null, "CREATE SCHEMA A", "CREATE SCHEMA B", "CREATE TABLE B.T(ID INT)", "INSERT INTO B.T VALUES (7)",
                     "CREATE TABLE B.E(ID INT PRIMARY KEY, NAME VARCHAR(10))", "INSERT INTO B.E VALUES (1, 'b')",
                     "CREATE TABLE PUBLIC.E(ID INT PRIMARY KEY, NAME VARCHAR(10))", "INSERT INTO PUBLIC.E VALUES (1, 'p')")
    assertTrue(setup.joinToString { it.sql }, setup.none { it is DbOutcome.Failed })
  }

  override fun tearDown() {
    try {
      DbConnectionService.getInstance(project).disconnect(ds.id)
      DbDataSourceService.getInstance(project).remove(ds.id)
    }
    catch (e: Throwable) {
      addSuppressedException(e)
    }
    finally {
      super.tearDown()
    }
  }

  private fun exec(schema: String?, vararg sql: String): List<DbOutcome> = execIn(schema?.let { DbSchemaChoice(it) }, *sql)

  private fun execIn(schema: DbSchemaChoice?, vararg sql: String): List<DbOutcome> = runBlocking { DbExecutor.execute(project, ds, sql.toList(), schema = schema) }

  private fun applied(): DbSchemaChoice? = DbConnectionService.getInstance(project).appliedSchema(ds.id)

  private fun sql(text: String, name: String = "q.sql"): SqlFile {
    val file = myFixture.configureByText(name, text) as SqlFile
    DbConsoles.attach(project, file.virtualFile, ds.id)
    return file
  }

  fun testSchemasListedForTheSelector() {
    val names = DbRunner.schemaChoices(runBlocking { DbMetadataCache.getInstance(project).schemas(ds) })
    assertEquals(DbSchemaChoice.DEFAULT, names.first())
    assertTrue(names.toString(), names.map { it.name }.containsAll(listOf("A", "B", "PUBLIC")))
  }

  fun testSelectedSchemaAppliesToTheConsoleConnection() {
    val file = sql("SELECT * FROM T").virtualFile
    DbConsoles.setSchema(project, file, DbSchemaChoice("B"))
    val inB = execIn(DbRunner.executionSchema(project, file), "SELECT * FROM T").single()
    assertTrue((inB as? DbOutcome.Failed)?.message.orEmpty(), inB is DbOutcome.Rows)
    assertEquals(listOf(7), (inB as DbOutcome.Rows).rows.map { it[0] })

    DbConsoles.setSchema(project, file, null)
    assertEquals(DbSchemaChoice.DEFAULT, DbRunner.executionSchema(project, file))
    assertTrue(execIn(DbRunner.executionSchema(project, file), "SELECT * FROM T").single() is DbOutcome.Failed)

    // a statement that does not ask leaves the connection where it is
    DbConsoles.setSchema(project, file, DbSchemaChoice("B"))
    exec("B", "SELECT 1")
    assertTrue(exec(null, "SELECT * FROM T").single() is DbOutcome.Rows)
  }

  fun testSchemaIsChosenAgainAfterTheTransactionEnds() {
    DbDataSourceService.getInstance(project).save(ds.copy().apply { manualCommit = true }, null)
    ds = DbDataSourceService.getInstance(project).find(ds.id)!!
    DbConnectionService.getInstance(project).disconnect(ds.id)
    exec("B", "SELECT * FROM T")
    assertEquals(DbSchemaChoice("B"), applied())
    // PostgreSQL's search_path goes back with a rollback: the switch is made again before the next run
    runBlocking { DbConnectionService.getInstance(project).endTransaction(ds, commit = false) }
    assertNull(applied())
    assertTrue(exec("B", "SELECT * FROM T").single() is DbOutcome.Rows)
    assertEquals(DbSchemaChoice("B"), applied())
    exec("B", "ROLLBACK")
    assertNull(applied())
    exec("B", "SELECT 1")
    exec("B", "COMMIT")
    assertNull(applied())
    exec("B", "SELECT 1")
    assertTrue(exec("B", "SELECT * FROM NOPE").single() is DbOutcome.Failed)
    assertNull("a failure inside a transaction may have aborted it", applied())
  }

  fun testTypedSchemaChangeIsNoticed() {
    exec("B", "SELECT 1")
    assertTrue(exec("B", "SET SCHEMA A").single() is DbOutcome.Updated)
    assertNull(applied())
    assertTrue(exec("B", "SELECT * FROM T").single() is DbOutcome.Rows)
    // a failure in auto-commit undoes nothing
    exec("B", "SELECT * FROM NOPE")
    assertEquals(DbSchemaChoice("B"), applied())
  }

  fun testGridEditsTheTableOfTheChosenSchema() {
    val rows = exec("B", "SELECT * FROM E").single() as DbOutcome.Rows
    assertEquals("b", rows.rows.single()[1])
    val target = runBlocking { DbEditTargets.resolve(project, ds, rows, DbSchemaChoice("B")) }!!
    assertEquals("\"B\".\"E\"", target.tableSql)
    // the console connection is elsewhere by now: the qualified edit still goes to B.E
    exec("", "SELECT 1")
    runBlocking { DbExecutor.execute(project, ds, target.update(rows.rows.single(), 1, "z")) }
    assertEquals("z", (exec(null, "SELECT NAME FROM B.E").single() as DbOutcome.Rows).rows.single()[0])
    assertEquals("p", (exec(null, "SELECT NAME FROM PUBLIC.E").single() as DbOutcome.Rows).rows.single()[0])
    // without a choice the default schema's table is meant
    assertEquals("\"E\"", runBlocking { DbEditTargets.resolve(project, ds, exec("", "SELECT * FROM E").single() as DbOutcome.Rows, DbSchemaChoice.DEFAULT) }!!.tableSql)
  }

  fun testSchemasOfOneNameInSeveralCatalogsStayApart() {
    val refs = listOf(DbSchemaRef("x", "c1", "dbo"), DbSchemaRef("x", "c2", "dbo"), DbSchemaRef("x", "c1", "sales"))
    assertEquals(listOf(DbSchemaChoice.DEFAULT, DbSchemaChoice("dbo", "c1"), DbSchemaChoice("dbo", "c2"), DbSchemaChoice("sales")), DbRunner.schemaChoices(refs))
    assertEquals("dbo (c2)", DbRunner.schemaText(DbSchemaChoice("dbo", "c2")))
    // MySQL lists catalogs: no catalog part
    assertEquals(listOf(DbSchemaChoice.DEFAULT, DbSchemaChoice("app")), DbRunner.schemaChoices(listOf(DbSchemaRef("x", "app", null))))
  }

  fun testUnknownSchemaShowsTheFailure() {
    val error = runCatching { exec("NOPE", "SELECT 1") }.exceptionOrNull()
    assertTrue(error.toString(), error is java.sql.SQLException)
    assertTrue(DbExecutor.message(error as java.sql.SQLException), DbExecutor.message(error).contains("NOPE"))
    // the connection still works afterwards
    assertTrue(exec("", "SELECT 1").single() is DbOutcome.Rows)
  }

  fun testManualCommitSessionKeepsItsTransaction() {
    DbDataSourceService.getInstance(project).save(ds.copy().apply { manualCommit = true }, null)
    ds = DbDataSourceService.getInstance(project).find(ds.id)!!
    DbConnectionService.getInstance(project).disconnect(ds.id)
    assertTrue(exec("B", "INSERT INTO T VALUES (8)").single() is DbOutcome.Updated)
    assertEquals(listOf(7, 8), (exec("B", "SELECT * FROM T ORDER BY ID").single() as DbOutcome.Rows).rows.map { it[0] })
    runBlocking { DbConnectionService.getInstance(project).endTransaction(ds, commit = false) }
    assertEquals(listOf(7), (exec("B", "SELECT * FROM T").single() as DbOutcome.Rows).rows.map { it[0] })
  }

  fun testSelectionIsKeptPerFile() {
    val a = sql("SELECT 1", "a.sql").virtualFile
    val b = sql("SELECT 1", "b.sql").virtualFile
    DbConsoles.setSchema(project, a, DbSchemaChoice("B"))
    assertEquals(DbSchemaChoice("B"), DbConsoles.schemaFor(project, a))
    assertNull(DbConsoles.schemaFor(project, b))
    // another data source drops the choice: its schemas are not this one's
    val other = DbDataSource().apply { name = "other"; dbms = Dbms.H2; url = "jdbc:h2:mem:other_${System.nanoTime()}" }
    DbDataSourceService.getInstance(project).save(other, CharArray(0))
    try {
      DbConsoles.attach(project, a, ds.id)
      assertEquals(DbSchemaChoice("B"), DbConsoles.schemaFor(project, a))
      DbConsoles.attach(project, a, other.id)
      assertNull(DbConsoles.schemaFor(project, a))
    }
    finally {
      DbDataSourceService.getInstance(project).remove(other.id)
    }
  }

  fun testCompletionAndResolveUseTheSelectedSchema() {
    myFixture.enableInspections(SqlUnresolvedTableInspection::class.java)
    val file = sql("SELECT * FROM T")
    val unknown = { myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING).any { it.description == "Unknown table 'T'" } }
    // the default schema (PUBLIC) has no T
    runBlocking { SqlSchema.getInstance(project).load(ds) }
    assertEquals("PUBLIC", SqlSchema.getInstance(project).snapshotFor(file, ds, load = false)?.current?.name)
    assertTrue(unknown())

    DbConsoles.setSchema(project, file.virtualFile, DbSchemaChoice("B"))
    runBlocking { SqlSchema.getInstance(project).load(ds, DbSchemaChoice("B")) }
    val snapshot = SqlSchema.getInstance(project).snapshotFor(file, ds, load = false)!!
    assertEquals("B", snapshot.current?.name)
    assertEquals("B", snapshot.find("T", null).first().schema)
    assertFalse(unknown())

    myFixture.configureByText("c.sql", "SELECT * FROM <caret>")
    DbConsoles.attach(project, myFixture.file.virtualFile, ds.id)
    DbConsoles.setSchema(project, myFixture.file.virtualFile, DbSchemaChoice("B"))
    val offered = myFixture.completeBasic()?.map { it.lookupString }.orEmpty()
    assertTrue(offered.toString(), "T" in offered)
  }
}
