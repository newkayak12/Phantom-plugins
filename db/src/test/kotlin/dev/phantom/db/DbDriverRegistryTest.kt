// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db

import com.intellij.openapi.util.JDOMUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.XmlSerializer
import dev.phantom.db.core.Dbms
import dev.phantom.db.core.drivers.DbDriverDefinition
import dev.phantom.db.core.drivers.DbDriverRegistry

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
}
