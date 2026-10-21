// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db

import com.intellij.openapi.util.JDOMUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.XmlSerializer
import dev.phantom.db.core.Dbms
import dev.phantom.db.core.drivers.DbDriverDefinition
import dev.phantom.db.core.drivers.DbDriverRegistry
import dev.phantom.db.core.dialect
import dev.phantom.db.core.driver

class DbDriverRegistryTest : BasePlatformTestCase() {
  companion object {
    init {
      System.setProperty("idea.load.plugins.id", "dev.phantom.db,org.jetbrains.kotlin")
    }
  }

  private lateinit var savedState: DbDriverRegistry.State

  override fun setUp() {
    super.setUp()
    savedState = roundTrip(DbDriverRegistry.getInstance().state)
  }

  override fun tearDown() {
    try {
      DbDriverRegistry.getInstance().loadState(savedState)
    }
    finally {
      super.tearDown()
    }
  }

  private fun roundTrip(state: DbDriverRegistry.State): DbDriverRegistry.State {
    val xml = JDOMUtil.write(XmlSerializer.serialize(state))
    return XmlSerializer.deserialize(JDOMUtil.load(xml), DbDriverRegistry.State::class.java)
  }

  fun testBuiltInsLoad() {
    val ids = DbDriverRegistry.builtIns.map { it.id }
    assertContainsElements(ids, "mysql", "mariadb", "postgresql", "h2", "aws-wrapper-mysql", "aws-mysql-jdbc", "aws-wrapper-postgresql",
                           "redshift", "athena", "oracle", "sqlserver", "sqlite", "clickhouse", "snowflake")
    assertTrue(DbDriverRegistry.builtIns.all { it.builtIn && it.driverClass.isNotBlank() })
    val redshift = DbDriverRegistry.builtIns.single { it.id == "redshift" }
    assertEquals("jdbc:redshift:iam://", redshift.iamUrlTo)
    assertEquals("{profile}", redshift.iamProperties["Profile"])
    assertEquals(Dbms.GENERIC, redshift.dialect)
    assertEquals(listOf("jdbc:clickhouse:", "jdbc:ch:"), DbDriverRegistry.builtIns.single { it.id == "clickhouse" }.urlPrefixes)
    assertEquals(3, DbDriverRegistry.builtIns.single { it.id == "aws-wrapper-mysql" }.artifacts.size)
  }

  fun testForUrlPicksLongestPrefix() {
    val r = DbDriverRegistry.getInstance()
    assertEquals("aws-wrapper-mysql", r.forUrl("jdbc:aws-wrapper:mysql://h:3306/x")?.id)
    assertEquals("mysql", r.forUrl("jdbc:p6spy:mysql://h/x")?.id)
    assertEquals("redshift", r.forUrl("jdbc:redshift:iam://c/dev")?.id)
    assertNull(r.forUrl("jdbc:unknown://x"))
  }

  fun testBuiltInFor() {
    val r = DbDriverRegistry.getInstance()
    assertEquals("mysql", r.builtInFor(Dbms.MYSQL)?.id)
    assertEquals("h2", r.builtInFor(Dbms.H2)?.id)
    assertNull(r.builtInFor(Dbms.GENERIC))
  }

  fun testUserDriverAndOverrideRoundTrip() {
    val r = DbDriverRegistry.getInstance()
    val user = DbDriverDefinition().apply {
      id = "custom"; name = "Custom"; driverClass = "x.Y"; urlPrefixes = mutableListOf("jdbc:custom:")
      defaultProperties = mutableMapOf("a" to "b")
    }
    val override = r.find("mysql")!!.copy().apply { defaultProperties = mutableMapOf("useSSL" to "false") }
    r.save(user)
    r.save(override)

    val fresh = DbDriverRegistry()
    fresh.loadState(roundTrip(r.state))
    assertEquals(mapOf("a" to "b"), fresh.find("custom")?.defaultProperties)
    assertFalse(fresh.find("custom")!!.builtIn)
    assertEquals(mapOf("useSSL" to "false"), fresh.find("mysql")?.defaultProperties)
    assertTrue(fresh.find("mysql")!!.builtIn)
    assertEquals(DbDriverRegistry.builtIns.size + 1, fresh.all.size)
    assertEquals("custom", fresh.all.last().id)

    fresh.remove("mysql")
    assertNotNull(fresh.find("mysql"))
    assertEquals(DbDriverRegistry.builtIns.single { it.id == "mysql" }.defaultProperties, fresh.find("mysql")?.defaultProperties)

    fresh.save(override)
    fresh.resetToDefault("mysql")
    assertTrue(fresh.find("mysql")!!.defaultProperties.isEmpty())

    fresh.remove("custom")
    assertNull(fresh.find("custom"))
  }

