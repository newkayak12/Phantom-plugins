// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db

import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import dev.phantom.db.core.*
import dev.phantom.db.core.erd.*
import dev.phantom.db.core.exec.*
import kotlinx.coroutines.runBlocking
import java.awt.event.MouseEvent

class ErdDesignerTest : BasePlatformTestCase() {
  companion object {
    init {
      System.setProperty("idea.load.plugins.id", "dev.phantom.db,org.jetbrains.kotlin")
    }
  }

  private val member = ErdTable("member", logicalName = "회원", comment = "members", x = 10, y = 20, columns = listOf(
    ErdColumn("id", "BIGINT", primaryKey = true, nullable = false, autoIncrement = true),
    ErdColumn("email", "VARCHAR(255)", nullable = false, comment = "it's the login"),
    ErdColumn("status", "VARCHAR(20)", default = "'new'"),
  ))
  private val orders = ErdTable("orders", x = 400, y = 300, columns = listOf(ErdColumn("id", "BIGINT", primaryKey = true, nullable = false), ErdColumn("memo", "VARCHAR(100)")))
  private val design = ErdDocument(Dbms.H2, tables = listOf(member, orders)).addRelation("orders", "member")!!

  private fun h2(): DbDataSource {
    val name = "erd${System.nanoTime()}"
    val ds = DbDataSource().apply {
      this.name = name
      dbms = Dbms.H2
      url = "jdbc:h2:mem:$name;DB_CLOSE_DELAY=-1"
      user = "sa"
    }
    DbDataSourceService.getInstance(project).save(ds, CharArray(0))
    return ds
  }

  private fun exec(ds: DbDataSource, sql: String) = runBlocking {
    sql.lines().filterNot { it.startsWith("--") }.joinToString("\n").split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach { statement ->
      val outcome = DbExecutor.execute(project, ds, listOf(statement)).single()
      if (outcome is DbOutcome.Failed) error("$statement: ${outcome.message}")
    }
  }

  private fun read(ds: DbDataSource): ErdDocument = runBlocking {
    ErdImport.read(project, ds, DbMetadataCache.getInstance(project).schemas(ds).first { it.name == "PUBLIC" })
  }

