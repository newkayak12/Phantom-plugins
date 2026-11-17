// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.db.core.*
import dev.phantom.db.core.exec.*
import kotlinx.coroutines.runBlocking

/** Run 'x.sql' from the Project view and the editor tab menu. */
class DbRunSqlFileTest : BasePlatformTestCase() {
  companion object {
    init {
      System.setProperty("idea.load.plugins.id", "dev.phantom.db,org.jetbrains.kotlin")
    }
  }

  private val id = "PhantomDb.RunFile"
  private var counter = 0

  private fun h2(): DbDataSource {
    val name = "runfile${counter++}"
    val ds = DbDataSource().apply {
      this.name = name
      dbms = Dbms.H2
      url = "jdbc:h2:mem:${name}_${System.nanoTime()};DB_CLOSE_DELAY=-1"
      user = "sa"
    }
    DbDataSourceService.getInstance(project).save(ds, CharArray(0))
    return ds
  }

  private fun exec(ds: DbDataSource, vararg sql: String): List<DbOutcome> = runBlocking { DbExecutor.execute(project, ds, sql.toList()) }

  private fun count(ds: DbDataSource, table: String): Any? = (exec(ds, "SELECT COUNT(*) FROM $table").single() as DbOutcome.Rows).rows.single()[0]

  private fun contents() = ToolWindowManager.getInstance(project).getToolWindow(DbResultsToolWindow.ID)?.contentManager

  private fun event(vararg files: VirtualFile, single: Boolean = true): AnActionEvent {
    val data = mutableMapOf<String, Any?>(CommonDataKeys.PROJECT.name to project, CommonDataKeys.VIRTUAL_FILE_ARRAY.name to arrayOf(*files))
    if (single && files.size == 1) data[CommonDataKeys.VIRTUAL_FILE.name] = files[0]
    val context: DataContext = SimpleDataContext.getSimpleContext(data, null)
    return TestActionEvent.createTestEvent(ActionManager.getInstance().getAction(id), context)
  }

  private fun fileOf(name: String, text: String): VirtualFile {
    myFixture.configureByText(name, text)
    return myFixture.file.virtualFile
  }

  private fun runFile(file: VirtualFile) {
    val action = ActionManager.getInstance().getAction(id)
    action.actionPerformed(event(file))
  }

  private fun waitStatus() {
    PlatformTestUtil.waitWithEventsDispatching("no result tab", {
      (contents()?.selectedContent?.component as? DbResultPanel)?.shownStatus?.isNotEmpty() == true
    }, 20)
  }

  override fun tearDown() {
    try {
      TestDialogManager.setTestDialog(TestDialog.DEFAULT)
      val service = DbDataSourceService.getInstance(project)
      service.dataSources.forEach {
        DbConnectionService.getInstance(project).disconnect(it.id)
        service.remove(it.id)
      }
      contents()?.removeAllContents(true)
    }
    catch (e: Throwable) {
      addSuppressedException(e)
    }
    finally {
      super.tearDown()
    }
  }

  fun testActionRegisteredAndVisibleForSqlFileOnly() {
    assertNotNull(ActionManager.getInstance().getAction(id))
    val sql = fileOf("x.sql", "SELECT 1")
    val e = event(sql)
    ActionManager.getInstance().getAction(id).update(e)
    assertTrue(e.presentation.isEnabledAndVisible)
    assertEquals("Run 'x.sql'", e.presentation.text)

    val txt = fileOf("x.txt", "SELECT 1")
    val t = event(txt)
    ActionManager.getInstance().getAction(id).update(t)
    assertFalse(t.presentation.isEnabledAndVisible)

    val dir = txt.parent
    val d = event(dir)
    ActionManager.getInstance().getAction(id).update(d)
    assertFalse(d.presentation.isEnabledAndVisible)

    val two = event(sql, txt, single = false)
    ActionManager.getInstance().getAction(id).update(two)
    assertFalse(two.presentation.isEnabledAndVisible)

    val only = event(sql, single = false)
    ActionManager.getInstance().getAction(id).update(only)
    assertTrue(only.presentation.isEnabledAndVisible)
  }

  fun testRunsEveryStatementOnAttachedDataSource() {
    val ds = h2()
    val file = fileOf("two.sql", "CREATE TABLE t (id INT PRIMARY KEY);\nINSERT INTO t VALUES (1);\n")
    DbConsoles.attach(project, file, ds.id)
    runFile(file)
    waitStatus()
    assertEquals(1L, count(ds, "t"))
    assertNotNull(contents()?.selectedContent)
  }

  fun testUsesUnsavedDocumentText() {
    val ds = h2()
    val file = fileOf("unsaved.sql", "SELECT 1")
    DbConsoles.attach(project, file, ds.id)
    val document = FileDocumentManager.getInstance().getDocument(file)!!
    runWriteAction { document.setText("CREATE TABLE u (id INT);\nINSERT INTO u VALUES (7);") }
    assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(document))
    runFile(file)
    waitStatus()
    assertEquals(1L, count(ds, "u"))
  }

  fun testSafetyConfirmNoRunsNothing() {
    val ds = h2()
    exec(ds, "CREATE TABLE s (id INT)", "INSERT INTO s VALUES (1), (2)")
    val file = fileOf("del.sql", "DELETE FROM s;")
    DbConsoles.attach(project, file, ds.id)
    TestDialogManager.setTestDialog(TestDialog.NO)
    runFile(file)
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    assertEquals(2L, count(ds, "s"))
    assertNull(contents()?.selectedContent)
  }

  fun testEmptyFileOpensNoTab() {
    val ds = h2()
    val file = fileOf("empty.sql", "  \n-- only a comment\n")
    DbConsoles.attach(project, file, ds.id)
    runFile(file)
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    assertTrue(contents()?.contentCount ?: 0 == 0)
  }
}