  fun testPinIsKeptApartFromTheDefinition() {
    val r = DbDriverRegistry.getInstance()
    assertTrue(r.pin("mysql", "9.1.0", listOf("/x/mysql-connector-j-9.1.0.jar")))
    assertTrue("a pin is no edit of the built-in", r.state.overrides.none { it.id == "mysql" })
    assertEquals("9.1.0", r.find("mysql")!!.resolvedVersion)
    assertEquals(listOf("/x/mysql-connector-j-9.1.0.jar"), r.find("mysql")!!.resolvedJars)

    r.save(r.find("mysql")!!.copy().apply { defaultProperties["useSSL"] = "false" })
    assertTrue(r.state.overrides.single { it.id == "mysql" }.resolvedJars.isEmpty())
    r.resetToDefault("mysql")
    assertEquals("reset keeps the downloaded jars", "9.1.0", r.find("mysql")!!.resolvedVersion)
    r.remove("mysql")
    assertEquals("9.1.0", r.find("mysql")!!.resolvedVersion)

    val fresh = DbDriverRegistry()
    fresh.loadState(roundTrip(r.state))
    assertEquals(listOf("/x/mysql-connector-j-9.1.0.jar"), fresh.find("mysql")!!.resolvedJars)

    r.save(DbDriverDefinition().apply { id = "mine"; name = "Mine"; driverClass = "x.Y"; resolvedVersion = "1"; resolvedJars = mutableListOf("/m.jar") })
    assertEquals("1", r.find("mine")!!.resolvedVersion)
    r.remove("mine")
    assertTrue("removing a user driver drops its pin", r.state.pins.none { it.id == "mine" })
    assertFalse("no pin for a driver that is gone", r.pin("mine", "1", listOf("/m.jar")))
    assertNull(r.find("mine"))

    assertTrue(r.pin("mysql", null, emptyList()))
    assertNull(r.find("mysql")!!.resolvedVersion)
    assertTrue(r.state.pins.none { it.id == "mysql" })
  }

  fun testPinsStoredInDefinitionsMoveOnLoad() {
    // as the 0.11.0 development builds wrote it: the pin inside a full copy of the built-in
    val old = DbDriverRegistry.State().apply {
      overrides.add(DbDriverRegistry.builtIns.single { it.id == "postgresql" }.copy().apply { resolvedVersion = "42.7.4"; resolvedJars = mutableListOf("/pg.jar") })
      drivers.add(DbDriverDefinition().apply { id = "u"; name = "U"; driverClass = "x.Y"; resolvedVersion = "2"; resolvedJars = mutableListOf("/u.jar") })
    }
    val r = DbDriverRegistry()
    r.loadState(roundTrip(old))
    assertEquals("42.7.4", r.find("postgresql")!!.resolvedVersion)
    assertEquals(listOf("/u.jar"), r.find("u")!!.resolvedJars)
    assertTrue(r.state.overrides.all { it.resolvedJars.isEmpty() && it.resolvedVersion == null })
    assertTrue(r.state.drivers.all { it.resolvedJars.isEmpty() })
    assertEquals(setOf("postgresql", "u"), r.state.pins.map { it.id }.toSet())
    assertEquals("no override is created", 1, r.state.overrides.size)
  }

  fun testDownloadPinsOnlyJarsToTheDriverAsItIsNow() {
    val r = DbDriverRegistry.getInstance()
    r.save(DbDriverDefinition().apply { id = "dl"; name = "Before"; driverClass = "x.Y"; artifacts = mutableListOf("x:y") })
    val started = r.find("dl")!!
    val pinned = dev.phantom.db.core.drivers.DbDriverActions.downloadAndPin(started, com.intellij.openapi.progress.EmptyProgressIndicator()) { d, _ ->
      r.save(r.find("dl")!!.copy().apply { name = "Edited meanwhile" })
      d.copy().apply { name = "Stale"; resolvedVersion = "3"; resolvedJars = mutableListOf("/dl.jar") }
    }
    assertTrue(pinned)
    assertEquals("Edited meanwhile", r.find("dl")!!.name)
    assertEquals("3", r.find("dl")!!.resolvedVersion)

    val gone = dev.phantom.db.core.drivers.DbDriverActions.downloadAndPin(r.find("dl")!!, com.intellij.openapi.progress.EmptyProgressIndicator()) { d, _ ->
      r.remove("dl")
      d.copy().apply { resolvedVersion = "4"; resolvedJars = mutableListOf("/dl4.jar") }
    }
    assertFalse(gone)
    assertNull("a removed driver is not brought back", r.find("dl"))
    assertTrue(r.state.pins.none { it.id == "dl" })
  }

