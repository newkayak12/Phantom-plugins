// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.db.core.DbDataSource
import dev.phantom.db.core.DbDataSourceService
import dev.phantom.db.core.DbSchemaScope
import dev.phantom.db.core.Dbms
import dev.phantom.db.core.exec.DbConnectionService
import dev.phantom.db.core.exec.DbConsoles
import dev.phantom.db.core.exec.DbExecutor
import dev.phantom.db.core.exec.DbMetadataCache
import dev.phantom.db.core.exec.DbOutcome
import dev.phantom.db.core.ui.DbSessionsDialog
import kotlinx.coroutines.runBlocking

class DbSessionsTest : BasePlatformTestCase() {
  companion object {
    init {
      System.setProperty("idea.load.plugins.id", "dev.phantom.db,org.jetbrains.kotlin")
    }
  }

  private fun h2(configure: DbDataSource.() -> Unit = {}): DbDataSource {
    val ds = DbDataSource().apply {
      name = "s"
      dbms = Dbms.H2
      url = "jdbc:h2:mem:sessions_${System.nanoTime()};DB_CLOSE_DELAY=-1"
      user = "sa"
      configure()
    }
    DbDataSourceService.getInstance(project).save(ds, CharArray(0))
    return ds
  }

  override fun tearDown() {
    try {
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

  private fun sessionId(ds: DbDataSource, console: String): Any? = runBlocking {
    (DbExecutor.execute(project, ds, listOf("SELECT SESSION_ID()"), console = console).single() as DbOutcome.Rows).rows.single()[0]
  }

  /** Single session mode off: each console its own connection; on (the default) or with manual commit: one shared. */
  fun testConsolesShareOrNot() {
    val shared = h2()
    assertEquals(sessionId(shared, "file:///a.sql"), sessionId(shared, "file:///b.sql"))

    val separate = h2 { singleSession = false }
    assertFalse(sessionId(separate, "file:///a.sql") == sessionId(separate, "file:///b.sql"))
    assertEquals(sessionId(separate, "file:///a.sql"), sessionId(separate, "file:///a.sql"))

    val manual = h2 { singleSession = false; manualCommit = true }
    assertEquals(sessionId(manual, "file:///a.sql"), sessionId(manual, "file:///b.sql"))

    val service = DbConnectionService.getInstance(project)
    val open = service.openSessions().filter { it.key.dataSourceId == separate.id }
    assertEquals(setOf("file:///a.sql", "file:///b.sql"), open.map { it.key.console }.toSet())
    assertTrue(service.isConnected(separate.id))

    val dialog = DbSessionsDialog(project)
    try {
      assertTrue(dialog.list.model.size >= 4)
    }
    finally {
      dialog.close(0)
    }

    val services = dev.phantom.db.core.ui.DbServiceViewContributor()
    val connected = services.getServices(project).single { it.dataSource.id == separate.id }
    assertEquals(2, connected.getServices(project).size)

    service.close(open.first().key)
    assertEquals(1, service.openSessions().count { it.key.dataSourceId == separate.id })
    service.disconnect(separate.id)
    assertFalse(service.isConnected(separate.id))
  }

  /** The console session switcher: its own session by default, or the shared one, another console's, a named one. */
  fun testConsoleChoosesItsSession() {
    val ds = h2 { singleSession = false }
    val a = myFixture.configureByText("a.sql", "").virtualFile
    val b = myFixture.configureByText("b.sql", "").virtualFile
    fun id(file: com.intellij.openapi.vfs.VirtualFile) = sessionIdIn(ds, DbConsoles.sessionFor(project, file))
    assertEquals(a.url, DbConsoles.sessionFor(project, a))
    assertFalse(id(a) == id(b))

    DbConsoles.setSession(project, b, a.url)
    assertEquals(id(a), id(b))
    DbConsoles.setSession(project, b, null)
    assertNull(DbConsoles.sessionFor(project, b))
    assertEquals(sessionIdIn(ds, null), id(b))
    DbConsoles.setSession(project, b, DbConsoles.NAMED_SESSION + "1")
    assertEquals("Session 1", DbConsoles.sessionName(DbConsoles.sessionFor(project, b)))
    assertFalse(id(a) == id(b))
    DbConsoles.setSession(project, b, b.url)
    assertEquals(b.url, DbConsoles.sessionFor(project, b))
  }

  private fun sessionIdIn(ds: DbDataSource, console: String?): Any? = runBlocking {
    (DbExecutor.execute(project, ds, listOf("SELECT SESSION_ID()"), console = console).single() as DbOutcome.Rows).rows.single()[0]
  }

  /** Level 1 looks up no columns for the editor; level 3 loads every table's columns with the names. */
  fun testIntrospectionLevels() {
    val names = h2 { introspection = 1 }
    runBlocking { DbExecutor.execute(project, names, listOf("CREATE TABLE t (id INT)")) }
    val schema = dev.phantom.db.core.SqlSchema.getInstance(project)
    val snapshot = runBlocking { schema.load(names) }!!
    val t = snapshot.tables.single { it.name == "T" }
    assertNull(schema.columns(names, t))
    assertNull(schema.columnsNoWait(names, t))

    val full = h2 { introspection = 3 }
    runBlocking { DbExecutor.execute(project, full, listOf("CREATE TABLE a (id INT)", "CREATE TABLE b (id INT)")) }
    val tables = runBlocking { schema.load(full) }!!.tables.filter { it.dataSourceId == full.id }
    assertEquals(2, tables.size)
    com.intellij.testFramework.PlatformTestUtil.waitWithEventsDispatching("columns not loaded up front", {
      tables.all { DbMetadataCache.cachedColumns(project, it) != null }
    }, 20)
  }

  fun testSchemaScope() {
    assertTrue(DbSchemaScope.matches("", "anything"))
    assertTrue(DbSchemaScope.matches("app, sales_*", "SALES_EU"))
    assertFalse(DbSchemaScope.matches("app, sales_*", "hr"))
    assertTrue(DbSchemaScope.matches("!tmp*", "app"))
    assertFalse(DbSchemaScope.matches("!tmp*", "tmp_1"))
    assertFalse(DbSchemaScope.matches("a?c, !abc", "abc"))
    assertTrue(DbSchemaScope.matches("a?c, !abc", "axc"))

    val ds = h2 { schemas = "APP" }
    runBlocking { DbExecutor.execute(project, ds, listOf("CREATE SCHEMA APP", "CREATE SCHEMA OTHER")) }
    val names = runBlocking { DbMetadataCache.getInstance(project).schemas(ds) }.map { it.name }
    assertEquals(listOf("APP"), names)
  }
}
