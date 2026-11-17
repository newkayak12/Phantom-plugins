// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.db.core.*
import dev.phantom.db.core.exec.*
import dev.phantom.db.core.ui.*
import kotlinx.coroutines.runBlocking
import java.awt.Dimension

/** CSV import (parsing, format guess, mapping, H2 import with rollback) and the ER diagram (layout, PNG). */
class DbImportDiagramTest : BasePlatformTestCase() {
  companion object {
    init {
      System.setProperty("idea.load.plugins.id", "dev.phantom.db,org.jetbrains.kotlin")
    }
  }

  private var counter = 0

  private fun h2(manualCommit: Boolean = false): DbDataSource {
    val name = "imp${counter++}"
    val ds = DbDataSource().apply {
      this.name = name
      dbms = Dbms.H2
      url = "jdbc:h2:mem:${name}_${System.nanoTime()};DB_CLOSE_DELAY=-1"
      this.manualCommit = manualCommit
      user = "sa"
    }
    DbDataSourceService.getInstance(project).save(ds, CharArray(0))
    return ds
  }

  private fun exec(ds: DbDataSource, vararg sql: String) = runBlocking { DbExecutor.execute(project, ds, sql.toList()) }.onEach {
    if (it is DbOutcome.Failed) error(it.message)
  }

  private fun rows(ds: DbDataSource, sql: String) = (exec(ds, sql).single() as DbOutcome.Rows).rows.map { r -> r.map { it?.toString() } }

  private fun table(ds: DbDataSource, name: String): DbTableRef = runBlocking {
    val cache = DbMetadataCache.getInstance(project)
    cache.tables(ds, cache.schemas(ds).first { it.name == "PUBLIC" }).first { it.name == name }
  }

