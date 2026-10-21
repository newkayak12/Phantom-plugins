// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db

import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.JDOMUtil
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.XmlSerializer
import dev.phantom.db.core.DbAuthKind
import dev.phantom.db.core.DbDataSource
import dev.phantom.db.core.Dbms
import dev.phantom.db.core.drivers.DbDriverDefinition
import dev.phantom.db.core.drivers.DbDriverRegistry
import dev.phantom.db.core.ui.DbDataSourceDialog
import dev.phantom.db.core.ui.DbDriversDialog
import dev.phantom.db.core.ui.driverClassesIn
import dev.phantom.db.core.ui.fillTemplate
import dev.phantom.db.core.ui.uniqueId

class DbDriverUiTest : BasePlatformTestCase() {
  companion object {
    init {
      System.setProperty("idea.load.plugins.id", "dev.phantom.db,org.jetbrains.kotlin")
    }
  }

  private lateinit var savedState: DbDriverRegistry.State
  private val registry get() = DbDriverRegistry.getInstance()

  override fun setUp() {
    super.setUp()
    savedState = XmlSerializer.deserialize(JDOMUtil.load(JDOMUtil.write(XmlSerializer.serialize(registry.state))), DbDriverRegistry.State::class.java)
  }

  override fun tearDown() {
    try {
      registry.loadState(savedState)
    }
    finally {
      super.tearDown()
    }
  }

  private val def get() = registry.find("h2")!!

  fun testDriverClassesFoundInH2Jar() = assertContainsElements(def.driverClassesIn(listOf(DbConsoleTest.h2Jar)), "org.h2.Driver")

  fun testFillTemplate() =
    assertEquals("jdbc:mysql://db:3306/app", fillTemplate("jdbc:mysql://{host}:3306/{database}", mapOf("host" to "db", "database" to "app")))

  fun testFillTemplateLeavesNoPlaceholders() {
    assertEquals("jdbc:athena://Region=;WorkGroup=primary", fillTemplate("jdbc:athena://Region={region};WorkGroup=primary", mapOf("host" to "localhost")))
    assertEquals("jdbc:h2:mem:test", fillTemplate("jdbc:h2:mem:test", emptyMap()))
    assertEquals("jdbc:x://h:5/", fillTemplate("jdbc:x://{host}:{port}/{db-name}", mapOf("host" to "h", "port" to "5")))
  }

  private fun <T> withDialog(existing: DbDataSource?, body: (DbDataSourceDialog) -> T): T {
    val dialog = DbDataSourceDialog(project, existing)
    try {
      return body(dialog)
    }
    finally {
      Disposer.dispose(dialog.disposable)
    }
  }

  fun testDialogPicksDriverFromPastedUrl() = withDialog(null) { dialog ->
    dialog.urlField.text = "jdbc:redshift://x:5439/dev"
    assertEquals("redshift", dialog.current().driverId)
    assertEquals("jdbc:redshift://x:5439/dev", dialog.current().url)
  }

  fun testNewDialogStartsOnMysqlTemplate() = withDialog(null) { dialog ->
    assertEquals("mysql", dialog.current().driverId)
    assertEquals("jdbc:mysql://localhost:3306/", dialog.urlField.text)
  }

  fun testSwitchingDriverReplacesOnlyUntouchedUrl() = withDialog(null) { dialog ->
    dialog.driverCombo.selectedItem = registry.find("postgresql")
    assertEquals("jdbc:postgresql://localhost:5432/", dialog.urlField.text)
    dialog.urlField.text = "jdbc:postgresql://db/app"
    dialog.driverCombo.selectedItem = registry.find("aws-wrapper-postgresql")
    assertEquals("typed URL is never clobbered", "jdbc:postgresql://db/app", dialog.urlField.text)
    assertEquals("aws-wrapper-postgresql", dialog.current().driverId)
    dialog.urlField.text = ""
    dialog.driverCombo.selectedItem = registry.find("h2")
    assertEquals("jdbc:h2:mem:test", dialog.urlField.text)
  }

