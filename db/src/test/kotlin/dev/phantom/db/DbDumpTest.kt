// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.db.core.*
import dev.phantom.db.core.exec.DbDump
import dev.phantom.db.core.ui.DbExplorerPanel
import dev.phantom.db.core.ui.DbTreeAction
import java.io.File
import java.nio.file.Path

/** Dump to File… and Restore from File…: the command lines, the tool lookup, and which data sources get the actions. */
class DbDumpTest : BasePlatformTestCase() {
  companion object {
    init {
      System.setProperty("idea.load.plugins.id", "dev.phantom.db,org.jetbrains.kotlin")
    }
  }

  private val out: Path = Path.of("/tmp/out.sql")

  private fun source(dbms: Dbms, url: String, password: String? = null) = DbDataSource().apply {
    name = "dump"
    this.dbms = dbms
    this.url = url
    user = "app"
  }

  private fun mysql(url: String = "jdbc:mysql://db.example:3307/shop?useSSL=false") = source(Dbms.MYSQL, url)

  override fun tearDown() {
    try {
      DbDump.Tool.entries.forEach { PropertiesComponent.getInstance().unsetValue(it.key) }
    }
    finally {
      super.tearDown()
    }
  }

  fun testMysqlDumpCommand() {
    val cmd = DbDump.dumpCommand(mysql(), null, out, "mysqldump", "s3cret")
    assertEquals("mysqldump", cmd.exePath)
    assertEquals(listOf("--protocol=TCP", "--host=db.example", "--port=3307", "--user=app", "--routines", "--triggers", "--single-transaction",
                        "--result-file=/tmp/out.sql", "shop"), cmd.parametersList.list)
    assertEquals("s3cret", cmd.environment["MYSQL_PWD"])
  }

  fun testPostgresDumpCommand() {
    val cmd = DbDump.dumpCommand(source(Dbms.POSTGRESQL, "jdbc:postgresql://pg:5433/app"), "sales", out, "pg_dump", "s3cret")
    assertEquals(listOf("--host=pg", "--port=5433", "--username=app", "--dbname=app", "--schema=sales", "--file=/tmp/out.sql"), cmd.parametersList.list)
    assertEquals("s3cret", cmd.environment["PGPASSWORD"])
    assertNull(cmd.environment["MYSQL_PWD"])
  }

  fun testRestoreCommands() {
    val my = DbDump.restoreCommand(mysql(), null, out, "mysql", "s3cret")
    assertEquals(listOf("--protocol=TCP", "--host=db.example", "--port=3307", "--user=app", "shop"), my.parametersList.list)
    assertEquals(File("/tmp/out.sql"), my.inputFile)
    val pg = DbDump.restoreCommand(source(Dbms.POSTGRESQL, "jdbc:postgresql://pg:5433/app"), null, out, "psql", "s3cret")
    assertEquals(listOf("--host=pg", "--port=5433", "--username=app", "--dbname=app", "-v", "ON_ERROR_STOP=1", "--file=/tmp/out.sql"), pg.parametersList.list)
    assertEquals("s3cret", pg.environment["PGPASSWORD"])
  }

  fun testPortOmittedWhenUrlHasNone() {
    val cmd = DbDump.dumpCommand(mysql("jdbc:mysql://db.example/shop"), null, out, "mysqldump", null)
    assertTrue(cmd.parametersList.list.none { it.startsWith("--port") })
    val aws = DbDump.dumpCommand(mysql("jdbc:aws-wrapper:mysql://aurora.example/shop"), null, out, "mysqldump", null)
    assertTrue("--host=aurora.example" in aws.parametersList.list)
    assertEquals("shop", aws.parametersList.list.last())
    val pg = DbDump.dumpCommand(source(Dbms.POSTGRESQL, "jdbc:postgresql://pg/app"), null, out, "pg_dump", null)
    assertTrue(pg.parametersList.list.none { it.startsWith("--port") })
  }

  fun testMysqlDataSourceWithoutDatabaseDumpsAllDatabases() {
    for (url in listOf("jdbc:mysql://db.example:3306/", "jdbc:mysql://db.example")) {
      val dump = DbDump.dumpCommand(mysql(url), null, out, "mysqldump", null).parametersList.list
      assertTrue(url, "--all-databases" in dump)
      assertEquals(url, "--all-databases", dump.last())
      assertTrue(url, dump.none { it == "shop" })
      val restore = DbDump.restoreCommand(mysql(url), null, out, "mysql", null).parametersList.list
      assertTrue(url, restore.none { !it.startsWith("--") })
    }
    val pg = DbDump.dumpCommand(source(Dbms.POSTGRESQL, "jdbc:postgresql://pg:5432/"), null, out, "pg_dump", null).parametersList.list
    assertTrue(pg.none { it.startsWith("--dbname") })
  }

  fun testRestoreConfirmationNamesTheRealTarget() {
    // PostgreSQL: psql gets no schema, so the confirmation names the database, not the selected schema
    assertEquals("pg:5433/app", DbDump.restoreTarget(source(Dbms.POSTGRESQL, "jdbc:postgresql://pg:5433/app"), "staging"))
    assertEquals("db.example:3307/shop", DbDump.restoreTarget(mysql(), null))
    assertEquals("db.example:3306/sales", DbDump.restoreTarget(mysql("jdbc:mysql://db.example:3306/"), "sales"))
  }

