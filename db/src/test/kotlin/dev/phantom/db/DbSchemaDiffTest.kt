// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.db.core.*
import dev.phantom.db.core.exec.*
import dev.phantom.db.core.ui.DbSchemaCompare
import dev.phantom.db.core.ui.DbSchemaSide
import kotlinx.coroutines.runBlocking

class DbSchemaDiffTest : BasePlatformTestCase() {
  companion object {
    init {
      System.setProperty("idea.load.plugins.id", "dev.phantom.db,org.jetbrains.kotlin")
    }
  }

  private var counter = 0

  private fun h2(): DbDataSource {
    val name = "diff${counter++}"
    val ds = DbDataSource().apply {
      this.name = name
      dbms = Dbms.H2
      url = "jdbc:h2:mem:${name}_${System.nanoTime()};DB_CLOSE_DELAY=-1"
      user = "sa"
    }
    DbDataSourceService.getInstance(project).save(ds, CharArray(0))
    return ds
  }

  private fun exec(ds: DbDataSource, vararg sql: String) {
    val outcomes = runBlocking { DbExecutor.execute(project, ds, sql.toList()) }
    outcomes.filterIsInstance<DbOutcome.Failed>().firstOrNull()?.let { fail(it.sql + ": " + it.message) }
  }

  private fun model(ds: DbDataSource): DbSchemaModel = runBlocking {
    DbSchemaReader.read(project, ds, DbMetadataCache.getInstance(project).schemas(ds).first { it.name == "PUBLIC" })
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

  fun testMigrationMakesTheTargetMatch() {
    val dev = h2()
    val prod = h2()
    exec(dev, "CREATE TABLE users (id BIGINT PRIMARY KEY, email VARCHAR(100) NOT NULL, name VARCHAR(50))",
         "CREATE UNIQUE INDEX users_email ON users (email)",
         "CREATE TABLE orders (id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL, total DECIMAL(10,2))",
         "CREATE INDEX orders_user ON orders (user_id)")
    exec(prod, "CREATE TABLE users (id BIGINT PRIMARY KEY, email VARCHAR(50), legacy INT)",
         "CREATE TABLE audit (id INT)")

    val source = model(dev)
    val target = model(prod)
    assertEquals(setOf("ORDERS", "USERS"), source.tables.keys)
    assertEquals(listOf("ID"), source.tables.getValue("USERS").primaryKey)
    assertEquals(listOf(DbIndexModel("USERS_EMAIL", true, listOf("EMAIL"))), source.tables.getValue("USERS").indexes)
    assertTrue(DbSchemaDiff.dump(source), DbSchemaDiff.dump(source).contains("  EMAIL CHARACTER VARYING(100) NOT NULL\n"))

    val statements = DbSchemaDiff.migration(source, target, Dbms.H2)
    val text = statements.joinToString("\n")
    assertTrue(text, text.contains("CREATE TABLE ORDERS ("))
    assertTrue(text, text.contains("ALTER TABLE USERS ADD NAME CHARACTER VARYING(50);"))
    assertTrue(text, text.contains("ALTER TABLE USERS ALTER COLUMN EMAIL SET DATA TYPE CHARACTER VARYING(100);"))
    assertTrue(text, text.contains("ALTER TABLE USERS ALTER COLUMN EMAIL SET NOT NULL;"))
    assertTrue("drops stay commented out:\n$text", text.contains("-- ALTER TABLE USERS DROP COLUMN LEGACY;") && text.contains("-- DROP TABLE AUDIT;"))

    exec(prod, *statements.flatMap { it.lines() }.filterNot { it.startsWith("--") }.joinToString("\n").split(";\n", ";").map { it.trim() }
      .filter { it.isNotEmpty() }.toTypedArray())
    assertEquals("only the commented drops are left", statements.filter { it.startsWith("--") },
                 DbSchemaDiff.migration(source, model(prod), Dbms.H2))

    val header = DbSchemaCompare.migrationText(DbSchemaSide(dev, DbSchemaRef(dev.id, null, "PUBLIC")),
                                               DbSchemaSide(prod, DbSchemaRef(prod.id, null, "PUBLIC")), listOf("SELECT 1;"))
    assertTrue(header, header.startsWith("-- Makes ${prod.name} · PUBLIC match ${dev.name} · PUBLIC."))
  }

  fun testDialects() {
    val users = DbTableModel("users", listOf(DbColumnModel("id", "bigint", false, null), DbColumnModel("name", "varchar(20)", true, null)),
                             listOf("id"), listOf(DbIndexModel("by_name", false, listOf("name"))))
    val old = users.copy(columns = listOf(DbColumnModel("id", "bigint", false, null), DbColumnModel("name", "varchar(10)", false, null)),
                         indexes = listOf(DbIndexModel("by_name", true, listOf("name"))))
    val source = DbSchemaModel("app", mapOf("users" to users))
    val target = DbSchemaModel("app", mapOf("users" to old))
    assertEquals(listOf("ALTER TABLE users MODIFY COLUMN name varchar(20);", "DROP INDEX by_name ON users;", "CREATE INDEX by_name ON users (name);"),
                 DbSchemaDiff.migration(source, target, Dbms.MYSQL))
    assertEquals(listOf("ALTER TABLE users ALTER COLUMN name TYPE varchar(20);\nALTER TABLE users ALTER COLUMN name DROP NOT NULL;",
                        "DROP INDEX by_name;", "CREATE INDEX by_name ON users (name);"),
                 DbSchemaDiff.migration(source, target, Dbms.POSTGRESQL))
    assertEquals(emptyList<String>(), DbSchemaDiff.migration(source, source, Dbms.POSTGRESQL))
  }
}
