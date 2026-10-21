// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
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
    val setup = exec(null, "CREATE SCHEMA A", "CREATE SCHEMA B", "CREATE TABLE B.T(ID INT)", "INSERT INTO B.T VALUES (7)")
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

  private fun exec(schema: String?, vararg sql: String): List<DbOutcome> = runBlocking { DbExecutor.execute(project, ds, sql.toList(), schema = schema) }

  private fun sql(text: String, name: String = "q.sql"): SqlFile {
    val file = myFixture.configureByText(name, text) as SqlFile
    DbConsoles.attach(project, file.virtualFile, ds.id)
    return file
  }

  fun testSchemasListedForTheSelector() {
    val names = DbRunner.schemaChoices(runBlocking { DbMetadataCache.getInstance(project).schemas(ds) })
    assertEquals("", names.first())
    assertTrue(names.toString(), names.containsAll(listOf("A", "B", "PUBLIC")))
  }

  fun testSelectedSchemaAppliesToTheConsoleConnection() {
    val file = sql("SELECT * FROM T").virtualFile
    DbConsoles.setSchema(project, file, "B")
    val inB = exec(DbRunner.executionSchema(project, file), "SELECT * FROM T").single()
    assertTrue((inB as? DbOutcome.Failed)?.message.orEmpty(), inB is DbOutcome.Rows)
    assertEquals(listOf(7), (inB as DbOutcome.Rows).rows.map { it[0] })

    DbConsoles.setSchema(project, file, null)
    assertEquals("", DbRunner.executionSchema(project, file))
    assertTrue(exec(DbRunner.executionSchema(project, file), "SELECT * FROM T").single() is DbOutcome.Failed)

    // a statement that does not ask leaves the connection where it is
    DbConsoles.setSchema(project, file, "B")
    exec("B", "SELECT 1")
    assertTrue(exec(null, "SELECT * FROM T").single() is DbOutcome.Rows)
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
    DbConsoles.setSchema(project, a, "B")
    assertEquals("B", DbConsoles.schemaFor(project, a))
    assertNull(DbConsoles.schemaFor(project, b))
    // another data source drops the choice: its schemas are not this one's
    val other = DbDataSource().apply { name = "other"; dbms = Dbms.H2; url = "jdbc:h2:mem:other_${System.nanoTime()}" }
    DbDataSourceService.getInstance(project).save(other, CharArray(0))
    try {
      DbConsoles.attach(project, a, ds.id)
      assertEquals("B", DbConsoles.schemaFor(project, a))
      DbConsoles.attach(project, a, other.id)
      assertNull(DbConsoles.schemaFor(project, a))
    }
    finally {
      DbDataSourceService.getInstance(project).remove(other.id)
    }
  }

  fun testCompletionAndResolveUseTheSelectedSchema() {
    val file = sql("SELECT * FROM T")
    val unknown = { myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING).any { it.description == "Unknown table 'T'" } }
    // the default schema (PUBLIC) has no T
    runBlocking { SqlSchema.getInstance(project).load(ds) }
    assertEquals("PUBLIC", SqlSchema.getInstance(project).snapshotFor(file, ds, load = false)?.current?.name)
    assertTrue(unknown())

    DbConsoles.setSchema(project, file.virtualFile, "B")
    runBlocking { SqlSchema.getInstance(project).load(ds, "B") }
    val snapshot = SqlSchema.getInstance(project).snapshotFor(file, ds, load = false)!!
    assertEquals("B", snapshot.current?.name)
    assertEquals("B", snapshot.find("T", null).first().schema)
    assertFalse(unknown())

    myFixture.configureByText("c.sql", "SELECT * FROM <caret>")
    DbConsoles.attach(project, myFixture.file.virtualFile, ds.id)
    DbConsoles.setSchema(project, myFixture.file.virtualFile, "B")
    val offered = myFixture.completeBasic()?.map { it.lookupString }.orEmpty()
    assertTrue(offered.toString(), "T" in offered)
  }
}