  fun testAllDatabasesDumpIsAskedFor() {
    assertTrue(DbDump.dumpsAllDatabases(mysql("jdbc:mysql://db.example:3306/"), null))
    assertFalse(DbDump.dumpsAllDatabases(mysql("jdbc:mysql://db.example:3306/"), "shop"))
    assertFalse(DbDump.dumpsAllDatabases(mysql(), null))
    assertFalse(DbDump.dumpsAllDatabases(source(Dbms.POSTGRESQL, "jdbc:postgresql://pg:5432/"), null))
  }

  fun testFailedDumpKeepsTheEarlierFile() {
    val dir = java.nio.file.Files.createTempDirectory("phantom-dump").toFile()
    try {
      val file = File(dir, "shop.sql").apply { writeText("good dump") }
      val part = DbDump.partFile(file)
      assertEquals(dir.canonicalFile, part.parentFile.canonicalFile)
      part.writeText("half a dump")
      DbDump.finishDump(part, file, success = false)
      assertEquals("good dump", file.readText())
      assertFalse(part.exists())
      part.writeText("new dump")
      DbDump.finishDump(part, file, success = true)
      assertEquals("new dump", file.readText())
      assertFalse(part.exists())
    }
    finally {
      dir.deleteRecursively()
    }
  }

  fun testMysqlSchemaNodeDumpsThatSchema() {
    val dump = DbDump.dumpCommand(mysql("jdbc:mysql://db.example:3306/"), "shop", out, "mysqldump", null).parametersList.list
    assertEquals("shop", dump.last())
    assertFalse("--all-databases" in dump)
    val restore = DbDump.restoreCommand(mysql("jdbc:mysql://db.example:3306/"), "shop", out, "mysql", null).parametersList.list
    assertEquals("shop", restore.last())
  }

  fun testPasswordNeverInArguments() {
    val commands = listOf(
      DbDump.dumpCommand(mysql(), null, out, "mysqldump", "s3cret"),
      DbDump.restoreCommand(mysql(), null, out, "mysql", "s3cret"),
      DbDump.dumpCommand(source(Dbms.POSTGRESQL, "jdbc:postgresql://pg:5433/app"), "sales", out, "pg_dump", "s3cret"),
      DbDump.restoreCommand(source(Dbms.POSTGRESQL, "jdbc:postgresql://pg:5433/app"), null, out, "psql", "s3cret"),
    )
    for (cmd in commands) {
      assertFalse(cmd.commandLineString, cmd.commandLineString.contains("s3cret"))
      assertTrue(cmd.parametersList.list.none { it.contains("s3cret") || it.startsWith("--password") })
    }
  }

  fun testSupportedOnlyForMysqlMariadbPostgres() {
    assertTrue(DbDump.supported(mysql()))
    assertTrue(DbDump.supported(mysql("jdbc:mysql://db.example:3306/")))
    assertTrue(DbDump.supported(mysql("jdbc:mysql://db.example")))
    assertTrue(DbDump.supported(source(Dbms.MARIADB, "jdbc:mariadb://m:3306/x")))
    assertTrue(DbDump.supported(source(Dbms.POSTGRESQL, "jdbc:postgresql://pg/app")))
    assertFalse(DbDump.supported(source(Dbms.H2, "jdbc:h2:mem:x")))
    assertFalse(DbDump.supported(source(Dbms.GENERIC, "jdbc:foo://h/x")))
    assertFalse(DbDump.supported(mysql("jdbc:mysql:nohost")))
  }

  fun testToolPathOverrideWinsOverPath() {
    val tool = File.createTempFile("mysqldump", "").apply { setExecutable(true); deleteOnExit() }
    try {
      DbDump.setPath(DbDump.Tool.MYSQLDUMP, tool.path)
      assertEquals(tool.path, DbDump.path(DbDump.Tool.MYSQLDUMP))
      // not executable: ignored
      DbDump.setPath(DbDump.Tool.MYSQLDUMP, "/nonexistent/mysqldump")
      assertTrue(DbDump.path(DbDump.Tool.MYSQLDUMP) != "/nonexistent/mysqldump")
    }
    finally {
      PropertiesComponent.getInstance().unsetValue(DbDump.Tool.MYSQLDUMP.key)
    }
  }

  fun testNoPasswordLeavesEnvironmentUnset() {
    for (password in listOf(null, "")) {
      val my = DbDump.dumpCommand(mysql(), null, out, "mysqldump", password)
      assertFalse(my.environment.containsKey("MYSQL_PWD"))
      val pg = DbDump.restoreCommand(source(Dbms.POSTGRESQL, "jdbc:postgresql://pg/app"), null, out, "psql", password)
      assertFalse(pg.environment.containsKey("PGPASSWORD"))
    }
  }

  fun testTreeActionsRegisteredAndHiddenForUnsupported() {
    val manager = ActionManager.getInstance()
    val panel = DbExplorerPanel(project, testRootDisposable)
    val context = SimpleDataContext.builder()
      .add(DbExplorerPanel.KEY, panel)
      .add(com.intellij.openapi.actionSystem.PlatformCoreDataKeys.CONTEXT_COMPONENT, panel.tree)
      .build()
    for (name in listOf("Dump", "Restore")) {
      val action = manager.getAction("PhantomDb.Tree.$name")
      assertTrue(name, action is DbTreeAction)
      assertFalse(name, action.templateText.isNullOrBlank())
      // nothing selected: no supported data source, so the action is hidden, not greyed out
      val event = TestActionEvent.createTestEvent(action, context)
      action.update(event)
      assertFalse(name, event.presentation.isVisible)
      assertNotNull(name, panel.command("PhantomDb.Tree.$name"))
    }
  }
}
