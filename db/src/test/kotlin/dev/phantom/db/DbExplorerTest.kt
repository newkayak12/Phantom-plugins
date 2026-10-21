// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.CommonProcessors
import com.intellij.openapi.progress.EmptyProgressIndicator
import dev.phantom.db.core.*
import dev.phantom.db.core.exec.*
import dev.phantom.db.core.ui.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

class DbExplorerTest : BasePlatformTestCase() {
  companion object {
    init {
      System.setProperty("idea.load.plugins.id", "dev.phantom.db,org.jetbrains.kotlin")
    }
  }

  private var counter = 0

  private fun h2(name: String = "ex${counter++}", manualCommit: Boolean = false, url: String? = null): DbDataSource {
    val ds = DbDataSource().apply {
      this.name = name
      dbms = Dbms.H2
      this.url = url ?: "jdbc:h2:mem:${name}_${System.nanoTime()};DB_CLOSE_DELAY=-1"
      this.manualCommit = manualCommit
      user = "sa"
    }
    DbDataSourceService.getInstance(project).save(ds, CharArray(0))
    return ds
  }

  private fun exec(ds: DbDataSource, vararg sql: String): List<DbOutcome> = runBlocking { DbExecutor.execute(project, ds, sql.toList()) }

  override fun tearDown() {
    try {
      FileEditorManager.getInstance(project).openFiles.forEach { FileEditorManager.getInstance(project).closeFile(it) }
      val service = DbDataSourceService.getInstance(project)
      service.dataSources.forEach {
        DbConnectionService.getInstance(project).disconnect(it.id)
        DbMetadataCache.getInstance(project).invalidate(it.id)
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

  private fun DefaultMutableTreeNode.kids(): List<DefaultMutableTreeNode> = (0 until childCount).map { getChildAt(it) as DefaultMutableTreeNode }

  private fun DefaultMutableTreeNode.child(match: (DbNode?) -> Boolean): DefaultMutableTreeNode? = kids().firstOrNull { match(it.userObject as? DbNode) }

  /** Expands [node] and waits until its children are loaded. */
  private fun DbExplorerPanel.expand(node: DefaultMutableTreeNode): List<DefaultMutableTreeNode> {
    tree.expandPath(TreePath(node.path))
    PlatformTestUtil.waitWithEventsDispatching("not loaded: ${node.userObject}", { node.childCount > 0 && node.kids()[0].userObject != DbNode.Loading }, 20)
    return node.kids()
  }

  private fun DbExplorerPanel.folder(parent: DefaultMutableTreeNode, kind: DbNode.Folder.Kind): DefaultMutableTreeNode? =
    parent.child { (it as? DbNode.Folder)?.kind == kind }

  private fun names(nodes: List<DefaultMutableTreeNode>): List<String> = nodes.map { DbNode.text(it.userObject as? DbNode) }

  private fun schemaOf(panel: DbExplorerPanel, ds: DbDataSource, name: String = "PUBLIC"): DefaultMutableTreeNode {
    val source = (panel.tree.model.root as DefaultMutableTreeNode).child { (it as? DbNode.Source)?.dataSource?.id == ds.id }!!
    return panel.expand(source).first { (it.userObject as? DbNode.Schema)?.ref?.name == name }
  }

  private fun objectsSchema(): DbDataSource {
    val ds = h2()
    exec(ds,
         "CREATE TABLE parent (id INT PRIMARY KEY, name VARCHAR(20))",
         "CREATE TABLE child (id INT PRIMARY KEY, parent_id INT REFERENCES parent(id), label VARCHAR(20))",
         "CREATE INDEX child_label ON child(label)",
         "CREATE VIEW parent_names AS SELECT name FROM parent",
         "CREATE SEQUENCE order_seq",
         "CREATE ALIAS my_sqrt FOR 'java.lang.Math.sqrt'",
         "CREATE ALIAS IF NOT EXISTS FT_INIT FOR 'org.h2.fulltext.FullText.init'",
         "CALL FT_INIT()",
         // makes a trigger FT_PARENT on PARENT: H2 has no built-in trigger class that works on its own
         "CALL FT_CREATE_INDEX('PUBLIC', 'PARENT', NULL)")
    return ds
  }

  fun testSchemaFoldersAndObjects() {
    val ds = objectsSchema()
    val panel = DbExplorerPanel(project, testRootDisposable)
    val schema = schemaOf(panel, ds)
    assertEquals(listOf("tables", "views", "routines", "triggers", "sequences"), names(panel.expand(schema)))

    assertEquals(listOf("CHILD", "PARENT"), names(panel.expand(panel.folder(schema, DbNode.Folder.Kind.TABLES)!!)))
    assertEquals(listOf("PARENT_NAMES"), names(panel.expand(panel.folder(schema, DbNode.Folder.Kind.VIEWS)!!)))
    assertContainsElements(names(panel.expand(panel.folder(schema, DbNode.Folder.Kind.ROUTINES)!!)), "MY_SQRT")
    assertEquals(listOf("FT_PARENT"), names(panel.expand(panel.folder(schema, DbNode.Folder.Kind.TRIGGERS)!!)))
    assertEquals(listOf("ORDER_SEQ"), names(panel.expand(panel.folder(schema, DbNode.Folder.Kind.SEQUENCES)!!)))

    val child = panel.folder(schema, DbNode.Folder.Kind.TABLES)!!.child { (it as? DbNode.Table)?.ref?.name == "CHILD" }!!
    val columns = panel.expand(child).mapNotNull { it.userObject as? DbNode.Column }
    assertEquals(listOf("ID", "PARENT_ID", "LABEL"), columns.map { it.info.name })
    assertEquals(listOf(false, true, false), columns.map { it.foreignKey })
    assertTrue(columns[0].info.primaryKey)
    val fks = panel.expand(panel.folder(child, DbNode.Folder.Kind.FOREIGN_KEYS)!!).map { it.userObject as DbNode.ForeignKey }
    assertEquals("PARENT", fks.single().fk.refTable.name)
    assertContainsElements(names(panel.expand(panel.folder(child, DbNode.Folder.Kind.INDEXES)!!)), "CHILD_LABEL")

    val parent = panel.folder(schema, DbNode.Folder.Kind.TABLES)!!.child { (it as? DbNode.Table)?.ref?.name == "PARENT" }!!
    panel.expand(parent)
    assertEquals(listOf("FT_PARENT"), names(panel.expand(panel.folder(parent, DbNode.Folder.Kind.TABLE_TRIGGERS)!!)))
  }

  fun testFilterHidesOtherObjectsAndEmptyFolders() {
    val ds = objectsSchema()
    val panel = DbExplorerPanel(project, testRootDisposable)
    val schema = schemaOf(panel, ds)
    panel.expand(schema)
    for (kind in listOf(DbNode.Folder.Kind.TABLES, DbNode.Folder.Kind.VIEWS, DbNode.Folder.Kind.ROUTINES, DbNode.Folder.Kind.SEQUENCES)) {
      panel.expand(panel.folder(schema, kind)!!)
    }
    // the triggers folder stays unloaded: the filter leaves out what is not loaded
    val tables = panel.folder(schema, DbNode.Folder.Kind.TABLES)!!
    val child = tables.child { (it as? DbNode.Table)?.ref?.name == "CHILD" }!!
    panel.expand(child)

    panel.setFilter("Chi")
    assertEquals(listOf("tables"), names(schema.kids()))
    assertEquals(listOf("CHILD"), names(tables.kids()))
    assertTrue("a matching table keeps its columns", child.kids().any { it.userObject is DbNode.Column })
    assertTrue(panel.tree.isExpanded(TreePath(tables.path)))

    panel.setFilter("seq")
    assertEquals(listOf("sequences"), names(schema.kids()))

    panel.setFilter("nothing-like-this")
    assertEmpty(schema.kids())
    assertNull("the schema is hidden", schema.parent)

    panel.setFilter("")
    assertEquals(listOf("tables", "views", "routines", "triggers", "sequences"), names(schema.kids()))
    assertEquals(listOf("CHILD", "PARENT"), names(tables.kids()))
  }

  fun testSystemSchemasOnlyOnRequest() {
    val ds = h2()
    val cache = DbMetadataCache.getInstance(project)
    val user = runBlocking { cache.schemas(ds) }.map { it.name }
    assertFalse(user.toString(), "INFORMATION_SCHEMA" in user)
    assertTrue("PUBLIC" in user)
    assertTrue("INFORMATION_SCHEMA" in runBlocking { cache.schemas(ds, includeSystem = true) }.map { it.name })
    assertFalse("INFORMATION_SCHEMA" in cache.cachedSchemas(ds.id)!!.map { it.name })
  }

  fun testSearchEverywhereUsesTheCacheOnly() {
    val ds = h2(url = "jdbc:h2:mem:never_${System.nanoTime()};IFEXISTS=TRUE")
    val contributor = DbSearchEverywhereContributor(project)
    fun found(pattern: String): List<DbObjectItem> {
      val collector = CommonProcessors.CollectProcessor<DbObjectItem>()
      contributor.fetchElements(pattern, EmptyProgressIndicator(), collector)
      return collector.results.toList()
    }
    assertEmpty(found("res"))
    assertFalse("the search connected", DbConnectionService.getInstance(project).isConnected(ds.id))

    val real = h2()
    exec(real, "CREATE TABLE reservation (id INT PRIMARY KEY, guest_name VARCHAR(20))", "CREATE VIEW reserved AS SELECT id FROM reservation",
         "CREATE ALIAS res_sqrt FOR 'java.lang.Math.sqrt'")
    assertEmpty("nothing loaded yet", found("res"))
    val cache = DbMetadataCache.getInstance(project)
    runBlocking {
      val schema = cache.schemas(real).first { it.name == "PUBLIC" }
      val table = cache.tables(real, schema).first { it.name == "RESERVATION" }
      cache.columns(real, table)
      DbObjectCache.getInstance(project).routines(real, schema)
    }
    val items = found("res")
    assertEquals(setOf("RESERVATION" to DbObjectItem.Kind.TABLE, "RESERVED" to DbObjectItem.Kind.VIEW, "RES_SQRT" to DbObjectItem.Kind.ROUTINE),
                 items.map { it.name to it.kind }.toSet())
    assertEquals("${real.name} / PUBLIC", items.first { it.kind == DbObjectItem.Kind.TABLE }.location)
    val column = found("guest").single()
    assertEquals(DbObjectItem.Kind.COLUMN, column.kind)
    assertEquals("RESERVATION · ${real.name} / PUBLIC", column.location)
    assertEmpty(found(""))
    assertFalse(DbConnectionService.getInstance(project).isConnected(ds.id))
  }

  fun testNewConsolesGetIncrementingNamesAndBinding() {
    val ds = h2("shop")
    val dir = DbConsoles.root().resolve(ds.id)
    try {
      val first = DbNewConsoleAction.create(project, ds, null)!!
      val second = DbNewConsoleAction.create(project, ds, DbSchemaChoice("PUBLIC"))!!
      val third = DbNewConsoleAction.create(project, ds, null)!!
      assertEquals(listOf("shop.sql", "shop [1].sql", "shop [2].sql"), listOf(first, second, third).map { it.name })
      for (f in listOf(first, second, third)) {
        assertTrue(DbConsoles.isConsole(f))
        assertEquals(ds.id, DbConsoles.dataSourceFor(project, f)?.id)
      }
      assertEquals(Path.of(first.path).parent, dir)
      assertTrue("in the Database Consoles scratch root", com.intellij.ide.scratch.ScratchFileService.findRootType(first) is DbConsoleRootType)
      assertEquals(DbSchemaChoice("PUBLIC"), DbConsoles.schemaFor(project, second))
      assertNull(DbConsoles.schemaFor(project, third))
      assertEquals(listOf(first, second, third), DbConsoles.consoles(ds.id))
      assertEquals(first, DbConsoles.consoleFile(ds))

      // the tree lists them under the data source
      val panel = DbExplorerPanel(project, testRootDisposable)
      val source = (panel.tree.model.root as DefaultMutableTreeNode).child { (it as? DbNode.Source)?.dataSource?.id == ds.id }!!
      val consoles = panel.expand(source).first { (it.userObject as? DbNode.Folder)?.kind == DbNode.Folder.Kind.CONSOLES }
      assertEquals(listOf("shop", "shop [1]", "shop [2]"), names(panel.expand(consoles)))
    }
    finally {
      FileEditorManager.getInstance(project).openFiles.forEach { FileEditorManager.getInstance(project).closeFile(it) }
      com.intellij.openapi.util.io.FileUtil.delete(dir)
    }
  }
}
