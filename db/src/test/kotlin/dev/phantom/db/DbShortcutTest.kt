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
}
