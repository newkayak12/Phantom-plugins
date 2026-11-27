// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.db.core.*
import dev.phantom.db.core.drivers.DbDriverDefinition
import dev.phantom.db.core.drivers.DbDriverRegistry
import dev.phantom.db.core.exec.*
import kotlinx.coroutines.runBlocking
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

  fun testGridEditsWithoutDialectUseDriverQuotes() {
    val columns = listOf(DbColumn("id", Types.INTEGER, "INT", null), DbColumn("name", Types.VARCHAR, "VARCHAR", null))
    val target = DbEditTargets.build(Dbms.GENERIC, "`", DbTableName("app", "t"), "app", "T", listOf("id"), listOf("id", "name"), columns)!!
    assertEquals("UPDATE `app`.`T` SET `name` = ? WHERE `id` = ?", target.update(arrayOf(1, "a"), 1, "b").sql)
    // standard SQL for a row of defaults; MySQL's `() VALUES ()` is not
    assertEquals("INSERT INTO `app`.`T` DEFAULT VALUES", target.insert(emptyMap()).sql)
    assertNull(DbEditTargets.build(Dbms.GENERIC, "\"", DbTableName(null, "t"), null, "T", emptyList(), listOf("id"), columns))
  }

  fun testGenericResultIsEditableThroughMetadata() {
    val ds = genericH2()
    val outcomes = runBlocking {
      DbExecutor.execute(project, ds, listOf("CREATE TABLE \"Item\"(id INT PRIMARY KEY, name VARCHAR(20))", "INSERT INTO \"Item\" VALUES (1, 'a')",
                                            "SELECT id, name FROM \"Item\""))
    }
    val rows = outcomes[2] as DbOutcome.Rows
    val target = runBlocking { DbEditTargets.resolve(project, ds, rows) }!!
    assertEquals("UPDATE \"Item\" SET \"NAME\" = ? WHERE \"ID\" = ?", target.update(arrayOf(1, "a"), 1, "b").sql)
    ds.readOnly = true
    assertNull(runBlocking { DbEditTargets.resolve(project, ds, rows) })
  }

  /** Copy Table To between databases without a dialect: the table is created with standard types and filled. */
  fun testCopyTableBetweenGenericDataSources() {
    val source = genericH2()
    val target = genericH2()
    val made = runBlocking {
      DbExecutor.execute(project, source, listOf("CREATE TABLE guest (id INT PRIMARY KEY, name VARCHAR(20), born DATE)",
                                                "INSERT INTO guest VALUES (1, 'a', DATE '2000-01-01'), (2, 'b', NULL)"))
    }
    assertTrue(made.toString(), made.none { it is DbOutcome.Failed })
    val cache = DbMetadataCache.getInstance(project)
    val table = runBlocking { cache.tables(source, cache.schemas(source).first { it.name == "PUBLIC" }) }.first { it.name == "GUEST" }
    val schema = runBlocking { cache.schemas(target) }.first { it.name == "PUBLIC" }
    val result = runBlocking { DbTableCopy.run(project, source, table, target, schema) }
    assertTrue(result.created)
    assertEquals(2L, result.rows)
    val copied = runBlocking { DbExecutor.execute(project, target, listOf("SELECT name FROM guest ORDER BY id")) }.single() as DbOutcome.Rows
    assertEquals(listOf("a", "b"), copied.rows.map { it[0] })
  }

  /** Modify Table without a dialect: adding and dropping columns and creating indexes; anything else is refused by name. */
  fun testModifyTableWithoutDialectWritesOnlyStandardDdl() {
    val t = DbTableRef("x", null, "app", "orders", "TABLE")
    val id = DbAlter.ColumnDef("id", "id", "INT", false, null)
    val old = DbAlter.ColumnDef("old", "old", "INT", true, null)
    val added = DbAlter.ColumnDef(null, "note", "VARCHAR(20)", false, "'n'")
    assertEquals(listOf("ALTER TABLE app.orders DROP COLUMN old", "ALTER TABLE app.orders ADD note VARCHAR(20) DEFAULT 'n' NOT NULL",
                        "CREATE UNIQUE INDEX ux_note ON app.orders (note)"),
                 DbAlter.statements(Dbms.GENERIC, t, listOf(id, old), listOf(id, added), emptyList(),
                                    listOf(DbAlter.IndexDef(null, "ux_note", listOf("note"), true))))
    fun refused(block: () -> Unit, name: String) {
      val e = runCatching(block).exceptionOrNull()
      assertTrue(e.toString(), e is DbException && e.message!!.endsWith(name))
    }
    refused({ DbAlter.statements(Dbms.GENERIC, t, listOf(id, old), listOf(id, old.copy(type = "BIGINT"))) }, "old")
    refused({ DbAlter.statements(Dbms.GENERIC, t, listOf(id, old), listOf(id, old.copy(name = "renamed"))) }, "old")
    refused({ DbAlter.statements(Dbms.GENERIC, t, listOf(id), listOf(id, added.copy(comment = "c"))) }, "note")
    refused({ DbAlter.statements(Dbms.GENERIC, t, listOf(id), listOf(id), listOf(DbAlter.IndexDef("ix", "ix", listOf("id"), false)), emptyList()) }, "ix")
  }

  private fun genericH2(): DbDataSource {
    DbDriverRegistry.getInstance().save(DbDriverDefinition().apply {
      id = "my-h2"; name = "My H2"; driverClass = "org.h2.Driver"
      urlPrefixes = mutableListOf("jdbc:h2:"); artifacts = mutableListOf("com.h2database:h2"); dialect = Dbms.GENERIC
    })
    val ds = DbDataSource().apply {
      name = "generic-h2"; driverId = "my-h2"; user = "sa"; url = "jdbc:h2:mem:generic_${System.nanoTime()};DB_CLOSE_DELAY=-1"
    }
    DbDataSourceService.getInstance(project).save(ds, CharArray(0))
    assertEquals(Dbms.GENERIC, ds.dialect())
    return ds
  }

  fun testGenericHasNoDriverToFind() {
    val ds = DbDataSource().apply { dbms = Dbms.GENERIC; url = "jdbc:nothing:x" }
    assertNull(DbDrivers.fromLocalCaches(DbDriverDefinition().apply { dialect = Dbms.GENERIC }))
    assertNull(DbDrivers.find(null, ds))
  }

  override fun tearDown() {
    try {
      val service = DbDataSourceService.getInstance(project)
      service.dataSources.forEach {
        DbConnectionService.getInstance(project).disconnect(it.id)
        service.remove(it.id)
      }
      DbDriverRegistry.getInstance().remove("my-h2")
    }
    catch (e: Throwable) {
      addSuppressedException(e)
    }
    finally {
      super.tearDown()
    }
  }

  fun testGenericUserDriverRunsConsoleAndTree() {
    DbDriverRegistry.getInstance().save(DbDriverDefinition().apply {
      id = "my-h2"; name = "My H2"; driverClass = "org.h2.Driver"
      urlPrefixes = mutableListOf("jdbc:h2:"); artifacts = mutableListOf("com.h2database:h2"); dialect = Dbms.GENERIC
    })
    val ds = DbDataSource().apply {
      name = "generic-h2"; driverId = "my-h2"; user = "sa"; url = "jdbc:h2:mem:generic_${System.nanoTime()};DB_CLOSE_DELAY=-1"
    }
    DbDataSourceService.getInstance(project).save(ds, CharArray(0))
    val outcomes = runBlocking { DbExecutor.execute(project, ds, listOf("CREATE TABLE t(id INT)", "SELECT * FROM t")) }
    assertTrue(outcomes[0] is DbOutcome.Updated)
    assertTrue(outcomes[1] is DbOutcome.Rows)
    val cache = DbMetadataCache.getInstance(project)
    val schemas = runBlocking { cache.schemas(ds) }
    assertNotEmpty(schemas)
    assertTrue(schemas.any { s -> runBlocking { cache.tables(ds, s) }.any { it.name.equals("T", true) } })
  }

  fun testPropertiesMergeOrderAndIam() {
    val def = DbDriverRegistry.getInstance().find("aws-wrapper-mysql")!!
    val ds = DbDataSource().apply {
      authKind = DbAuthKind.AWS_IAM; user = "app"; awsProfile = "dev"; awsRegion = ""
      properties = mutableMapOf("sslMode" to "REQUIRED")
    }
    val p = DbConnectionService.connectionProperties(def, ds, password = "x")
    assertEquals("iam", p["wrapperPlugins"])
    assertEquals("dev", p["awsProfile"])
    assertNull(p["iamRegion"])
    assertEquals("REQUIRED", p["sslMode"])
    assertEquals("app", p["user"])
    assertNull(p["password"])
  }

  fun testPasswordAuthAndNoneAuth() {
    val def = DbDriverRegistry.getInstance().find("postgresql")!!
    val ds = DbDataSource().apply { user = "u"; properties = mutableMapOf("connectTimeout" to "3") }
    val p = DbConnectionService.connectionProperties(def, ds, password = null)
    assertEquals("u", p["user"])
    assertNull(p["password"])
    assertEquals("3", p["connectTimeout"])
    ds.authKind = DbAuthKind.NONE
    val none = DbConnectionService.connectionProperties(def, ds, password = "x")
    assertNull(none["user"])
    assertNull(none["password"])
  }

  fun testRedshiftIamRewritesUrl() {
    val def = DbDriverRegistry.getInstance().find("redshift")!!
    val ds = DbDataSource().apply { authKind = DbAuthKind.AWS_IAM; url = "jdbc:redshift://c:5439/dev" }
    assertEquals("jdbc:redshift:iam://c:5439/dev", connectionUrl(def, ds))
    ds.authKind = DbAuthKind.PASSWORD
    assertEquals("jdbc:redshift://c:5439/dev", connectionUrl(def, ds))
  }

  fun testRejectedUrlNamesTheRightDriver() {
    val registry = DbDriverRegistry.getInstance()
    assertEquals("aws-mysql-jdbc", registry.forUrl("jdbc:mysql:aws://db-1.rds.amazonaws.com:3306/app")?.id)
    val wrapper = registry.find("aws-wrapper-mysql")!!
    val other = rejectedUrlMessage(wrapper, "jdbc:mysql:aws://db-1.rds.amazonaws.com:3306/app", "x.jar")
    assertTrue(other, other.contains("AWS JDBC Driver for MySQL"))
    assertTrue(other, other.contains("jdbc:aws-wrapper:mysql://"))
    val expected = rejectedUrlMessage(wrapper, "jdbc:nothing://h", "x.jar")
    assertTrue(expected, expected.contains("jdbc:aws-wrapper:mysql://{host}:{port}/{database}"))
  }

  fun testUnknownDriverErrorMentionsDriver() {
    val ds = DbDataSource().apply { driverId = "nope"; url = "jdbc:nope://x"; dbms = Dbms.GENERIC }
    val e = runCatching { DbConnectionService.getInstance(project).open(ds, "") }.exceptionOrNull()
    assertTrue(e is DbException)
    assertTrue(e!!.message, e.message!!.contains("nope"))
  }

  fun testUnknownDriverIdDoesNotFallBackToBuiltInByDbms() {
    val ds = DbDataSource().apply { driverId = "my-wrapper"; url = "jdbc:nope://x"; dbms = Dbms.MYSQL }
    assertNull(ds.driver())
    assertEquals(Dbms.MYSQL, ds.dialect())
    val e = runCatching { DbConnectionService.getInstance(project).open(ds, "") }.exceptionOrNull()
    assertTrue(e is DbException)
    assertTrue(e!!.message, e.message!!.contains("my-wrapper"))
  }

  fun testMissingDriverFilesThrowWithoutDownloading() {
    val registry = DbDriverRegistry.getInstance()
    registry.save(DbDriverDefinition().apply {
      id = "my-h2"; name = "Ghost"; driverClass = "x.Ghost"; urlPrefixes = mutableListOf("jdbc:ghost:")
      artifacts = mutableListOf("com.nonexistent.phantomtest:ghost-driver"); dialect = Dbms.GENERIC
    })
    val ds = DbDataSource().apply { driverId = "my-h2"; url = "jdbc:ghost://x" }
    val e = runCatching { DbConnectionService.getInstance(project).open(ds, "") }.exceptionOrNull()
    assertTrue(e is DbException)
    assertEquals(PhantomDbBundle.message("db.driver.missing.download", "Ghost"), e!!.message)
    assertEmpty(registry.find("my-h2")!!.resolvedJars)
    assertFalse(java.nio.file.Files.exists(dev.phantom.db.core.drivers.DbDriverDownloader.defaultDir().resolve("com.nonexistent.phantomtest")))
  }

  fun testTestConnectionErrorIsInlineOnly() {
    val shown = mutableListOf<com.intellij.notification.Notification>()
    project.messageBus.connect(testRootDisposable).subscribe(com.intellij.notification.Notifications.TOPIC, object : com.intellij.notification.Notifications {
      override fun notify(notification: com.intellij.notification.Notification) {
        shown += notification
      }
    })
    val connections = DbConnectionService.getInstance(project)
    val ds = DbDataSource().apply { id = "inline-${System.nanoTime()}"; driverId = "nope-inline"; url = "jdbc:nope://x" }
    assertTrue(runCatching { connections.testConnection(ds, "") }.exceptionOrNull() is DbException)
    com.intellij.testFramework.PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    assertEmpty(shown)
    // the console path still offers the balloon
    assertTrue(runCatching { connections.open(ds, "") }.exceptionOrNull() is DbException)
    com.intellij.testFramework.PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    assertSize(1, shown)
    shown.forEach { it.expire() }
  }

  fun testContextClassLoaderIsSetAndRestored() {
    val before = Thread.currentThread().contextClassLoader
    val loader = java.net.URLClassLoader(arrayOf(), null)
    assertSame(loader, withContextClassLoader(loader) { Thread.currentThread().contextClassLoader })
    assertSame(before, Thread.currentThread().contextClassLoader)
    assertTrue(runCatching { withContextClassLoader(loader) { error("driver failed") } }.isFailure)
    assertSame(before, Thread.currentThread().contextClassLoader)
  }

  fun testH2ConnectRestoresContextClassLoader() {
    val before = Thread.currentThread().contextClassLoader
    val ds = DbDataSource().apply { driverId = "h2"; user = "sa"; url = "jdbc:h2:mem:tccl_${System.nanoTime()}" }
    DbConnectionService.getInstance(project).open(ds, "").use { assertTrue(it.isValid(5)) }
    assertSame(before, Thread.currentThread().contextClassLoader)
  }
}
