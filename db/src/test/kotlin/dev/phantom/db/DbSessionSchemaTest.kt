// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.db.core.Dbms
import dev.phantom.db.core.exec.DbException
import dev.phantom.db.core.exec.DbSchemaChoice
import dev.phantom.db.core.exec.DbSessionSchema
import dev.phantom.db.core.exec.switchesCatalog
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DatabaseMetaData

/** Schema switching against drivers H2 cannot stand in for: no default catalog, a setter that does nothing. */
class DbSessionSchemaTest : BasePlatformTestCase() {
  companion object {
    init {
      System.setProperty("idea.load.plugins.id", "dev.phantom.db,org.jetbrains.kotlin")
    }
  }

  /** A connection that keeps a catalog and a schema; [ignoresSetters]: like SQL Server's or SQLite's setSchema. */
  private class Fake(var catalog: String?, var schema: String?, val ignoresSetters: Boolean = false) {
    val connection: Connection = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
      when (method.name) {
        "getCatalog" -> catalog
        "getSchema" -> schema
        "setCatalog" -> { if (!ignoresSetters) catalog = args[0] as String; null }
        "setSchema" -> { if (!ignoresSetters) schema = args[0] as String; null }
        "getMetaData" -> error("not needed for a dialect with a known layout")
        "hashCode" -> System.identityHashCode(this)
        "equals" -> false
        "toString" -> "fake"
        else -> error(method.name)
      }
    } as Connection
  }

  fun testDefaultWithoutCatalogNeedsANewConnection() {
    val first = Fake(null, null)
    val second = Fake(null, null)
    val schema = DbSessionSchema()
    var reopened = 0
    assertSame(first.connection, schema.use(Dbms.MYSQL, first.connection, DbSchemaChoice("app")) { error("no reopen") })
    assertEquals("app", first.catalog)
    assertEquals(DbSchemaChoice("app"), schema.applied)
    assertSame(second.connection, schema.use(Dbms.MYSQL, first.connection, DbSchemaChoice.DEFAULT) { reopened++; second.connection })
    assertEquals(1, reopened)
    assertEquals(DbSchemaChoice.DEFAULT, schema.applied)
    assertSame(second.connection, schema.use(Dbms.MYSQL, second.connection, DbSchemaChoice.DEFAULT) { error("already there") })
  }

  fun testDefaultWithoutCatalogAndAnOpenTransactionKeepsTheChoice() {
    val fake = Fake(null, null)
    val schema = DbSessionSchema()
    schema.use(Dbms.MYSQL, fake.connection, DbSchemaChoice("app")) { error("no reopen") }
    val error = runCatching { schema.use(Dbms.MYSQL, fake.connection, DbSchemaChoice.DEFAULT) { throw DbException("pending") } }.exceptionOrNull()
    assertEquals("pending", error?.message)
    assertEquals("the connection is still in app", DbSchemaChoice("app"), schema.applied)
  }

  fun testDefaultGoesBackToWhereTheConnectionOpened() {
    val fake = Fake("db", "public")
    val schema = DbSessionSchema()
    schema.use(Dbms.POSTGRESQL, fake.connection, DbSchemaChoice("audit")) { error("no reopen") }
    assertEquals("audit", fake.schema)
    schema.use(Dbms.POSTGRESQL, fake.connection, DbSchemaChoice.DEFAULT) { error("no reopen") }
    assertEquals("public", fake.schema)
    // after a rollback the switch is made again
    schema.use(Dbms.POSTGRESQL, fake.connection, DbSchemaChoice("audit")) { error("no reopen") }
    fake.schema = "public"
    schema.forget()
    schema.use(Dbms.POSTGRESQL, fake.connection, DbSchemaChoice("audit")) { error("no reopen") }
    assertEquals("audit", fake.schema)
  }

  fun testCatalogOfAChoiceIsSetFirst() {
    val fake = Fake("c1", "dbo")
    DbSessionSchema().use(Dbms.POSTGRESQL, fake.connection, DbSchemaChoice("dbo", "c2")) { error("no reopen") }
    assertEquals("c2" to "dbo", fake.catalog to fake.schema)
  }

  fun testDriverThatIgnoresTheSwitchFails() {
    val fake = Fake("main", null, ignoresSetters = true)
    val schema = DbSessionSchema()
    val error = runCatching { schema.use(Dbms.GENERIC, fake.connection, DbSchemaChoice("other")) { error("no reopen") } }.exceptionOrNull()
    assertTrue(error.toString(), error is DbException && error.message!!.contains("'other'"))
    assertEquals(DbSchemaChoice.DEFAULT, schema.applied)
  }

  fun testGenericDriverWithCatalogsOnlySwitchesCatalogs() {
    fun md(schemas: Boolean, catalogs: Boolean) = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(DatabaseMetaData::class.java)) { _, method, _ ->
      when (method.name) {
        "supportsSchemasInDataManipulation" -> schemas
        "supportsCatalogsInDataManipulation" -> catalogs
        else -> error(method.name)
      }
    } as DatabaseMetaData
    assertTrue(switchesCatalog(Dbms.GENERIC) { md(schemas = false, catalogs = true) })
    assertFalse(switchesCatalog(Dbms.GENERIC) { md(schemas = true, catalogs = true) })
    assertFalse(switchesCatalog(Dbms.GENERIC) { md(schemas = false, catalogs = false) })
    assertTrue(switchesCatalog(Dbms.MARIADB) { error("not asked") })
    assertFalse(switchesCatalog(Dbms.H2) { error("not asked") })
  }
}
