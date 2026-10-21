// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.db.core.*
import dev.phantom.db.core.exec.*

class DbGenericDialectTest : BasePlatformTestCase() {
  companion object {
    init {
      System.setProperty("idea.load.plugins.id", "dev.phantom.db,org.jetbrains.kotlin")
    }
  }

  fun testGenericHasNoDialectFeatures() {
    assertFalse(Dbms.GENERIC.hasDialect)
    assertTrue(Dbms.entries.filter { it != Dbms.GENERIC }.all { it.hasDialect })
    assertNull(DbExplain.sql(Dbms.GENERIC, "SELECT 1", analyze = false))
    assertEquals("\"a b\"", DbNames.quote(Dbms.GENERIC, "a b"))
    assertNull(Dbms.forUrl("jdbc:redshift://x:5439/dev"))
    assertEquals(Dbms.MYSQL, Dbms.forUrl("jdbc:mysql://h/db"))
  }

  fun testDialectInspectionsSkipGeneric() {
    val ds = DbDataSource().apply {
      name = "generic"
      dbms = Dbms.GENERIC
      url = "jdbc:h2:mem:generic_${System.nanoTime()};DB_CLOSE_DELAY=-1"
      user = "sa"
      driverJars = arrayListOf(DbConsoleTest.h2Jar.toString())
    }
    DbDataSourceService.getInstance(project).save(ds, CharArray(0))
    try {
      myFixture.enableInspections(SqlFunctionArgumentsInspection::class.java, SqlReservedIdentifierInspection::class.java,
                                  SqlIfFunctionInspection::class.java)
      val file = myFixture.configureByText("g.sql", "CREATE TABLE `order` (id INT)")
      DbConsoles.attach(project, file.virtualFile, ds.id)
      assertEmpty(myFixture.doHighlighting(HighlightSeverity.WARNING))
    }
    finally {
      DbConnectionService.getInstance(project).disconnect(ds.id)
      DbDataSourceService.getInstance(project).remove(ds.id)
    }
  }

  fun testGenericHasNoDriverToFind() {
    val ds = DbDataSource().apply { dbms = Dbms.GENERIC; url = "jdbc:nothing:x" }
    assertNull(DbDrivers.fromLocalCaches(Dbms.GENERIC))
    assertNull(DbDrivers.find(null, ds))
  }
}