  fun testSnapshotIsSharedAndSafeToReadWhileSaving() {
    val r = DbDriverRegistry.getInstance()
    assertSame("no rebuild per call", r.all, r.all)
    assertSame(r.all.first(), r.find(r.all.first().id))
    val before = r.all
    r.save(DbDriverDefinition().apply { id = "t0"; name = "T0"; driverClass = "x.Y" })
    assertNotSame(before, r.all)
    assertNull("an old snapshot does not change", before.firstOrNull { it.id == "t0" })
    assertTrue(runCatching { (r.all as MutableList<DbDriverDefinition>).add(DbDriverDefinition()) }.isFailure)

    val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
    val stop = java.util.concurrent.atomic.AtomicBoolean()
    val readers = (1..4).map {
      Thread {
        while (!stop.get()) {
          try {
            r.all.forEach { d -> d.urlPrefixes.size }
            r.forUrl("jdbc:mysql://h/x")
            com.intellij.util.xmlb.XmlSerializer.serialize(r.state)
          }
          catch (e: Throwable) {
            failures += e
          }
        }
      }.apply { start() }
    }
    repeat(300) { i -> r.save(DbDriverDefinition().apply { id = "t${i % 5}"; name = "T$i"; driverClass = "x.Y"; urlPrefixes = mutableListOf("jdbc:t$i:") }) }
    stop.set(true)
    readers.forEach { it.join() }
    (0 until 5).forEach { r.remove("t$it") }
    assertEmpty(failures.map { it.toString() })
  }

  fun testListenerFiresOnChange() {
    val r = DbDriverRegistry.getInstance()
    var count = 0
    r.addListener(testRootDisposable) { count++ }
    r.save(DbDriverDefinition().apply { id = "c2"; name = "C2"; driverClass = "x.Y" })
    r.remove("c2")
    assertEquals(2, count)
  }

  fun testOldStateMigratesToBuiltInDriver() {
    // state as 0.10.0 wrote it: no driverId
    val old = dev.phantom.db.core.DbDataSourceService.State().apply {
      dataSources.add(dev.phantom.db.core.DbDataSource().apply { id = "a"; name = "pg"; dbms = Dbms.POSTGRESQL; url = "jdbc:postgresql://h/x" })
    }
    val xml = JDOMUtil.write(XmlSerializer.serialize(old))
    assertFalse(xml, xml.contains("name=\"driverId\" value"))
    val state = XmlSerializer.deserialize(JDOMUtil.load(xml), dev.phantom.db.core.DbDataSourceService.State::class.java)
    val service = dev.phantom.db.core.DbDataSourceService.getInstance(project)
    service.loadState(state)
    val ds = service.find("a")!!
    assertEquals("postgresql", ds.driver()?.id)
    assertEquals(Dbms.POSTGRESQL, ds.dialect())
    assertNull("loading writes nothing back", ds.driverId)
    assertEquals(xml, JDOMUtil.write(XmlSerializer.serialize(service.state)))
    service.loadState(dev.phantom.db.core.DbDataSourceService.State())
  }

  fun testUnknownDriverIdFallsBackByUrl() {
    val ds = dev.phantom.db.core.DbDataSource().apply { driverId = "someone-elses"; url = "jdbc:aws-wrapper:mysql://h/x"; dbms = Dbms.MYSQL }
    assertEquals("aws-wrapper-mysql", ds.driver()?.id)
  }

  fun testSavedDataSourceStillWritesDbms() {
    val service = dev.phantom.db.core.DbDataSourceService.getInstance(project)
    service.save(dev.phantom.db.core.DbDataSource().apply { id = "p"; name = "pg"; driverId = "postgresql"; url = "jdbc:postgresql://h/x" }, null)
    val xml = JDOMUtil.write(XmlSerializer.serialize(service.state))
    assertTrue(xml, xml.contains("name=\"dbms\" value=\"POSTGRESQL\""))
    assertFalse(xml.contains("assword"))
    assertFalse(xml.contains("awsSecret"))
    service.remove("p")
  }