  override fun tearDown() {
    try {
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

  fun testCsvParsing() {
    val text = "id,name,note\r\n1,\"Smith, John\",\"says \"\"hi\"\"\nand bye\"\n2,,\"\"\n\n3,x"
    assertEquals(listOf(listOf("id", "name", "note"), listOf("1", "Smith, John", "says \"hi\"\nand bye"), listOf("2", null, ""), listOf("3", "x")),
                 DbCsvReader.readAll(text, DbCsvFormat()))
    val reader = DbCsvReader(text.reader(), ',', '"')
    reader.next(); assertEquals(1, reader.line)
    reader.next(); assertEquals(2, reader.line)
    reader.next(); assertEquals(4, reader.line)
    reader.next(); assertEquals(6, reader.line)
    try {
      DbCsvReader.readAll("a,\"b\nc", DbCsvFormat())
      fail("unterminated quote")
    }
    catch (_: DbException) {
    }
  }

  fun testFormatDetectionAndMapping() {
    assertEquals(DbCsvFormat(';', '"', true), DbCsv.detect("id;name\n1;a\n2;b\n"))
    assertEquals(DbCsvFormat('\t', '"', false), DbCsv.detect("1\tx, y\n2\tz\n"))
    assertEquals(DbCsvFormat(',', '\'', false), DbCsv.detect("1,'a,b'\n2,'c'\n"))
    // names over names: a header only if they name the table's columns
    assertTrue(DbCsv.detect("Name,City\nann,rome\n", listOf("NAME", "CITY")).header)
    assertFalse(DbCsv.detect("ann,rome\nbob,oslo\n", listOf("NAME", "CITY")).header)
    assertEquals(listOf(1, 0, null), DbCsv.mapping(listOf("full name", "ID"), 2, listOf("ID", "FULL_NAME", "CREATED")))
    assertEquals(listOf(0, 1, null), DbCsv.mapping(null, 2, listOf("ID", "FULL_NAME", "CREATED")))
    // JDBC FLOAT is a double; REAL a float
    assertEquals(0.1, DbCsvImport.convert("0.1", java.sql.Types.FLOAT))
    assertEquals(0.5f, DbCsvImport.convert("0.5", java.sql.Types.REAL))
  }

  fun testImportIntoH2AndRollbackOnBadRow() {
    val ds = h2()
    exec(ds, "CREATE TABLE person (id INT PRIMARY KEY, name VARCHAR(40), born DATE, score DECIMAL(5,2))")
    val t = table(ds, "PERSON")
    val columns = listOf("ID", "NAME", "BORN", "SCORE")
    val csv = buildString {
      append("score,id,name,born\n")
      for (i in 1..1203) append("1.5,$i,\"p, $i\",2000-01-02\n")
    }
    val format = DbCsv.detect(csv, columns)
    val mapping = DbCsv.mapping(DbCsvReader.readAll(csv.lineSequence().first(), format).first().map { it.orEmpty() }, 4, columns)
    assertEquals(listOf(1, 2, 3, 0), mapping)
    val result = runBlocking {
      DbCsvImport.run(project, ds, t, columns, mapping, DbCsvReader(csv.reader(), format.delimiter, format.quote), true, null, "(test)")
    }
    assertEquals(DbCsvResult(1203, false), result)
    assertEquals(listOf(listOf("1203", "p, 7", "2000-01-02", "1.50")), rows(ds, "SELECT COUNT(*), MAX(CASE WHEN id = 7 THEN name END), MIN(born), MIN(score) FROM person"))

    // a duplicate key in the second batch: nothing of the file stays, and the line is named
    exec(ds, "DELETE FROM person")
    val bad = buildString {
      for (i in 1..700) append("$i,n$i\n")
      append("5,dup\n")
    }
    try {
      runBlocking { DbCsvImport.run(project, ds, t, columns, listOf(0, 1, null, null), DbCsvReader(bad.reader(), ',', '"'), false, null, "") }
      fail("duplicate key")
    }
    catch (e: DbException) {
      assertTrue(e.message, e.message!!.contains("701"))
    }
    assertEquals(listOf(listOf("0")), rows(ds, "SELECT COUNT(*) FROM person"))
    // a value the column cannot take
    try {
      runBlocking { DbCsvImport.run(project, ds, t, columns, listOf(0, 1, null, null), DbCsvReader("1,a\nx,b\n".reader(), ',', '"'), false, null, "") }
      fail("not a number")
    }
    catch (e: DbException) {
      assertTrue(e.message, e.message!!.contains("line 2"))
    }
    assertEquals(listOf(listOf("0")), rows(ds, "SELECT COUNT(*) FROM person"))
    // still in auto-commit afterwards
    exec(ds, "INSERT INTO person (id) VALUES (99)")
    assertFalse(DbPendingChanges.getInstance(project).hasPending(ds.id))
  }

  fun testImportInManualCommitWaitsInChanges() {
    val ds = h2(manualCommit = true)
    exec(ds, "CREATE TABLE item (id INT PRIMARY KEY, label VARCHAR(10))")
    runBlocking { DbConnectionService.getInstance(project).endTransaction(ds, commit = true) }
    exec(ds, "INSERT INTO item VALUES (100, 'before')")
    val t = table(ds, "ITEM")
    val result = runBlocking {
      DbCsvImport.run(project, ds, t, listOf("ID", "LABEL"), listOf(0, 1), DbCsvReader("1,a\n2,b\n".reader(), ',', '"'), false, null, "(x.csv)")
    }
    assertEquals(DbCsvResult(2, true), result)
    assertEquals(2, DbPendingChanges.getInstance(project).of(ds.id).size)
    // a failing import rolls back to before itself only
    try {
      runBlocking { DbCsvImport.run(project, ds, t, listOf("ID", "LABEL"), listOf(0, 1), DbCsvReader("3,c\n1,dup\n".reader(), ',', '"'), false, null, "") }
      fail("duplicate key")
    }
    catch (_: DbException) {
    }
    assertEquals(listOf(listOf("3")), rows(ds, "SELECT COUNT(*) FROM item"))
    runBlocking { DbPendingChanges.getInstance(project).rollback(ds) }
    assertEquals(listOf(listOf("0")), rows(ds, "SELECT COUNT(*) FROM item"))
  }

  private fun ref(name: String) = DbTableRef("d", null, "s", name, "TABLE")

  private fun fk(from: String, to: String) = DbForeignKey(null, ref(from), listOf("${to}_id"), ref(to), listOf("id"))

  fun testDiagramLayersFollowForeignKeys() {
    val tables = listOf("order_item", "customer", "orders", "product", "note").map {
      DbDiagramTable(ref(it), listOf(DbColumnInfo("id", "INT", false, true)), emptySet())
    }
    // chain customer <- orders <- order_item -> product, plus a self reference and a cycle-free loner
    val model = DbDiagramModel("d", tables, listOf(fk("orders", "customer"), fk("order_item", "orders"), fk("order_item", "product"),
                                                   fk("customer", "customer")), false)
    assertEquals(listOf(listOf("customer", "note", "product"), listOf("orders"), listOf("order_item")),
                 DbDiagramLayout.layers(model).map { l -> l.map { it.name } })
    val boxes = DbDiagramLayout.place(model) { Dimension(100, 50) }
    assertEquals(boxes.getValue(ref("customer")).x, boxes.getValue(ref("product")).x)
    assertTrue(boxes.getValue(ref("orders")).x > boxes.getValue(ref("customer")).x)
    assertTrue(boxes.getValue(ref("order_item")).x > boxes.getValue(ref("orders")).x)
    assertTrue(boxes.getValue(ref("note")).y > boxes.getValue(ref("customer")).y)
    // a cycle does not hang
    val cyclic = DbDiagramModel("c", tables.take(3), listOf(fk("orders", "customer"), fk("customer", "orders")), false)
    assertEquals(3, DbDiagramLayout.layers(cyclic).flatten().size)
  }

  fun testDiagramFromH2AndPng() {
    val ds = h2()
    exec(ds, "CREATE TABLE customer (id INT PRIMARY KEY, name VARCHAR(20))",
         "CREATE TABLE orders (id INT PRIMARY KEY, customer_id INT REFERENCES customer(id))",
         "CREATE TABLE order_item (id INT PRIMARY KEY, order_id INT REFERENCES orders(id))",
         "CREATE VIEW v AS SELECT * FROM customer")
    val schema = runBlocking { DbMetadataCache.getInstance(project).schemas(ds).first { it.name == "PUBLIC" } }
    val model = runBlocking { DbDiagrams.load(project, ds, schema, null) }
    assertEquals(setOf("CUSTOMER", "ORDERS", "ORDER_ITEM"), model.tables.map { it.ref.name }.toSet())
    assertEquals(2, model.foreignKeys.size)
    assertEquals(setOf("CUSTOMER_ID"), model.tables.first { it.ref.name == "ORDERS" }.fkColumns)
    assertEquals(listOf(listOf("CUSTOMER"), listOf("ORDERS"), listOf("ORDER_ITEM")), DbDiagramLayout.layers(model).map { l -> l.map { it.name } })
    val panel = DbDiagramPanel(model)
    val image = panel.toImage()
    assertTrue(image.width > 200 && image.height > 50)
    val colors = HashSet<Int>()
    for (x in 0 until image.width step 3) for (y in 0 until image.height step 3) colors += image.getRGB(x, y)
    assertTrue("something is drawn", colors.size > 3)
    // the editor opens on the diagram file
    val file = DbDiagramFile(model)
    assertTrue(DbDiagramEditorProvider().accept(project, file))
  }
}