  override fun tearDown() {
    try {
      val service = DbDataSourceService.getInstance(project)
      service.dataSources.toList().forEach {
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

  fun testAFileReadsBackAsTheSameDesign() {
    assertEquals(design, ErdJson.read(ErdJson.write(design)))
    assertEquals(ErdDocument(), ErdJson.read(""))
  }

  private fun assertRefused(text: String) {
    assertThrows(Exception::class.java) { ErdJson.read(text) }
  }

  fun testSomethingElseIsNotADesign() {
    assertRefused("<<<<<<< HEAD")
    assertRefused("""{"tables":[{"x":1}]}""")
  }

  fun testRelationAddsTheColumnAndRenamesCarryThrough() {
    assertEquals(listOf("id", "memo", "member_id"), design.table("orders")!!.columns.map { it.name })
    assertNull("nothing to add twice", design.addRelation("orders", "member"))
    val renamed = design.renameTable("member", "users")!!.renameColumn("users", "id", "uid")!!
    assertEquals(ErdRelation("fk_orders_member", "orders", listOf("member_id"), "users", listOf("uid")), renamed.relations.single())
    assertNull("taken", design.renameTable("member", "ORDERS"))
    assertNull("blank", design.renameColumn("member", "email", " "))
    assertTrue("relation goes with its column", design.removeColumn("orders", "member_id").relations.isEmpty())
    assertNull("no key to reference", ErdDocument(tables = listOf(ErdTable("a"), ErdTable("b"))).addRelation("a", "b"))
  }

  fun testCardinalityFollowsUniqueness() {
    val relation = design.relations.single()
    assertFalse(design.isOneToOne(relation))
    val unique = design.setUnique("orders", "member_id", true)
    assertTrue(unique.isOneToOne(relation))
    assertEquals(listOf("uq_orders_member_id"), unique.table("orders")!!.indexes.map { it.name })
    assertTrue(unique.setUnique("orders", "member_id", false).table("orders")!!.indexes.isEmpty())
  }

  fun testCreateRunsOnADatabaseAndNothingIsLeftToChange() {
    val ds = h2()
    val doc = design.setUnique("member", "email", true)
    exec(ds, ErdSql.create(doc))
    val live = read(ds)
    assertEquals("-- No changes", ErdSql.alter(live, live))
    assertEquals(ErdRelation(live.relations.single().name, "orders", listOf("member_id"), "member", listOf("id")), live.relations.single())
    val imported = live.table("member")!!
    assertEquals("members", imported.comment)
    assertEquals("it's the login", imported.columns.first { it.name == "email" }.comment)
    assertTrue(imported.columns.first { it.name == "id" }.autoIncrement)
    assertEquals("'new'", imported.columns.first { it.name == "status" }.default)
  }

  fun testAlterBringsADatabaseToTheDesign() {
    val ds = h2()
    exec(ds, ErdSql.create(design.copy(relations = emptyList())))
    val live = read(ds)
    val changed = live
      .updateTable("orders") { it.copy(columns = it.columns + ErdColumn("note", "VARCHAR(50)", comment = "note")) }
      .updateColumn("member", "status") { it.copy(default = "'old'", comment = "state") }
      .updateTable("member") { it.copy(comment = "people") }
      .let { it.copy(tables = it.tables + ErdTable("tags", columns = listOf(ErdColumn("id", "INT", primaryKey = true, nullable = false)))) }
      .copy(relations = listOf(ErdRelation("fk_o_m", "orders", listOf("id"), "member", listOf("id"))))
    val alter = ErdSql.alter(changed, live)
    assertTrue(alter, alter.contains("CREATE TABLE \"tags\""))
    assertTrue(alter, alter.contains("FOREIGN KEY"))
    exec(ds, alter)
    val after = read(ds)
    assertEquals("people", after.table("member")!!.comment)
    assertEquals("'old'", after.table("member")!!.columns.first { it.name == "status" }.default)
    assertEquals("-- No changes", ErdSql.alter(changed, after))
  }

  fun testTheSameTypeSpelledAnotherWayIsNoChange() {
    val live = ErdDocument(Dbms.POSTGRESQL, tables = listOf(ErdTable("a", columns = listOf(ErdColumn("id", "int8"), ErdColumn("on", "bool"), ErdColumn("t", "varchar(20)")))))
    val design = ErdDocument(Dbms.POSTGRESQL, tables = listOf(ErdTable("a", columns = listOf(ErdColumn("id", "BIGINT"), ErdColumn("on", "BOOLEAN"), ErdColumn("t", "CHARACTER VARYING(20)")))))
    assertEquals("-- No changes", ErdSql.alter(design, live))
    val wider = design.updateColumn("a", "t") { it.copy(type = "VARCHAR(30)") }
    assertTrue(ErdSql.alter(wider, live).contains("TYPE VARCHAR(30)"))
  }

  fun testDroppingIsOnlyEverSuggested() {
    val without = design.removeColumn("orders", "memo").removeTable("member")
    ErdSql.alter(without, design).lines().filter { it.contains("DROP") }.also { assertFalse(it.isEmpty()) }.forEach { assertTrue(it, it.startsWith("--")) }
  }

  fun testSqlOfOneTableKeepsToIt() {
    val create = ErdSql.create(design, "member")
    assertTrue(create, create.contains("CREATE TABLE \"member\""))
    assertFalse(create, create.contains("FOREIGN KEY"))
    val live = design.updateTable("member") { it.copy(columns = it.columns.dropLast(1)) }.updateTable("orders") { it.copy(columns = it.columns.dropLast(2)) }
    val alter = ErdSql.alter(design, live, "member")
    assertFalse(alter, alter.contains("orders"))
    assertTrue(alter, alter.contains("status"))
  }

  fun testDialects() {
    val mysql = ErdSql.create(design.copy(dbms = Dbms.MYSQL))
    assertTrue(mysql, mysql.contains("AUTO_INCREMENT"))
    assertTrue(mysql, mysql.contains("COMMENT 'it''s the login'"))
    assertTrue(mysql, mysql.contains("ALTER TABLE member COMMENT = 'members';"))
    val pg = ErdSql.create(design.copy(dbms = Dbms.POSTGRESQL))
    assertTrue(pg, pg.contains("GENERATED BY DEFAULT AS IDENTITY"))
    assertTrue(pg, pg.contains("COMMENT ON COLUMN member.email IS 'it''s the login';"))
    val serial = design.copy(dbms = Dbms.POSTGRESQL).updateColumn("member", "id") { it.copy(type = "bigserial") }
    assertFalse(ErdSql.create(serial).contains("bigserial NOT NULL GENERATED"))
  }

  fun testMergingKeepsPlacesAndWhatOnlyTheDesignHas() {
    val db = ErdDocument(Dbms.POSTGRESQL, "dev", "public", listOf(ErdTable("MEMBER", x = 500, columns = listOf(ErdColumn("EMAIL", "TEXT"))), ErdTable("fresh")))
    val merged = ErdImport.merge(design, db)
    val kept = merged.table("MEMBER")!!
    assertEquals(10, kept.x)
    assertEquals("회원", kept.logicalName)
    assertEquals(listOf("TEXT"), kept.columns.map { it.type })
    assertNotNull(merged.table("orders"))
    assertTrue("below the design", merged.table("fresh")!!.y > member.y)
    assertEquals("dev", merged.dataSource)
  }

  private fun erdFile(text: String = ""): VirtualFile = myFixture.tempDirFixture.createFile("schema.erd", text)

  private fun editor(file: VirtualFile): ErdEditor = ErdEditor(project, file).also { Disposer.register(testRootDisposable, it) }

  private fun ErdEditor.click(x: Int, y: Int) {
    canvas.setSize(800, 600)
    canvas.dispatchEvent(MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, 0, 0, x, y, 1, false, MouseEvent.BUTTON1))
    canvas.dispatchEvent(MouseEvent(canvas, MouseEvent.MOUSE_RELEASED, 0, 0, x, y, 1, false, MouseEvent.BUTTON1))
  }

  fun testClickingATableOpensItsPanelsAndTheEmptyCanvasOrCloseFoldsThem() {
    val editor = editor(erdFile(ErdJson.write(design)))
    assertFalse(editor.panelsOpenForTest())
    editor.click(15, 25)
    assertEquals("member", editor.selectionForTest())
    assertTrue(editor.panelsOpenForTest())
    assertTrue(editor.sql.sqlText.text, editor.sql.sqlText.text.contains("member"))
    editor.click(700, 500)
    assertNull(editor.selectionForTest())
    assertFalse(editor.panelsOpenForTest())
    editor.click(15, 25)
    editor.detail.closeButton.doClick()
    assertFalse(editor.panelsOpenForTest())
  }

  fun testEveryChangeIsWrittenAndCanBeUndone() {
    val file = erdFile()
    val editor = editor(file)
    editor.addTable()
    assertEquals("table_1", editor.selectionForTest())
    assertNotNull(ErdJson.read(VfsUtil.loadText(file)).table("table_1"))
    editor.undoForTest()
    assertTrue(ErdJson.read(VfsUtil.loadText(file)).tables.isEmpty())
    assertNull(editor.selectionForTest())
  }

  fun testAFileChangedElsewhereIsReloadedAndABrokenOneIsLeftAlone() {
    val file = erdFile(ErdJson.write(design))
    val editor = editor(file)
    runWriteAction { VfsUtil.saveText(file, ErdJson.write(design.removeTable("orders"))) }
    UIUtil.dispatchAllInvocationEvents()
    assertNull(editor.document.table("orders"))
    runWriteAction { VfsUtil.saveText(file, "<<<<<<< HEAD") }
    UIUtil.dispatchAllInvocationEvents()
    assertTrue(editor.errorShownForTest())
    editor.addTable()
    assertEquals("<<<<<<< HEAD", VfsUtil.loadText(file))
  }

  fun testAlterIsWrittenAgainstTheChosenDatabase() {
    val editor = editor(erdFile(ErdJson.write(design)))
    val live = design.updateTable("orders") { it.copy(columns = it.columns.filter { c -> c.name != "memo" }) }.copy(dataSource = "dev", schema = "PUBLIC")
    editor.loaded(live, tables = false)
    editor.click(15, 25)
    editor.sql.alter.doClick()
    assertEquals("-- No changes", editor.sql.sqlText.text)
    editor.select("orders")
    assertTrue(editor.sql.sqlText.text, editor.sql.sqlText.text.contains("ADD \"memo\""))
  }
}
