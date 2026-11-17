// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionPromoter
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.actionSystem.impl.Utils
import com.intellij.openapi.keymap.ex.KeymapManagerEx
import com.intellij.openapi.keymap.impl.ActionProcessor
import com.intellij.openapi.keymap.impl.IdeKeyEventDispatcher
import com.intellij.openapi.keymap.impl.KeymapImpl
import com.intellij.openapi.keymap.Keymap
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.database.datagrid.GridRequestSource
import com.intellij.database.datagrid.ModelIndexSet
import dev.phantom.db.core.*
import dev.phantom.db.core.exec.*
import dev.phantom.db.core.ui.DbExplorerPanel
import kotlinx.coroutines.runBlocking
import java.awt.Component
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.JComponent
import javax.swing.KeyStroke
import javax.swing.tree.TreePath

/**
 * Presses the db shortcuts as the IDE does: through the key event dispatcher, with the data context of the focused component
 * (an editor, the result grid, the Database tree), under the Mac keymap and under a keymap derived from it, and checks which
 * action runs.
 */
class DbShortcutDispatchTest : BasePlatformTestCase() {
  companion object {
    init {
      System.setProperty("idea.load.plugins.id", "dev.phantom.db,org.jetbrains.kotlin")
    }
  }

  private var saved: Keymap? = null
  private var derived: KeymapImpl? = null

