// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.ai.mcp.AgentTools
import dev.phantom.ai.mcp.McpToolError
import dev.phantom.db.core.DbDataSource
import dev.phantom.db.core.DbDataSourceService
import dev.phantom.db.core.Dbms
import dev.phantom.db.core.exec.DbConnectionService
import dev.phantom.db.core.exec.DbExecutor
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit

/** The database tools of the optional phantom.ai.db module, on an H2 data source of the Database Console. */
class DbAgentToolsInIdeTest : BasePlatformTestCase() {
  private lateinit var ds: DbDataSource

  override fun setUp() {
    super.setUp()
    ds = DbDataSource().apply {
      name = "shop"
      dbms = Dbms.H2
      url = "jdbc:h2:mem:agent${System.nanoTime()};DB_CLOSE_DELAY=-1"
      user = "sa"
    }
    DbDataSourceService.getInstance(project).save(ds, CharArray(0))
    runBlocking {
      DbExecutor.execute(project, ds, listOf(
        "CREATE TABLE orders (id INT PRIMARY KEY, name VARCHAR(20) NOT NULL, note VARCHAR(20))",
        "INSERT INTO orders VALUES (1, 'a', NULL), (2, 'b', 'x'), (3, 'c', NULL)",
      ), history = false)
    }
  }

  override fun tearDown() {
    try {
      DbConnectionService.getInstance(project).disconnect(ds.id)
      DbDataSourceService.getInstance(project).remove(ds.id)
    }
    finally {
      super.tearDown()
    }
  }

  private fun call(name: String, vararg args: Pair<String, Any>): String {
    val tool = AgentTools.all().single { it.name == name }
    val json = JsonObject().apply { args.forEach { (k, v) -> if (v is Int) addProperty(k, v) else addProperty(k, v.toString()) } }
    return ApplicationManager.getApplication().executeOnPooledThread<Result<String>> { runCatching { tool.call(project.basePath, json) } }
      .get(60, TimeUnit.SECONDS).getOrThrow()
  }

  private fun error(name: String, vararg args: Pair<String, Any>): String =
    (runCatching { call(name, *args) }.exceptionOrNull() as? McpToolError)?.message ?: fail("no tool error") as String

  fun testTheModuleAddsTheDatabaseTools() {
    val names = AgentTools.all().map { it.name }
    assertEquals(listOf("list_databases", "list_tables", "describe_table", "query_database", "draft_sql"), names)
    assertEquals(listOf("draft_sql"), AgentTools.all().filterNot { it.readOnly }.map { it.name })
  }

  fun testListsTablesAndColumns() {
    assertTrue(call("list_databases"), call("list_databases").lines().any { it.startsWith("shop  H2") })
    val tables = call("list_tables", "database" to "shop", "schema" to "PUBLIC")
    assertEquals("shop.PUBLIC: 1 tables and views\nORDERS", tables)
    assertEquals("PUBLIC.ORDERS\nID  INTEGER  NOT NULL  PK\nNAME  CHARACTER VARYING(20)  NOT NULL\nNOTE  CHARACTER VARYING(20)", call("describe_table", "database" to "SHOP", "table" to "orders"))
  }

  fun testQueriesReturnFewRows() {
    assertEquals("ID\tNAME\tNOTE\n1\ta\tNULL\n2\tb\tx\n... more rows; raise max_rows or narrow the query",
      call("query_database", "database" to "shop", "sql" to "SELECT * FROM orders ORDER BY id", "max_rows" to 2))
    assertEquals("C\n3\n(1 rows)", call("query_database", "database" to "shop", "sql" to "select count(*) as c from orders;"))
  }

  fun testNothingCanBeChanged() {
    assertTrue(error("query_database", "database" to "shop", "sql" to "DELETE FROM orders").startsWith("Only read-only statements"))
    assertEquals("One statement at a time", error("query_database", "database" to "shop", "sql" to "SELECT 1; DROP TABLE orders"))
    assertTrue(error("query_database", "database" to "nope", "sql" to "SELECT 1").startsWith("No data source nope"))
    assertEquals("C\n3\n(1 rows)", call("query_database", "database" to "shop", "sql" to "SELECT count(*) AS c FROM orders"))
  }

  fun testDraftsChangesIntoTheConsoleWithoutRunningThem() {
    // the tool opens the console on the EDT, which is this test's thread: keep it dispatching while the tool waits
    val tool = AgentTools.all().single { it.name == "draft_sql" }
    val json = JsonObject().apply { addProperty("database", "shop"); addProperty("sql", "DELETE FROM orders WHERE id = 1;") }
    val future = ApplicationManager.getApplication().executeOnPooledThread<String> { tool.call(project.basePath, json) }
    val deadline = System.currentTimeMillis() + 60_000
    while (!future.isDone && System.currentTimeMillis() < deadline) com.intellij.testFramework.PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    val said = future.get(1, TimeUnit.SECONDS)
    assertTrue(said, said.startsWith("Drafted in the console") && said.contains("not run"))
    val editor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).selectedTextEditor ?: fail("no console open") as Nothing
    assertTrue(editor.document.text, editor.document.text.contains("DELETE FROM orders WHERE id = 1;"))
    assertEquals("C\n3\n(1 rows)", call("query_database", "database" to "shop", "sql" to "SELECT count(*) AS c FROM orders"))
  }
}
