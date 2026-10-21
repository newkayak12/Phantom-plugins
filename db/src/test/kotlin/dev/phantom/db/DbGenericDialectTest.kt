// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.db.core.*
import dev.phantom.db.core.drivers.DbDriverDefinition
import dev.phantom.db.core.drivers.DbDriverRegistry
import dev.phantom.db.core.exec.*
import dev.phantom.db.core.ui.DbDataSourceDialog
import java.sql.Types

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

  private fun warningsFor(dbms: Dbms): List<String> {
    val ds = DbDataSource().apply {
      name = "bound_${dbms.name}"
      this.dbms = dbms
      url = "jdbc:h2:mem:bound_${System.nanoTime()};DB_CLOSE_DELAY=-1"
      user = "sa"
      driverJars = arrayListOf(DbConsoleTest.h2Jar.toString())
      // the url alone would resolve to the H2 preset, so bind the driver whose dialect is under test
      driverId = if (dbms == Dbms.GENERIC) "generic-test" else dbms.name.lowercase()
    }
    if (dbms == Dbms.GENERIC) {
      DbDriverRegistry.getInstance().save(DbDriverDefinition().apply { id = "generic-test"; name = "Generic"; driverClass = "org.h2.Driver" })
    }
    DbDataSourceService.getInstance(project).save(ds, CharArray(0))
    try {
      myFixture.enableInspections(SqlFunctionArgumentsInspection::class.java, SqlReservedIdentifierInspection::class.java,
                                  SqlIfFunctionInspection::class.java)
      val file = myFixture.configureByText("g.sql", "CREATE TABLE user (id INT)")
      DbConsoles.attach(project, file.virtualFile, ds.id)
      return myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING).filter { it.inspectionToolId?.startsWith("PhantomSql") == true }.map { it.description.orEmpty() }
    }
    finally {
      DbConnectionService.getInstance(project).disconnect(ds.id)
      DbDataSourceService.getInstance(project).remove(ds.id)
      DbDriverRegistry.getInstance().remove("generic-test")
    }
  }

  fun testDialectInspectionsSkipGeneric() {
    // control: a PostgreSQL data source reserves `user`, so the same text warns there
    assertNotEmpty(warningsFor(Dbms.POSTGRESQL))
    assertEmpty(warningsFor(Dbms.GENERIC))
  }

  fun testGridIsReadOnlyWithoutDialect() {
    val columns = listOf(DbColumn("id", Types.INTEGER, "INT", null))
    val name = DbTableName(null, "t")
    assertNotNull(DbEditTargets.build(Dbms.H2, "\"", name, null, "T", listOf("id"), listOf("id"), columns))
    assertNull(DbEditTargets.build(Dbms.GENERIC, "\"", name, null, "T", listOf("id"), listOf("id"), columns))
  }

  fun testDialogKeepsUrlTypedForOther() {
    assertFalse(DbDataSourceDialog.replacesUrl("jdbc:redshift://x:5439/dev", Dbms.GENERIC))
    assertTrue(DbDataSourceDialog.replacesUrl("jdbc:mysql://localhost:3306/", Dbms.GENERIC))
    assertTrue(DbDataSourceDialog.replacesUrl("", Dbms.GENERIC))
    assertTrue(DbDataSourceDialog.replacesUrl("jdbc:redshift://x:5439/dev", Dbms.MYSQL))
    assertFalse(DbDataSourceDialog.replacesUrl("jdbc:mysql://h/db", Dbms.MYSQL))
  }

  fun testGenericHasNoDriverToFind() {
    val ds = DbDataSource().apply { dbms = Dbms.GENERIC; url = "jdbc:nothing:x" }
    assertNull(DbDrivers.fromLocalCaches(DbDriverDefinition().apply { dialect = Dbms.GENERIC }))
    assertNull(DbDrivers.find(null, ds))
  }
}
