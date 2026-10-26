// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.db.core.DbDataSource
import dev.phantom.db.core.DbDataSourceService
import dev.phantom.db.core.DbSchemaScope
import dev.phantom.db.core.Dbms
import dev.phantom.db.core.exec.DbConnectionService
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

    service.close(open.first().key)
    assertEquals(1, service.openSessions().count { it.key.dataSourceId == separate.id })
    service.disconnect(separate.id)
    assertFalse(service.isConnected(separate.id))
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
