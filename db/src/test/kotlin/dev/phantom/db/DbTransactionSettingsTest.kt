// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.db.core.*
import dev.phantom.db.core.exec.*
import kotlinx.coroutines.runBlocking
import java.sql.Connection

/** Per data source connection settings: transaction isolation and read-only (H2 does not report Connection.setReadOnly back). */
class DbTransactionSettingsTest : BasePlatformTestCase() {
  private fun h2(configure: DbDataSource.() -> Unit): DbDataSource {
    val ds = DbDataSource().apply {
      name = "tx"; dbms = Dbms.H2; user = "sa"; url = "jdbc:h2:mem:tx_${System.nanoTime()};DB_CLOSE_DELAY=-1"
      driverJars = arrayListOf(DbConsoleTest.h2Jar.toString())
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

  private fun <T> onConnection(ds: DbDataSource, block: (Connection) -> T): T =
    runBlocking { DbConnectionService.getInstance(project).withConnection(ds, DbConnectionService.Purpose.CONSOLE) { block(it) } }

  fun testIsolationLevelIsSetOnConnect() {
    val ds = h2 { isolation = DbIsolation.SERIALIZABLE }
    assertEquals(DbIsolation.SERIALIZABLE, ds.copy().isolation)
    assertEquals(Connection.TRANSACTION_SERIALIZABLE, onConnection(ds) { it.transactionIsolation })
  }

  fun testDefaultIsolationKeepsTheDrivers() {
    val ds = h2 {}
    assertEquals(DbIsolation.DEFAULT, ds.isolation)
    assertEquals(Connection.TRANSACTION_READ_COMMITTED, onConnection(ds) { it.transactionIsolation })
  }

  fun testReadOnlyDataSourceRefusesWrites() {
    val ds = h2 { readOnly = true }
    val verdict = SqlSafety.check(ds, listOf("SELECT 1", "INSERT INTO t VALUES (1)"))
    assertTrue(verdict.toString(), verdict is SqlSafety.Verdict.Blocked)
    assertTrue((verdict as SqlSafety.Verdict.Blocked).message.contains("read-only"))
    assertEquals(SqlSafety.Verdict.Ok, SqlSafety.check(ds, listOf("SELECT 1", "WITH x AS (SELECT 1) SELECT * FROM x")))
  }
}