  fun testUnknownDriverIdIsKept() = withDialog(DbDataSource().apply { driverId = "from-a-teammate"; url = "jdbc:foo://h/db" }) { dialog ->
    assertEquals("from-a-teammate", dialog.current().driverId)
  }

  fun testAwsFieldsOnlyForIamDrivers() {
    assertTrue(DbDataSourceDialog.showsAwsFields(DbAuthKind.AWS_IAM, registry.find("aws-wrapper-mysql")))
    assertFalse(DbDataSourceDialog.showsAwsFields(DbAuthKind.PASSWORD, registry.find("aws-wrapper-mysql")))
    assertFalse(DbDataSourceDialog.showsAwsFields(DbAuthKind.AWS_IAM, registry.find("mysql")))
  }

  fun testUniqueId() {
    assertEquals("mysql-copy", uniqueId("mysql-copy", setOf("mysql")))
    assertEquals("mysql-copy-2", uniqueId("mysql-copy", setOf("mysql", "mysql-copy")))
  }

  private fun <T> withDrivers(select: String?, body: (DbDriversDialog) -> T): T {
    val dialog = DbDriversDialog(project, select)
    try {
      return body(dialog)
    }
    finally {
      Disposer.dispose(dialog.disposable)
    }
  }

  fun testDuplicateBuiltInMakesUserDriver() = withDrivers("mysql") { dialog ->
    assertEquals("mysql", dialog.selected()?.id)
    dialog.duplicate()
    assertEquals("mysql-copy", dialog.selected()?.id)
    dialog.apply()
    val copy = registry.find("mysql-copy")!!
    assertFalse(copy.builtIn)
    assertEquals("com.mysql.cj.jdbc.Driver", copy.driverClass)
  }

  fun testResetBuiltInDropsOverride() {
    registry.save(registry.find("mysql")!!.copy().apply { defaultProperties["useSSL"] = "false" })
    withDrivers("mysql") { dialog ->
      dialog.reset()
      dialog.apply()
    }
    assertTrue(registry.state.overrides.none { it.id == "mysql" })
    assertEmpty(registry.find("mysql")!!.defaultProperties.keys)
  }

  fun testRemoveUserDriver() {
    registry.save(registry.find("h2")!!.copy().apply { id = "mine"; name = "Mine" })
    withDrivers("mine") { dialog ->
      dialog.remove()
      dialog.apply()
    }
    assertNull(registry.find("mine"))
  }

  private fun registryXml() = JDOMUtil.write(XmlSerializer.serialize(registry.state))

  fun testEditorDownloadIsKeptUntilOk() {
    val before = registryXml()
    withDrivers("mysql") { dialog ->
      dialog.editor.downloader = { d, _ -> d.copy().apply { resolvedVersion = "9.9"; resolvedJars = mutableListOf(DbConsoleTest.h2Jar.toString()) } }
      dialog.editor.download()
      PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
      assertEquals("9.9", dialog.selected()!!.resolvedVersion)
      // closed with Cancel: no apply()
    }
    assertEquals(before, registryXml())
    assertNull(registry.find("mysql")!!.resolvedVersion)
  }

  fun testEditorDownloadOfBuiltInPinsWithoutOverride() {
    withDrivers("mysql") { dialog ->
      dialog.editor.downloader = { d, _ -> d.copy().apply { resolvedVersion = "9.9"; resolvedJars = mutableListOf(DbConsoleTest.h2Jar.toString()) } }
      dialog.editor.download()
      PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
      dialog.apply()
    }
    assertTrue("drivers.xml changes keep reaching the built-in", registry.state.overrides.none { it.id == "mysql" })
    assertEquals("9.9", registry.find("mysql")!!.resolvedVersion)
  }

