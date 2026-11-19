// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db

import com.intellij.openapi.util.JDOMUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.XmlSerializer
import dev.phantom.db.core.DbDataSource
import dev.phantom.db.core.PhantomDbIcons
import dev.phantom.db.core.drivers.DbDriverDefinition
import dev.phantom.db.core.drivers.DbDriverRegistry

class DbIconsTest : BasePlatformTestCase() {
  companion object {
    init {
      System.setProperty("idea.load.plugins.id", "dev.phantom.db,org.jetbrains.kotlin")
    }
  }

  fun testEveryBuiltInHasIconKeyAndIcon() {
    for (d in DbDriverRegistry.builtIns) {
      assertTrue("${d.id} has no icon key", d.icon.isNotBlank())
      assertNotNull(PhantomDbIcons.forDriver(d))
    }
  }

  fun testEveryBuiltInKeyIsKnown() {
    for (d in DbDriverRegistry.builtIns) assertTrue("${d.id}: ${d.icon}", d.icon in PhantomDbIcons.keys)
  }

  fun testEveryKnownKeyResolvesToItsOwnIcon() {
    for (k in PhantomDbIcons.keys) assertNotSame(k, PhantomDbIcons.GENERIC, PhantomDbIcons.forKey(k))
  }

  fun testUnknownKeyIsGeneric() {
    assertSame(PhantomDbIcons.GENERIC, PhantomDbIcons.forKey(""))
    assertSame(PhantomDbIcons.GENERIC, PhantomDbIcons.forKey("nope"))
    assertSame(PhantomDbIcons.GENERIC, PhantomDbIcons.forDriver(null))
  }

  fun testRedshiftDataSourceShowsRedshiftIcon() {
    val ds = DbDataSource().apply { driverId = "redshift"; url = "jdbc:redshift://h:5439/db" }
    assertSame(PhantomDbIcons.forKey("redshift"), PhantomDbIcons.forDataSource(ds))
    assertNotSame(PhantomDbIcons.GENERIC, PhantomDbIcons.forDataSource(ds))
  }

  fun testCopyAndXmlKeepIcon() {
    val d = DbDriverDefinition().apply { id = "x"; name = "X"; icon = "oracle" }
    assertEquals("oracle", d.copy().icon)
    val xml = JDOMUtil.write(XmlSerializer.serialize(d))
    assertEquals("oracle", XmlSerializer.deserialize(JDOMUtil.load(xml), DbDriverDefinition::class.java).icon)
  }
}
