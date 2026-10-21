// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db

import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.JBColor
import com.intellij.util.xmlb.XmlSerializer
import dev.phantom.db.core.*
import dev.phantom.db.core.exec.DbConnectionService
import dev.phantom.db.core.exec.DbConsoles
import dev.phantom.db.core.exec.DbResultsToolWindow
import com.intellij.openapi.util.Disposer
import dev.phantom.db.core.ui.DbDataSourceDialog
import dev.phantom.db.core.ui.DbExplorerPanel
import dev.phantom.db.core.ui.DbNode
import javax.swing.border.MatteBorder
import javax.swing.tree.DefaultMutableTreeNode

class DbColorTest : BasePlatformTestCase() {
  companion object {
    init {
      System.setProperty("idea.load.plugins.id", "dev.phantom.db,org.jetbrains.kotlin")
    }
  }

  override fun tearDown() {
    try {
      val service = DbDataSourceService.getInstance(project)
      service.dataSources.forEach {
        DbConnectionService.getInstance(project).disconnect(it.id)
        service.remove(it.id)
      }
    }
    catch (e: Throwable) {
      addSuppressedException(e)
    }
    finally {
      super.tearDown()
    }
  }

  private fun source(name: String, color: String): DbDataSource = DbDataSource().apply {
    this.name = name
    dbms = Dbms.H2
    url = "jdbc:h2:mem:color_${name}_${System.nanoTime()}"
    this.color = color
  }.also { DbDataSourceService.getInstance(project).save(it, CharArray(0)) }

  fun testColorIsCopiedAndSaved() {
    val ds = source("red", "red")
    assertEquals("red", ds.copy().color)
    val state = DbDataSourceService.getInstance(project).state
    val element = XmlSerializer.serialize(state)
    val loaded = XmlSerializer.deserialize(element, DbDataSourceService.State::class.java)
    assertEquals("red", loaded.dataSources.single { it.id == ds.id }.color)
  }

  fun testPalette() {
    assertEquals(listOf("", "red", "orange", "yellow", "green", "blue", "purple", "gray"), DbColors.keys)
    for (key in DbColors.keys.drop(1)) {
      assertTrue(key, DbColors.tint(key) is JBColor)
      assertTrue(key, DbColors.stripe(key) is JBColor)
      assertFalse(key, DbColors.tint(key) == DbColors.stripe(key))
    }
    assertNull(DbColors.tint(""))
    assertNull(DbColors.tint("magenta"))
    assertNull(DbColors.stripe(null))
    assertEquals("None", DbColors.displayName(""))
    assertEquals("None", DbColors.displayName("magenta"))
    assertEquals("Red", DbColors.displayName("red"))
  }

  fun testTabColorAndEditorBackgroundFollowTheDataSource() {
    val red = source("red", "red")
    val plain = source("plain", "")
    val file = myFixture.configureByText("q.sql", "SELECT 1").virtualFile
    val provider = DbEditorTabColorProvider()
    assertNull(provider.getEditorTabColor(project, file))
    val editor = myFixture.editor as EditorEx
    val schemeBackground = editor.backgroundColor

    DbConsoles.attach(project, file, red.id)
    assertEquals(DbColors.tint("red"), provider.getEditorTabColor(project, file))
    assertEquals(DbColors.tint("red"), editor.backgroundColor)

    // saving the data source recolors open editors
    DbDataSourceService.getInstance(project).save(red.copy().apply { color = "blue" }, null)
    com.intellij.testFramework.PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    assertEquals(DbColors.tint("blue"), provider.getEditorTabColor(project, file))
    assertEquals(DbColors.tint("blue"), editor.backgroundColor)

    DbConsoles.attach(project, file, plain.id)
    assertNull(provider.getEditorTabColor(project, file))
    assertEquals(schemeBackground, editor.backgroundColor)

    val text = myFixture.addFileToProject("notes.txt", "x").virtualFile
    DbConsoles.attach(project, text, red.id)
    assertNull(provider.getEditorTabColor(project, text))
  }

  fun testDialogEditsTheColor() {
    val red = source("red", "red")
    val dialog = DbDataSourceDialog(project, red)
    try {
      assertEquals("red", dialog.colorCombo.selectedItem)
      assertEquals("", dialog.colorCombo.getItemAt(0))
      dialog.colorCombo.selectedItem = "green"
      assertEquals("green", dialog.current().color)
    }
    finally {
      Disposer.dispose(dialog.disposable)
    }
  }

  fun testTreeRowsOfAColoredDataSourceAreTinted() {
    source("red", "red")
    source("plain", "")
    val panel = DbExplorerPanel(project, testRootDisposable)
    val rows = (0 until panel.tree.rowCount).associate { row ->
      val path = panel.tree.getPathForRow(row)
      ((path.lastPathComponent as DefaultMutableTreeNode).userObject as DbNode.Source).dataSource.name to panel.tree.getPathBackground(path, row)
    }
    assertEquals(DbColors.tint("red"), rows["red"])
    assertFalse(rows["plain"] == DbColors.tint("red"))
  }

  fun testResultTabHasTheStripe() {
    val red = source("red", "red")
    val file = myFixture.configureByText("q.sql", "SELECT 1").virtualFile
    val panel = DbResultsToolWindow.tabFor(project, file, red)
    assertEquals(DbColors.stripe("red"), (panel.border as MatteBorder).matteColor)
    DbDataSourceService.getInstance(project).save(red.copy().apply { color = "" }, null)
    com.intellij.testFramework.PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    assertFalse(panel.border is MatteBorder)
  }
}
