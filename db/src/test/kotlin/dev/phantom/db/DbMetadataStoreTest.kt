// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.db.core.*
import dev.phantom.db.core.exec.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class DbMetadataStoreTest : BasePlatformTestCase() {
  companion object {
    init {
      System.setProperty("idea.load.plugins.id", "dev.phantom.db,org.jetbrains.kotlin")
    }
  }

  private fun sample(id: String): DbStoredMetadata {
    val schema = DbSchemaRef(id, null, "PUBLIC")
    val guest = DbTableRef(id, null, "PUBLIC", "GUEST", "TABLE")
    val stay = DbTableRef(id, null, "PUBLIC", "STAY", "BASE TABLE")
    return DbStoredMetadata(id).apply {
      schemas = listOf(schema, DbSchemaRef(id, "cat", null))
      tables[schema] = listOf(guest, stay)
      columns[guest] = listOf(DbColumnInfo("ID", "BIGINT", false, true), DbColumnInfo("NAME", "VARCHAR(40)", true, false))
      details[guest] = listOf(DbColumnDetail("ID", "BIGINT", false, null, true), DbColumnDetail("NAME", "VARCHAR(40)", true, "'x'", false, true, "a note"))
      primaryKeys[guest] = DbPrimaryKey("PK_GUEST", listOf("ID"))
      foreignKeys[stay] = listOf(DbForeignKey("FK_STAY", stay, listOf("GUEST_ID"), guest, listOf("ID")))
      referencing[guest] = foreignKeys[stay]!!
      indexes[guest] = listOf(DbIndex("IX_NAME", listOf("NAME"), false))
      routines[schema] = listOf(DbRoutine(schema, "nightly", true))
      triggers[schema] = listOf(DbTrigger(schema, "TR", "GUEST", "BEFORE", "INSERT"))
      sequences[schema] = listOf(DbSequence(schema, "SEQ"))
    }
  }

  fun testRoundTrip() {
    val path = Files.createTempDirectory("db-metadata").resolve("ds.json")
    val stored = sample("ds")
    DbMetadataStore.write(path, stored)
    val read = DbMetadataStore.read(path, "ds")!!
    assertEquals(stored.schemas, read.schemas)
    for ((a, b) in listOf(stored.tables to read.tables, stored.columns to read.columns, stored.details to read.details,
                          stored.primaryKeys to read.primaryKeys, stored.foreignKeys to read.foreignKeys, stored.referencing to read.referencing,
                          stored.indexes to read.indexes, stored.routines to read.routines, stored.triggers to read.triggers,
                          stored.sequences to read.sequences)) {
      assertEquals(a.toMap(), b.toMap())
    }
    // another data source's file is not taken for this one
    assertNull(DbMetadataStore.read(path, "other"))
  }

  fun testCorruptFileIsDropped() {
    val dir = Files.createTempDirectory("db-metadata")
    val garbage = Files.writeString(dir.resolve("a.json"), "{not json")
    assertNull(DbMetadataStore.read(garbage, "a"))
    assertFalse(Files.exists(garbage))
    // valid JSON with a table lacking its name: Gson would hand out a null the code never expects
    val partial = Files.writeString(dir.resolve("b.json"), """{"version":${DbMetadataStore.VERSION},"dataSourceId":"b","schemaParts":[],""" +
                                                           """"tableParts":[{"table":{"dataSourceId":"b","kind":"TABLE"}}]}""")
    assertNull(DbMetadataStore.read(partial, "b"))
    assertFalse(Files.exists(partial))
  }

  fun testOtherVersionIsDropped() {
    val path = Files.createTempDirectory("db-metadata").resolve("ds.json")
    DbMetadataStore.write(path, sample("ds"))
    Files.writeString(path, Files.readString(path).replace("\"version\":${DbMetadataStore.VERSION}", "\"version\":${DbMetadataStore.VERSION + 1}"))
    assertNull(DbMetadataStore.read(path, "ds"))
    assertFalse(Files.exists(path))
  }

  private fun introspect(ds: DbDataSource) = runBlocking {
    val cache = DbMetadataCache.getInstance(project)
    DbExecutor.execute(project, ds, listOf(
      "CREATE TABLE guest (id BIGINT PRIMARY KEY, name VARCHAR(40))",
      "CREATE TABLE reservation (id BIGINT PRIMARY KEY, guest_id BIGINT)",
      "CREATE SCHEMA audit",
      "CREATE TABLE audit.log_entry (id BIGINT)",
    ))
    for (schema in cache.schemas(ds).filter { it.name in setOf("PUBLIC", "AUDIT") }) {
      for (table in cache.tables(ds, schema)) cache.columns(ds, table)
    }
  }

  /** After a restart the stored copy serves completion without a connection; Refresh then replaces it schema by schema. */
  fun testStoredMetadataServesCompletionOfflineAndRefreshReplacesIt() {
    val ds = DbDataSource().apply {
      name = "stored"
      dbms = Dbms.H2
      url = "jdbc:h2:mem:stored_${System.nanoTime()};DB_CLOSE_DELAY=-1"
      user = "sa"
    }
    val sources = DbDataSourceService.getInstance(project)
    sources.save(ds, CharArray(0))
    val cache = DbMetadataCache.getInstance(project)
    val store = DbMetadataStore.getInstance()
    try {
      introspect(ds)
      val file = store.file(ds.id)
      store.flush(ds.id)
      assertTrue(Files.isRegularFile(file))
      assertFalse(Files.readString(file).contains("sa\""))

      // a restart without the database: memory empty, the file read again off the EDT, the connection failing
      DbConnectionService.getInstance(project).disconnect(ds.id)
      val url = ds.url
      ds.url = "jdbc:h2:mem:missing_${System.nanoTime()};IFEXISTS=TRUE"
      cache.invalidate(ds.id, keepStored = true)
      store.unload(ds.id)
      ApplicationManager.getApplication().executeOnPooledThread { store.get(ds.id) }.get(10, TimeUnit.SECONDS)
      assertSameElements(cache.cachedTables(ds.id).map { it.name }, "GUEST", "RESERVATION", "LOG_ENTRY")
      val file1 = myFixture.configureByText("q.sql", "SELECT r.<caret> FROM reservation r")
      DbConsoles.attach(project, file1.virtualFile, ds.id)
      myFixture.completeBasic()
      assertSameElements(myFixture.lookupElementStrings!!, "ID", "GUEST_ID")

      // back online, RESERVATION dropped behind the cache's back, Refresh: only what is loaded again is replaced
      ds.url = url
      runBlocking {
        DbConnectionService.getInstance(project).withConnection(ds, DbConnectionService.Purpose.CONSOLE) { c ->
          c.createStatement().use { it.execute("DROP TABLE reservation") }
        }
      }
      cache.invalidate(ds.id, keepStored = true)
      val public = runBlocking { cache.schemas(ds) }.single { it.name == "PUBLIC" }
      runBlocking { cache.tables(ds, public) }
      store.flush(ds.id)
      store.unload(ds.id)
      val stored = ApplicationManager.getApplication().executeOnPooledThread<DbStoredMetadata> { store.get(ds.id) }.get(10, TimeUnit.SECONDS)
      assertSameElements(stored.tables[public]!!.map { it.name }, "GUEST")
      assertSameElements(stored.columns.keys.map { it.name }, "GUEST", "LOG_ENTRY")
      assertSameElements(stored.tables.values.flatten().map { it.name }, "GUEST", "LOG_ENTRY")

      // a data source removed or changed forgets its stored copy
      cache.invalidate(ds.id)
      store.flush(ds.id)
      assertFalse(Files.exists(file))
    }
    finally {
      DbConnectionService.getInstance(project).disconnect(ds.id)
      cache.invalidate(ds.id)
      sources.remove(ds.id)
    }
  }
}
