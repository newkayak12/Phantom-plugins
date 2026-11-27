// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.db.core.*
import dev.phantom.db.core.exec.DbConsoles

class SqlDialectTest : BasePlatformTestCase() {
  fun testDialectFollowsAttachedDataSource() {
    val ds = DbDataSource().apply {
      name = "pg"
      dbms = Dbms.POSTGRESQL
      url = "jdbc:postgresql://localhost:5432/postgres"
    }
    DbDataSourceService.getInstance(project).save(ds, CharArray(0))
    try {
      myFixture.configureByText("q.sql", "SELECT 1;")
      val file = myFixture.file.virtualFile
      assertNull(file.getUserData(SqlDialects.KEY))
      val before = myFixture.file

      DbConsoles.attach(project, file, ds.id)
      assertSame(PostgresDialect, file.getUserData(SqlDialects.KEY))
      assertTrue("the reparse waits for the caller to let go of the PSI", before.isValid)
      PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
      assertFalse("a new dialect reparses the file", before.isValid)

      val reparsed = psiManager.findFile(file)!!
      DbConsoles.attach(project, file, ds.id)
      PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
      assertTrue("the same dialect does not reparse", reparsed.isValid)

      DbConsoles.attach(project, file, null)
      assertSame(SqlDialects.ANY, file.getUserData(SqlDialects.KEY))
    }
    finally {
      DbDataSourceService.getInstance(project).remove(ds.id)
    }
  }

  fun testDialectForDbms() {
    assertSame(MySqlDialect, SqlDialects.forDbms(Dbms.MYSQL))
    assertSame(MariaDbDialect, SqlDialects.forDbms(Dbms.MARIADB))
    assertSame(PostgresDialect, SqlDialects.forDbms(Dbms.POSTGRESQL))
    assertSame(H2Dialect, SqlDialects.forDbms(Dbms.H2))
    assertSame(SqlDialects.ANY, SqlDialects.forDbms(Dbms.GENERIC))
    assertSame(SqlDialects.ANY, SqlDialects.forDbms(null))
  }

  fun testAnyIsTheUnionOfTheDialects() {
    val any = SqlDialects.ANY
    assertTrue(any.isReserved("select"))
    // reserved in MySQL only, so a name in ANY
    assertTrue(MySqlDialect.isReserved("rank"))
    assertFalse(any.isReserved("rank"))
    assertTrue(SqlFeature.ON_CONFLICT in any.features)
    assertTrue(SqlFeature.ON_DUPLICATE_KEY in any.features)
    assertTrue(SqlFeature.TOP in any.features)
    assertFalse(SqlFeature.ON_CONFLICT in MySqlDialect.features)
    assertTrue(SqlFeature.RETURNING in MariaDbDialect.features)
    assertFalse(SqlFeature.LATERAL in MariaDbDialect.features)
  }
}
