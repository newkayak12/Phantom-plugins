// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
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

  fun testGridIsReadOnlyWithoutDialect() {
    val columns = listOf(DbColumn("id", Types.INTEGER, "INT", null))
    val name = DbTableName(null, "t")
    assertNotNull(DbEditTargets.build(Dbms.H2, "\"", name, null, "T", listOf("id"), listOf("id"), columns))
    assertNull(DbEditTargets.build(Dbms.GENERIC, "\"", name, null, "T", listOf("id"), listOf("id"), columns))
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
}
