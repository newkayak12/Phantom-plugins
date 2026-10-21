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
    assertContainsElements(ids, "mysql", "mariadb", "postgresql", "h2", "aws-wrapper-mysql", "aws-wrapper-postgresql",
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

  fun testSuggestionSeesAwsWrapper() {
    assertTrue(dev.phantom.db.core.DbDrivers.isDriverLibraryName("Gradle: software.amazon.jdbc:aws-advanced-jdbc-wrapper:2.5.0"))
    assertTrue(dev.phantom.db.core.DbDrivers.isDriverLibraryName("Gradle: com.mysql:mysql-connector-j:8.4.0"))
    assertFalse(dev.phantom.db.core.DbDrivers.isDriverLibraryName("Gradle: org.slf4j:slf4j-api:2.0.0"))
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
