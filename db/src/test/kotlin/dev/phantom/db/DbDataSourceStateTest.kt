// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db

import com.intellij.openapi.util.JDOMUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.XmlSerializer
import dev.phantom.db.core.DbDataSourceService

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
}
