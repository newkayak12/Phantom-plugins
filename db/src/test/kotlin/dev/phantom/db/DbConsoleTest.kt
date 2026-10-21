// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db

import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.XmlSerializer
import com.intellij.openapi.util.JDOMUtil
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.LocalFileSystem
import dev.phantom.db.core.*
import dev.phantom.db.core.drivers.DbDriverRegistry
import dev.phantom.db.core.exec.*
import dev.phantom.db.core.ui.DbChangesToolWindowFactory
import dev.phantom.db.core.ui.DbDataSourceDialog
import dev.phantom.db.core.ui.DbExplorerPanel
import dev.phantom.db.core.ui.DbNode
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

class DbConsoleTest : BasePlatformTestCase() {
  companion object {
    init {
      System.setProperty("idea.load.plugins.id", "dev.phantom.db,org.jetbrains.kotlin")
    }

    /** The H2 driver from the local Gradle or Maven cache: the same lookup a data source without a chosen jar uses. */
    val h2Jar: Path by lazy {
      DbDrivers.fromLocalCaches(DbDriverRegistry.builtIns.single { it.id == "h2" })?.jars?.single() ?: error("These tests need com.h2database:h2 in ~/.gradle or ~/.m2")
    }
  }

  private var counter = 0

  private fun h2(name: String = "db${counter++}", readOnly: Boolean = false, manualCommit: Boolean = false, url: String? = null): DbDataSource {
    val ds = DbDataSource().apply {
      this.name = name
      dbms = Dbms.H2
      this.url = url ?: "jdbc:h2:mem:${name}_${System.nanoTime()};DB_CLOSE_DELAY=-1"
      this.manualCommit = manualCommit
      user = "sa"
      this.readOnly = readOnly
      maxRows = 2
    }
    DbDataSourceService.getInstance(project).save(ds, CharArray(0))
    return ds
  }

  private fun exec(ds: DbDataSource, vararg sql: String): List<DbOutcome> = runBlocking { DbExecutor.execute(project, ds, sql.toList()) }

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

  fun testStatementsAndCaret() {
    val file = myFixture.configureByText("a.sql", """
      -- header
      SELECT 'a;b' FROM t;
      UPDATE t SET x = 1 WHERE id = 2;  -- trailing
      /* lead */ INSERT INTO t VALUES (1);
      SELECT 1
    """.trimIndent()) as SqlFile
    val statements = file.statements
    assertEquals(listOf("SELECT 'a;b' FROM t", "UPDATE t SET x = 1 WHERE id = 2", "INSERT INTO t VALUES (1)", "SELECT 1"),
                 statements.map { it.executableText })
    assertEquals(listOf("SELECT", "UPDATE", "INSERT", "SELECT"), statements.map { it.firstKeyword })
    val text = file.text
    assertEquals("UPDATE t SET x = 1 WHERE id = 2", file.statementAt(text.indexOf("x = 1"))?.executableText)
    // caret right after `;` still means the statement just finished
    assertEquals("SELECT 'a;b' FROM t", file.statementAt(text.indexOf("FROM t;") + 7)?.executableText)
    assertEquals("SELECT 1", file.statementAt(text.length)?.executableText)
    assertEquals(listOf("SELECT 1", "SELECT \$\$a;b\$\$", "SELECT 2"), SqlStatements.split("SELECT 1; SELECT \$\$a;b\$\$;\n-- c;\nSELECT 2;"))
  }

  fun testSafetyChecks() {
    val ds = DbDataSource().apply { name = "prod"; readOnly = true }
    assertTrue(SqlSafety.missingWhere("DELETE FROM reservation"))
    assertTrue(SqlSafety.missingWhere("/* x */ update t set a = 1"))
    assertFalse(SqlSafety.missingWhere("UPDATE t SET a = 1 WHERE id = 3"))
    assertFalse(SqlSafety.missingWhere("SELECT * FROM t"))
    assertTrue(SqlSafety.check(ds, listOf("SELECT 1", "DROP TABLE t")) is SqlSafety.Verdict.Blocked)
    assertEquals(SqlSafety.Verdict.Ok, SqlSafety.check(ds, listOf("SELECT 1", "SHOW TABLES", "EXPLAIN SELECT 1")))
    assertTrue(SqlSafety.check(DbDataSource(), listOf("DELETE FROM t")) is SqlSafety.Verdict.Confirm)
    assertTrue(SqlSafety.isDdl("create table x (id int)"))
  }

