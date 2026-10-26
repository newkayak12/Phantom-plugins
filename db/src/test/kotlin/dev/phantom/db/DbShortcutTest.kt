// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.db.core.exec.SqlActionPromoter
import javax.swing.KeyStroke

class DbShortcutTest : BasePlatformTestCase() {
  companion object {
    init {
      System.setProperty("idea.load.plugins.id", "dev.phantom.db,org.jetbrains.kotlin")
    }
  }

  private fun shortcuts(keymap: String, id: String): List<KeyStroke> =
    KeymapManager.getInstance().getKeymap(keymap)!!.getShortcuts(id).filterIsInstance<KeyboardShortcut>().map { it.firstKeyStroke }

  fun testShortcutsInDefaultAndMacKeymaps() {
    assertTrue(KeyStroke.getKeyStroke("control ENTER") in shortcuts("\$default", "PhantomDb.RunStatement"))
    assertTrue(KeyStroke.getKeyStroke("control alt E") in shortcuts("\$default", "PhantomDb.ShowHistory"))
    for (mac in listOf("Mac OS X 10.5+", "Mac OS X")) {
      assertTrue(mac, KeyStroke.getKeyStroke("meta ENTER") in shortcuts(mac, "PhantomDb.RunStatement"))
      assertTrue(mac, KeyStroke.getKeyStroke("meta alt E") in shortcuts(mac, "PhantomDb.ShowHistory"))
    }
  }

  fun testNewConsoleShortcutIsFree() {
    for ((keymap, key) in listOf("\$default" to "control alt shift Q", "Mac OS X 10.5+" to "meta control N", "Mac OS X" to "meta control N")) {
      val stroke = KeyStroke.getKeyStroke(key)
      assertTrue(keymap, stroke in shortcuts(keymap, "PhantomDb.NewConsole"))
      assertEquals(keymap, listOf("PhantomDb.NewConsole"),
                   KeymapManager.getInstance().getKeymap(keymap)!!.getActionIds(KeyboardShortcut(stroke, null)).toList())
    }
  }

  fun testRunAndHistoryWinInSqlEditorsOnly() {
    val manager = ActionManager.getInstance()
    val run = manager.getAction("PhantomDb.RunStatement")
    val history = manager.getAction("PhantomDb.ShowHistory")
    val other = manager.getAction("EditorSplitLine") ?: manager.getAction("EditorStartNewLine")!!
    val actions: List<AnAction> = listOf(other, history, run)

    val sql = myFixture.configureByText("q.sql", "SELECT 1")
    val promoted = SqlActionPromoter().promote(actions, SimpleDataContext.getSimpleContext(CommonDataKeys.PSI_FILE, sql))
    assertEquals(listOf(run, history), promoted)

    val text = myFixture.configureByText("a.txt", "x")
    assertEmpty(SqlActionPromoter().promote(actions, SimpleDataContext.getSimpleContext(CommonDataKeys.PSI_FILE, text)))
    val java = myFixture.configureByText("A.java", "class A {}")
    assertEmpty(SqlActionPromoter().promote(actions, SimpleDataContext.getSimpleContext(CommonDataKeys.PSI_FILE, java)))
  }

  /** DataGrip's keys: New Console, Commit, Cancel (Stop's), Jump to Console (Console.Open's) and the Database tree commands. */
  fun testDataGripShortcuts() {
    for (mac in listOf("Mac OS X 10.5+", "Mac OS X")) {
      assertTrue(mac, KeyStroke.getKeyStroke("meta shift L") in shortcuts(mac, "PhantomDb.NewConsole"))
      assertTrue(mac, KeyStroke.getKeyStroke("meta alt shift ENTER") in shortcuts(mac, "PhantomDb.Commit"))
      assertTrue(mac, KeyStroke.getKeyStroke("meta I") in shortcuts(mac, "PhantomDb.Tree.Edit"))
    }
    assertTrue(KeyStroke.getKeyStroke("control shift Q") in shortcuts("\$default", "PhantomDb.NewConsole"))
    for ((id, key) in listOf("PhantomDb.Grid.SetNull" to "control alt N", "PhantomDb.Grid.SetDefault" to "control alt D",
                             "PhantomDb.SelectInTree" to "alt shift B", "PhantomDb.Tree.CopyDdl" to "control shift C",
                             "PhantomDb.Tree.SqlGenerator" to "control alt G")) {
      assertTrue(id, KeyStroke.getKeyStroke(key) in shortcuts("\$default", id))
    }
    assertTrue(KeyStroke.getKeyStroke("control alt shift ENTER") in shortcuts("\$default", "PhantomDb.Commit"))
    for (keymap in listOf("\$default", "Mac OS X 10.5+")) {
      for ((ours, theirs) in listOf("PhantomDb.Cancel" to "Stop", "PhantomDb.JumpToConsole" to "Console.Open", "PhantomDb.Tree.Remove" to "\$Delete",
                                    "PhantomDb.Tree.Add" to "NewElement", "PhantomDb.Tree.Refresh" to "Refresh", "PhantomDb.Tree.Ddl" to "GotoDeclaration",
                                    "PhantomDb.Tree.Modify" to "ChangeSignature", "PhantomDb.Tree.Compare" to "CompareTwoFiles")) {
        val expected = shortcuts(keymap, theirs)
        assertTrue("$keymap $theirs", expected.isNotEmpty())
        assertEquals("$keymap $ours", expected, shortcuts(keymap, ours))
      }
    }
  }

  /** Every tree command is registered, and the tree's commands win only in the tree, not in its filter field. */
  fun testTreeCommands() {
    val manager = ActionManager.getInstance()
    for (name in listOf("Add", "Edit", "Remove", "MakeGlobal", "Refresh", "Drivers", "OpenConsole", "Data", "Ddl", "Modify", "Select", "ImportCsv",
                        "Diagram", "Entity", "Compare", "CopyDdl", "SqlGenerator")) {
      val action = manager.getAction("PhantomDb.Tree.$name")
      assertTrue(name, action is dev.phantom.db.core.ui.DbTreeAction)
      assertFalse(name, action.templateText.isNullOrBlank())
    }
    val panel = dev.phantom.db.core.ui.DbExplorerPanel(project, testRootDisposable)
    val remove = manager.getAction("PhantomDb.Tree.Remove")
    val delete = manager.getAction("\$Delete")
    fun context(component: java.awt.Component) = SimpleDataContext.builder()
      .add(dev.phantom.db.core.ui.DbExplorerPanel.KEY, panel)
      .add(com.intellij.openapi.actionSystem.PlatformCoreDataKeys.CONTEXT_COMPONENT, component)
      .build()
    val promoter = dev.phantom.db.core.ui.DbTreeActionPromoter()
    assertEquals(listOf(remove), promoter.promote(listOf(delete, remove), context(panel.tree)))
    assertEmpty(promoter.promote(listOf(delete, remove), context(javax.swing.JTextField())))
  }
}