  override fun tearDown() {
    try {
      saved?.let { KeymapManagerEx.getInstanceEx().activeKeymap = it }
      derived?.let { KeymapManagerEx.getInstanceEx().schemeManager.removeScheme(it) }
      ToolWindowManager.getInstance(project).getToolWindow(DbResultsToolWindow.ID)?.contentManager?.removeAllContents(true)
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

  private fun h2(manualCommit: Boolean = false): DbDataSource {
    val ds = DbDataSource().apply {
      name = "keys"
      dbms = Dbms.H2
      url = "jdbc:h2:mem:keys_${System.nanoTime()};DB_CLOSE_DELAY=-1"
      user = "sa"
      this.manualCommit = manualCommit
    }
    DbDataSourceService.getInstance(project).save(ds, CharArray(0))
    return ds
  }

  /** The keymaps the shortcuts are checked under: the Mac one, and one derived from it as when its settings are changed. */
  private fun keymaps(): List<Keymap> {
    val manager = KeymapManagerEx.getInstanceEx()
    saved = manager.activeKeymap
    val mac = manager.getKeymap("Mac OS X 10.5+")!!
    val copy = (mac as KeymapImpl).deriveKeymap("Mac OS X 10.5+ test copy")
    // as in a user's keymap: an override of an action the tree's Duplicate takes its keys from
    copy.removeAllActionShortcuts("EditorDuplicate")
    copy.addShortcut("EditorDuplicate", KeyboardShortcut(KeyStroke.getKeyStroke("meta shift D"), null))
    manager.schemeManager.addScheme(copy, false)
    derived = copy
    return listOf(mac, copy)
  }

  /** Puts [component]'s top parent in a panel that gives the project, as the project frame does. */
  private fun inFrame(component: Component): Component {
    val top = generateSequence(component) { it.parent }.last()
    if (top !is FrameStandIn) FrameStandIn(project).add(top)
    return component
  }

  private class FrameStandIn(private val project: com.intellij.openapi.project.Project) : javax.swing.JPanel(java.awt.BorderLayout()), UiDataProvider {
    override fun uiDataSnapshot(sink: DataSink) {
      sink[CommonDataKeys.PROJECT] = project
    }
  }

  private fun id(action: AnAction): String = ActionManager.getInstance().getId(action) ?: action.javaClass.name

  /** The id of the action a press of [key] on [component] runs (without running it), or null when none does; and the candidates. */
  private fun press(component: Component, key: String): Pair<String?, String> {
    val stroke = KeyStroke.getKeyStroke(key) ?: error("bad key $key")
    val shortcut = KeyboardShortcut(stroke, null)
    val event = KeyEvent(component, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), stroke.modifiers, stroke.keyCode, KeyEvent.CHAR_UNDEFINED)
    val dispatcher = IdeKeyEventDispatcher(null)
    dispatcher.context.inputEvent = event
    dispatcher.updateCurrentContext(component, shortcut)
    dispatcher.context.project = project
    // the data context the IDE builds for the focused component (the headless DataManager's has none of the components' data)
    val context = Utils.createAsyncDataContext(component)
    dispatcher.context.dataContext = context
    @Suppress("UNCHECKED_CAST")
    val candidates = (dispatcher.context.javaClass.getDeclaredMethod("getActions").apply { isAccessible = true }.invoke(dispatcher.context) as List<AnAction>).toList()
    val order = com.intellij.openapi.actionSystem.impl.rearrangeByPromotersImpl(
      candidates, Utils.getUiOnlyDataContext(context), ActionPromoter.EP_NAME.extensionList)
    var performed: String? = null
    try {
      dispatcher.processAction(event, object : ActionProcessor() {
        override fun performAction(inputEvent: InputEvent, action: AnAction, event: AnActionEvent) {
          performed = id(action)
        }
      })
    }
    finally {
      dispatcher.context.clear()
    }
    return performed to "candidates ${candidates.map(::id)}, promoted ${order.map(::id)}"
  }

  private fun check(failures: MutableList<String>, keymap: Keymap, where: String, component: Component, key: String, expected: String?) {
    KeymapManagerEx.getInstanceEx().activeKeymap = keymap
    val (actual, candidates) = press(inFrame(component), key)
    if (actual != expected) failures += "${keymap.name}: $key in $where ran $actual, expected $expected; $candidates"
  }

  /** Cmd+Enter was Split Line's: the promoter asked for the PSI file, which the context of a key press does not have yet. */
  fun testSqlEditorShortcuts() {
    val ds = h2()
    myFixture.configureByText("console.sql", "SELECT 2")
    DbConsoles.attach(project, myFixture.file.virtualFile, ds.id)
    val console = myFixture.editor.contentComponent
    myFixture.configureByText("q.sql", "SELECT 1")
    val editor = myFixture.editor.contentComponent
    val failures = ArrayList<String>()
    for (keymap in keymaps()) {
      check(failures, keymap, "a console", console, "meta ENTER", "PhantomDb.RunStatement")
      check(failures, keymap, "a .sql editor", editor, "meta ENTER", "PhantomDb.RunStatement")
      check(failures, keymap, "a .sql editor", editor, "meta alt E", "PhantomDb.ShowHistory")
      check(failures, keymap, "a .sql editor", editor, "alt shift B", "PhantomDb.SelectInTree")
      check(failures, keymap, "a .sql editor", editor, "meta shift L", "PhantomDb.NewConsole")
      check(failures, keymap, "a .sql editor", editor, "meta control N", "PhantomDb.NewConsole")
    }
    assertEmpty(failures.joinToString("\n"), failures)
  }

  fun testGlobalShortcutsOutsideSql() {
    h2()
    myFixture.configureByText("a.txt", "x")
    val editor = myFixture.editor.contentComponent
    val failures = ArrayList<String>()
    for (keymap in keymaps()) {
      check(failures, keymap, "a text editor", editor, "meta shift L", "PhantomDb.NewConsole")
      check(failures, keymap, "a text editor", editor, "meta control N", "PhantomDb.NewConsole")
    }
    assertEmpty(failures.joinToString("\n"), failures)
  }

  fun testTreeShortcuts() {
    val ds = h2()
    val panel = DbExplorerPanel(project, testRootDisposable)
    panel.reload()
    val source = panel.sourceNodes().single { (it.userObject as? dev.phantom.db.core.ui.DbNode.Source)?.dataSource?.id == ds.id }
    panel.tree.selectionPath = TreePath(source.path)
    val failures = ArrayList<String>()
    for (keymap in keymaps()) {
      check(failures, keymap, "the Database tree", panel.tree, "meta I", "PhantomDb.Tree.Edit")
      check(failures, keymap, "the Database tree", panel.tree, "meta C", "PhantomDb.Tree.CopySource")
      check(failures, keymap, "the Database tree", panel.tree, "meta N", "PhantomDb.Tree.Add")
      check(failures, keymap, "the Database tree", panel.tree, "meta BACK_SPACE", "PhantomDb.Tree.Remove")
      check(failures, keymap, "the Database tree", panel.tree, "meta R", "PhantomDb.Tree.Refresh")
      check(failures, keymap, "the Database tree", panel.tree, "F6", "PhantomDb.Tree.MoveToGroup")
      check(failures, keymap, "the Database tree", panel.tree, "meta shift L", "PhantomDb.NewConsole")
    }
    // Paste is the tree's only while data sources are on the clipboard
    val clipboard = com.intellij.openapi.ide.CopyPasteManager.getInstance()
    clipboard.setContents(java.awt.datatransfer.StringSelection(dev.phantom.db.core.ui.DbDataSourceClipboard.write(listOf(ds))))
    check(failures, derived!!, "the Database tree", panel.tree, "meta V", "PhantomDb.Tree.PasteSource")
    clipboard.setContents(java.awt.datatransfer.StringSelection("just text"))
    check(failures, derived!!, "the Database tree", panel.tree, "meta V", null)
    assertEmpty(failures.joinToString("\n"), failures)
  }

  fun testGridShortcuts() {
    val ds = h2()
    runBlocking { DbExecutor.execute(project, ds, listOf("CREATE TABLE t (id INT PRIMARY KEY, name VARCHAR(10))", "INSERT INTO t VALUES (1, 'a'), (2, 'b')")) }
    myFixture.configureByText("grid.sql", "SELECT * FROM t ORDER BY id")
    DbRunner.start(project, myFixture.file.virtualFile, ds, listOf("SELECT * FROM t ORDER BY id"))
    val panel = { ToolWindowManager.getInstance(project).getToolWindow(DbResultsToolWindow.ID)?.contentManager?.selectedContent?.component as? DbResultPanel }
    PlatformTestUtil.waitWithEventsDispatching("grid not editable; status: ${panel()?.shownStatus}", { panel()?.shownEditing != null }, 20)
    val grid = panel()!!.shownGrid!!
    val edits = panel()!!.shownEditing!!
    edits.mutate(GridRequestSource(null), ModelIndexSet.forRows(edits.hookUp.dataModel, 0), ModelIndexSet.forColumns(edits.hookUp.dataModel, 1), "z", true)
    grid.selectionModel.setSelection(com.intellij.database.datagrid.ModelIndex.forRow(grid, 1), com.intellij.database.datagrid.ModelIndex.forColumn(grid, 1))
    val table: JComponent = grid.resultView.component
    val failures = ArrayList<String>()
    for (keymap in keymaps()) {
      check(failures, keymap, "the result grid", table, "meta ENTER", "PhantomDb.Grid.Submit")
      // the Mac keymaps take the default keymap's Ctrl as Cmd, as DataGrip's Set NULL (Cmd+Alt+N)
      check(failures, keymap, "the result grid", table, "meta alt N", "PhantomDb.Grid.SetNull")
      check(failures, keymap, "the result grid", table, "shift SPACE", "PhantomDb.Grid.SelectRow")
      check(failures, keymap, "the result grid", table, "meta alt shift F", "PhantomDb.Grid.EditFilterCriteria")
    }
    assertEmpty(failures.joinToString("\n"), failures)
  }
}