  fun testSavedStateHasNoPassword() {
    val ds = h2("saved")
    DbDataSourceService.getInstance(project).save(ds, "s3cret".toCharArray())
    val xml = JDOMUtil.write(XmlSerializer.serialize(DbDataSourceService.getInstance(project).state))
    assertTrue(xml, xml.contains(ds.url))
    assertFalse(xml, xml.contains("s3cret"))
    assertEquals("s3cret", DbDataSourceService.getInstance(project).password(ds.id))
    assertEquals("mysql@db.local", DbDataSourceDialog.defaultName(Dbms.MYSQL, "jdbc:mysql://db.local:3306/app"))
    assertEquals(Dbms.MYSQL, Dbms.forUrl("jdbc:p6spy:mysql://localhost/x"))
  }

  fun testDriverLookup() {
    val home = Files.createTempDirectory("phantom-db-home")
    try {
      for (v in listOf("8.0.33", "9.1.0")) {
        val dir = home.resolve(".gradle/caches/modules-2/files-2.1/com.mysql/mysql-connector-j/$v/abc$v")
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("mysql-connector-j-$v.jar"), "")
        Files.writeString(dir.resolve("mysql-connector-j-$v-sources.jar"), "")
      }
      val found = DbDrivers.fromLocalCaches(DbDriverRegistry.builtIns.single { it.id == "mysql" }, home)!!
      assertEquals("9.1.0", found.version)
      assertNull(DbDrivers.fromLocalCaches(DbDriverRegistry.builtIns.single { it.id == "postgresql" }, home))
    }
    finally {
      FileUtil.delete(home)
    }

