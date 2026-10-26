// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db

import com.intellij.openapi.util.JDOMUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.XmlSerializer
import dev.phantom.db.core.DbDataSource
import dev.phantom.db.core.DbDataSourceService
import dev.phantom.db.core.DbGlobalDataSourceService
import dev.phantom.db.core.DbPasswordStorage
import dev.phantom.db.core.exec.isAuthFailure
import java.sql.SQLException

class DbDataSourceStateTest : BasePlatformTestCase() {
  companion object {
    init {
      System.setProperty("idea.load.plugins.id", "dev.phantom.db,org.jetbrains.kotlin")
    }
  }

  /** An element the serializer does not know (hand-edited file) must not leave a null that the tool windows and inspections trip on. */
  fun testUnreadableElementIsDropped() {
    val xml = """
      <State>
        <dataSources>
          <DbDataSource><option name="id" value="bad" /></DbDataSource>
          <data-source><option name="id" value="good" /><option name="url" value="jdbc:h2:mem:a" /></data-source>
        </dataSources>
      </State>
    """.trimIndent()
    val state = XmlSerializer.deserialize(JDOMUtil.load(xml), DbDataSourceService.State::class.java)
    val service = DbDataSourceService.getInstance(project)
    val saved = service.state
    try {
      service.loadState(state)
      assertEquals(listOf("good"), service.dataSources.map { it.id })
    }
    finally {
      service.loadState(saved)
    }
  }

  /** Make Global moves the data source to the IDE list, keeping its id and password; Move to Project moves it back. */
  fun testMakeGlobalAndBack() {
    val service = DbDataSourceService.getInstance(project)
    val global = DbGlobalDataSourceService.getInstance()
    val ds = DbDataSource().apply { name = "g"; url = "jdbc:h2:mem:g"; user = "sa" }
    var events = 0
    service.addListener(testRootDisposable) { events++ }
    try {
      service.save(ds, "pw".toCharArray())
      service.save(ds.copy().apply { this.global = true }, null)
      assertTrue(service.find(ds.id)!!.global)
      assertEquals(listOf(ds.id), global.state.dataSources.map { it.id })
      assertFalse(service.state.dataSources.any { it.id == ds.id })
      assertEquals(1, service.dataSources.count { it.id == ds.id })
      assertEquals("pw", service.password(ds.id))
      // the global list is written without the flag: which file holds it says so
      assertFalse(JDOMUtil.write(XmlSerializer.serialize(global.state)).contains("global"))
      assertTrue(events >= 2)

      service.save(service.find(ds.id)!!.copy().apply { this.global = false }, null)
      assertFalse(service.find(ds.id)!!.global)
      assertEmpty(global.state.dataSources)
    }
    finally {
      service.remove(ds.id)
    }
    assertNull(service.find(ds.id))
  }

  fun testPasswordStorage() {
    val service = DbDataSourceService.getInstance(project)
    val ds = DbDataSource().apply { name = "p"; url = "jdbc:h2:mem:p"; user = "sa"; credentialStorage = DbPasswordStorage.UNTIL_RESTART }
    try {
      service.save(ds, "mem".toCharArray())
      assertEquals("mem", service.password(ds.id))
      service.save(ds.copy().apply { credentialStorage = DbPasswordStorage.NEVER }, null)
      assertNull(service.password(ds.id))
      service.save(ds.copy().apply { credentialStorage = DbPasswordStorage.FOREVER }, "kept".toCharArray())
      assertEquals("kept", service.password(ds.id))
    }
    finally {
      service.remove(ds.id)
    }
  }

  fun testAuthFailure() {
    assertTrue(isAuthFailure(SQLException("denied", "28000", 1045)))
    assertTrue(isAuthFailure(SQLException("password authentication failed", "28P01")))
    assertTrue(isAuthFailure(SQLException("Login failed", "S0001", 18456)))
    assertTrue(isAuthFailure(SQLException("ORA-01017", "72000", 1017)))
    assertFalse(isAuthFailure(SQLException("no table", "42S02", 1146)))
    assertFalse(isAuthFailure(SQLException("timeout", "08001", 0)))
  }
}