  /** 0.10.0 fails on an enum value it does not know, losing every data source: GENERIC must never reach the file. */
  fun testGenericDataSourceIsReadableBy010() {
    val service = dev.phantom.db.core.DbDataSourceService.getInstance(project)
    val ds = dev.phantom.db.core.DbDataSource().apply { id = "r"; name = "rs"; dbms = Dbms.GENERIC; driverId = "redshift"; url = "jdbc:redshift://h/x" }
    service.save(ds, null)
    assertEquals(Dbms.GENERIC, service.find("r")!!.dialect())
    val xml = JDOMUtil.write(XmlSerializer.serialize(service.state))
    assertFalse(xml, xml.contains("GENERIC"))
    assertTrue(xml, xml.contains("name=\"driverId\" value=\"redshift\""))

    val old = XmlSerializer.deserialize(JDOMUtil.load(xml), OldState::class.java)
    assertEquals(OldDbms.MYSQL, old.dataSources.single { it.id == "r" }.dbms)
    val reloaded = XmlSerializer.deserialize(JDOMUtil.load(xml), dev.phantom.db.core.DbDataSourceService.State::class.java)
    assertEquals(Dbms.GENERIC, reloaded.dataSources.single { it.id == "r" }.dialect())
    service.remove("r")
  }

  fun testMarketplaceDetectsEveryDriverRoot() {
    val marketplace = java.io.File("../.phantom/marketplace.json").readText()
    val db = marketplace.substring(marketplace.indexOf("\"id\": \"dev.phantom.db\""), marketplace.indexOf("\"id\": \"dev.phantom.spring\""))
    val version = Regex("""^version = "(.+)"""", RegexOption.MULTILINE).find(java.io.File("build.gradle.kts").readText())!!.groupValues[1]
    assertTrue(db.contains("\"version\": \"$version\""))
    assertTrue(db.contains("\"download\": \"db-$version/db-$version.zip\""))
    for (d in DbDriverRegistry.builtIns) {
      for (root in if (d.artifactsAreAlternatives) d.artifacts else d.artifacts.take(1)) assertTrue(root, db.contains("\"$root\""))
    }
    assertFalse(db.contains("software.amazon.awssdk:rds"))
  }

  fun testSuggestionSeesAwsWrapper() {
    assertTrue(dev.phantom.db.core.DbDrivers.isDriverLibraryName("Gradle: software.amazon.jdbc:aws-advanced-jdbc-wrapper:2.5.0"))
    assertTrue(dev.phantom.db.core.DbDrivers.isDriverLibraryName("Gradle: com.mysql:mysql-connector-j:8.4.0"))
    assertFalse(dev.phantom.db.core.DbDrivers.isDriverLibraryName("Gradle: org.slf4j:slf4j-api:2.0.0"))
    assertTrue(dev.phantom.db.core.DbDrivers.isDriverLibraryName("Gradle: com.amazon.redshift:redshift-jdbc42:2.1.0.30"))
    assertFalse("an SDK helper alone is no driver", dev.phantom.db.core.DbDrivers.isDriverLibraryName("Gradle: software.amazon.awssdk:rds:2.25.0"))
  }
}

/** `Dbms` as 0.10.0 has it. */
enum class OldDbms { MYSQL, MARIADB, POSTGRESQL, H2 }

/** `DbDataSource` as 0.10.0 has it: non-null `dbms`. */
@com.intellij.util.xmlb.annotations.Tag("data-source")
class OldDataSource {
  var id: String = ""
  var name: String = ""
  var dbms: OldDbms = OldDbms.MYSQL
  var url: String = ""
  var user: String = ""
  var readOnly: Boolean = false
  var manualCommit: Boolean = false
  @com.intellij.util.xmlb.annotations.XCollection(style = com.intellij.util.xmlb.annotations.XCollection.Style.v2)
  var driverJars: MutableList<String> = ArrayList()
  var maxRows: Int = 500
  var queryTimeoutSeconds: Int = 60
}

class OldState {
  @com.intellij.util.xmlb.annotations.XCollection(style = com.intellij.util.xmlb.annotations.XCollection.Style.v2)
  var dataSources: MutableList<OldDataSource> = ArrayList()
}