    // the project's own dependency comes first; a Gradle-imported library name also makes the tool window appear
    assertFalse(DbDrivers.projectHasDriverLibrary(project))
    val jarRoot = JarFileSystem.getInstance().getJarRootForLocalFile(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(h2Jar)!!)!!
    val library = PsiTestUtil.addProjectLibrary(module, "Gradle: com.h2database:h2:2.3.232", listOf(jarRoot), emptyList())
    try {
      val fromProject = DbDrivers.find(project, DbDataSource().apply { dbms = Dbms.H2 })!!
      assertEquals(DbDriver.Origin.PROJECT_LIBRARY, fromProject.origin)
      assertEquals(h2Jar.fileName, fromProject.jars.single().fileName)
      assertTrue(DbDrivers.projectHasDriverLibrary(project))
    }
    finally {
      PsiTestUtil.removeLibrary(module, library)
    }
  }

  fun testExecutesOnH2InItsOwnClassLoader() {
    val ds = h2()
    val outcomes = exec(ds,
                        "CREATE TABLE reservation (id BIGINT PRIMARY KEY, guest VARCHAR(40) NOT NULL, note CLOB)",
                        "INSERT INTO reservation VALUES (1, 'kim', 'a'), (2, 'lee', NULL), (3, 'park', 'c')",
                        "SELECT id, guest FROM reservation ORDER BY id",
                        "UPDATE reservation SET guest = 'choi' WHERE id = 3")
    assertEquals(4, outcomes.size)
    assertEquals(3L, (outcomes[1] as DbOutcome.Updated).count)
    val rows = outcomes[2] as DbOutcome.Rows
    assertEquals(listOf("ID", "GUEST"), rows.columns.map { it.name })
    assertEquals(listOf(listOf(1L, "kim"), listOf(2L, "lee")), rows.rows.map { it.toList() })
    assertTrue("maxRows = 2 must cut 3 rows", rows.truncated)
    assertEquals(1L, (outcomes[3] as DbOutcome.Updated).count)

    // a failure stops the rest and keeps the driver message
    val failed = exec(ds, "SELECT nope FROM reservation", "DELETE FROM reservation")
    assertEquals(1, failed.size)
    assertTrue((failed[0] as DbOutcome.Failed).message, (failed[0] as DbOutcome.Failed).message.contains("NOPE"))
    assertEquals(3, ((exec(ds, "SELECT COUNT(*) FROM reservation")[0] as DbOutcome.Rows).rows[0][0] as Number).toInt())

    // the driver came from its own loader, not from the IDE classpath
    val connection = DbConnectionService.getInstance(project).open(ds, "")
    connection.use { assertTrue(it.javaClass.classLoader is java.net.URLClassLoader) }
    assertTrue(DbConnectionService.getInstance(project).isConnected(ds.id))
  }

  fun testReadOnlyConnectionAndCancel() {
    val ds = h2()
    val job = DbConnectionService.getInstance(project).launch {
      DbExecutor.execute(project, ds, listOf("SELECT SUM(X) FROM SYSTEM_RANGE(1, 5000000000)"))
    }
    Thread.sleep(500)
    val started = System.nanoTime()
    runBlocking { job.cancelAndJoin() }
    val waitedMs = (System.nanoTime() - started) / 1_000_000
    assertTrue("cancel took $waitedMs ms", waitedMs < 5000)
    // the connection still works after a cancelled statement
    assertEquals(1, ((exec(ds, "SELECT 1")[0] as DbOutcome.Rows).rows[0][0] as Number).toInt())
  }

  fun testSchemaTreeAndCompletionUseTheCache() {
    val ds = h2()
    exec(ds, "CREATE TABLE reservation (id BIGINT PRIMARY KEY, guest_name VARCHAR(40) NOT NULL)", "CREATE VIEW guests AS SELECT guest_name FROM reservation")
    val file = myFixture.configureByText("q.sql", "SELECT * FROM <caret>").virtualFile
    DbConsoles.attach(project, file, ds.id)

    // nothing in the tree yet: completion loads the current schema's tables itself
    myFixture.completeBasic()
    assertContainsElements(myFixture.lookupElementStrings!!, "RESERVATION", "GUESTS")

    val panel = DbExplorerPanel(project, testRootDisposable)
    val tree = panel.tree
    val sourceNode = (tree.model.root as DefaultMutableTreeNode).getChildAt(0) as DefaultMutableTreeNode
    tree.expandPath(TreePath(sourceNode.path))
    // H2 has one user schema, PUBLIC, which opens by itself; then expand the table
    PlatformTestUtil.waitWithEventsDispatching("tables not loaded", { tableNode(sourceNode, "RESERVATION") != null }, 20)
    val table = tableNode(sourceNode, "RESERVATION")!!
    assertEquals("VIEW", (tableNode(sourceNode, "GUESTS")!!.userObject as DbNode.Table).ref.kind)
    tree.expandPath(TreePath(table.path))
    PlatformTestUtil.waitWithEventsDispatching("columns not loaded", { (table.getChildAt(0) as DefaultMutableTreeNode).userObject is DbNode.Column }, 20)
    val columns = (0 until table.childCount).mapNotNull { (table.getChildAt(it) as DefaultMutableTreeNode).userObject as? DbNode.Column }
    assertEquals(listOf("ID", "GUEST_NAME"), columns.map { it.info.name })
    assertTrue(columns[0].info.primaryKey)
    assertEquals("SELECT * FROM PUBLIC.RESERVATION;", panel.selectAll(table.userObject as DbNode.Table))

    myFixture.configureByText("q.sql", "SELECT * FROM <caret>")
    DbConsoles.attach(project, myFixture.file.virtualFile, ds.id)
    myFixture.completeBasic()
    assertContainsElements(myFixture.lookupElementStrings!!, "RESERVATION", "GUESTS")

    myFixture.configureByText("q.sql", "SELECT r.<caret> FROM reservation r")
    DbConsoles.attach(project, myFixture.file.virtualFile, ds.id)
    myFixture.completeBasic()
    assertSameElements(myFixture.lookupElementStrings!!, "ID", "GUEST_NAME")
  }

  private fun tableNode(source: DefaultMutableTreeNode, name: String): DefaultMutableTreeNode? {
    val schema = (0 until source.childCount).map { source.getChildAt(it) as DefaultMutableTreeNode }
                   .firstOrNull { (it.userObject as? DbNode.Schema)?.ref?.name == "PUBLIC" } ?: return null
    return (0 until schema.childCount).map { schema.getChildAt(it) as DefaultMutableTreeNode }
      .firstOrNull { (it.userObject as? DbNode.Table)?.ref?.name == name }
  }

  fun testRunGutterAndResultsWindow() {
    val ds = h2()
    exec(ds, "CREATE TABLE t (id INT, name VARCHAR(10))", "INSERT INTO t VALUES (1, 'a'), (2, 'b'), (3, 'c')")
    val console = DbConsoles.consoleFile(ds)!!
    assertEquals(ds.id, DbConsoles.dataSourceFor(project, console)?.id)
    myFixture.configureByText("run.sql", "SELECT * FROM t ORDER BY id;\nUPDATE t SET name = 'z' WHERE id = 1;\n")
    DbConsoles.attach(project, myFixture.file.virtualFile, ds.id)
    assertEquals(2, myFixture.findAllGutters().size)

    DbRunner.start(project, myFixture.file.virtualFile, ds, listOf("SELECT * FROM t ORDER BY id"))
    val toolWindow = { ToolWindowManager.getInstance(project).getToolWindow(DbResultsToolWindow.ID) }
    val panel = { toolWindow()?.contentManager?.selectedContent?.component as? DbResultPanel }
    try {
      try {
        PlatformTestUtil.waitWithEventsDispatching("no rows shown", { panel()?.shownRows != null }, 20)
      }
      catch (e: AssertionError) {
        throw AssertionError("no rows shown; status: " + panel()?.shownStatus, e)
      }
      assertEquals(listOf(listOf(1, "a"), listOf(2, "b")), panel()!!.shownRows)
      assertTrue(panel()!!.shownStatus, panel()!!.shownStatus.startsWith("2+ rows"))

      // the same file reuses its tab
      DbRunner.start(project, myFixture.file.virtualFile, ds, listOf("UPDATE t SET name = 'z' WHERE id = 1", "SELECT name FROM t WHERE id = 1"))
      PlatformTestUtil.waitWithEventsDispatching("second run not shown", { panel()?.shownStatus?.startsWith("2 statements") == true }, 20)
      assertEquals(listOf(listOf("z")), panel()!!.shownRows)
      assertEquals(1, toolWindow()!!.contentManager.contentCount)
    }
    finally {
      toolWindow()?.contentManager?.removeAllContents(true)
    }
  }

  fun testManualCommitKeepsChangesUntilCommitted() {
    val manual = h2("manual", manualCommit = true)
    val auto = h2("auto", url = manual.url)
    val pending = DbPendingChanges.getInstance(project)
    fun count(ds: DbDataSource) = ((exec(ds, "SELECT COUNT(*) FROM t").single() as DbOutcome.Rows).rows.single()[0] as Number).toInt()

    exec(manual, "CREATE TABLE t(id INT)")
    assertFalse("H2 commits DDL itself", pending.hasPending(manual.id))

    exec(manual, "INSERT INTO t VALUES (1)", "INSERT INTO t VALUES (2)")
    assertEquals(listOf("INSERT INTO t VALUES (1)", "INSERT INTO t VALUES (2)"), pending.of(manual.id).map { it.sql })
    assertEquals(listOf(1L, 1L), pending.of(manual.id).map { it.rows })
    assertEquals("the other connection does not see uncommitted rows", 0, count(auto))
    assertEquals("reads add nothing to the transaction", 2, pending.of(manual.id).size)

    runBlocking { pending.rollback(manual) }
    assertFalse(pending.hasPending(manual.id))
    assertEquals(0, count(manual))

    exec(manual, "INSERT INTO t VALUES (3)")
    runBlocking { pending.commit(manual) }
    assertEquals(1, count(auto))

    exec(manual, "UPDATE t SET id = 4 WHERE id = 3")
    exec(manual, "COMMIT")
    assertFalse("COMMIT typed in the console ends the transaction too", pending.hasPending(manual.id))
    assertEquals(4, ((exec(auto, "SELECT id FROM t").single() as DbOutcome.Rows).rows.single()[0] as Number).toInt())
  }

  fun testDisconnectRollsBackAndForgetsChanges() {
    val manual = h2("manual", manualCommit = true)
    val auto = h2("auto", url = manual.url)
    exec(manual, "CREATE TABLE t(id INT)")
    exec(manual, "INSERT INTO t VALUES (1)")
    DbConnectionService.getInstance(project).disconnect(manual.id)
    assertFalse(DbPendingChanges.getInstance(project).hasPending(manual.id))
    PlatformTestUtil.waitWithEventsDispatching("connection not closed", { !DbConnectionService.getInstance(project).isConnected(manual.id) }, 10)
    assertEquals(0L, ((exec(auto, "SELECT COUNT(*) FROM t").single() as DbOutcome.Rows).rows.single()[0] as Number).toLong())
  }

  fun testChangesToolWindowOnlyForManualCommit() {
    h2("auto")
    assertFalse(runBlocking { DbChangesToolWindowFactory().isApplicableAsync(project) })
    h2("manual", manualCommit = true)
    assertTrue(runBlocking { DbChangesToolWindowFactory().isApplicableAsync(project) })
  }
}