  fun testResetKeepsDownloadedJars() {
    registry.pin("mysql", "9.9", listOf(DbConsoleTest.h2Jar.toString()))
    registry.save(registry.find("mysql")!!.copy().apply { defaultProperties["useSSL"] = "false" })
    withDrivers("mysql") { dialog ->
      dialog.reset()
      dialog.apply()
    }
    assertTrue(registry.state.overrides.none { it.id == "mysql" })
    assertEquals("9.9", registry.find("mysql")!!.resolvedVersion)
  }

  fun testDriverRequiredToSave() {
    withDialog(DbDataSource().apply { dbms = Dbms.GENERIC; url = "jdbc:nothing:x" }) { dialog ->
      assertNull(dialog.current().driverId)
      assertNotNull(dialog.doValidate())
    }
    withDialog(null) { assertNull(it.doValidate()) }
    withDialog(DbDataSource().apply { driverId = "from-a-teammate"; url = "jdbc:foo://h/db" }) { assertNull(it.doValidate()) }
  }

  fun testTestConnectionDownloadSavesOnlyJars() {
    registry.save(DbDriverDefinition().apply {
      id = "fake"; name = "Fake"; driverClass = "org.h2.Driver"; urlTemplate = "jdbc:fake:"; artifacts = mutableListOf("x.fake:nothing")
    })
    DbDataSourceDialog.downloadIfMissing(project, DbDataSource().apply { driverId = "fake" }, EmptyProgressIndicator()) { d, _ ->
      d.copy().apply { name = "Changed"; resolvedVersion = "1.0"; resolvedJars = mutableListOf(DbConsoleTest.h2Jar.toString()) }
    }
    val saved = registry.find("fake")!!
    assertEquals("Fake", saved.name)
    assertEquals("1.0", saved.resolvedVersion)
    assertEquals(listOf(DbConsoleTest.h2Jar.toString()), saved.resolvedJars)
  }

  fun testFieldsBuildUrl() = withDialog(null) { dialog ->
    assertFalse(dialog.urlOnlyBox.isSelected)
    dialog.hostField.text = "db"
    dialog.databaseField.text = "app"
    assertEquals("jdbc:mysql://db:3306/app", dialog.urlField.text)
    dialog.driverCombo.selectedItem = registry.find("postgresql")
    assertEquals("jdbc:postgresql://db:5432/app", dialog.urlField.text)
    dialog.urlField.text = "jdbc:postgresql://other/x"
    assertTrue("a typed URL switches to URL only", dialog.urlOnlyBox.isSelected)
    dialog.hostField.text = "ignored"
    assertEquals("jdbc:postgresql://other/x", dialog.urlField.text)
  }

  fun testExistingDataSourceOpensOnUrl() = withDialog(DbDataSource().apply { driverId = "mysql"; url = "jdbc:mysql://h/db" }) { dialog ->
    assertTrue(dialog.urlOnlyBox.isSelected)
    assertEquals("jdbc:mysql://h/db", dialog.current().url)
  }

  fun testAwsIamOnlyOfferedByIamDrivers() = withDialog(null) { dialog ->
    fun kinds() = (0 until dialog.authCombo.itemCount).map { dialog.authCombo.getItemAt(it) }
    assertFalse(DbAuthKind.AWS_IAM in kinds())
    dialog.driverCombo.selectedItem = registry.find("aws-wrapper-mysql")
    assertTrue(DbAuthKind.AWS_IAM in kinds())
    dialog.authCombo.selectedItem = DbAuthKind.AWS_IAM
    dialog.driverCombo.selectedItem = registry.find("mysql")
    assertEquals(DbAuthKind.PASSWORD, dialog.authCombo.selectedItem)
  }

  fun testDriversDialogValidation() = withDrivers("mysql") { dialog ->
    assertNull(dialog.doValidate())
    dialog.add()
    assertNotNull("a new driver has no class yet", dialog.doValidate())
  }
}
