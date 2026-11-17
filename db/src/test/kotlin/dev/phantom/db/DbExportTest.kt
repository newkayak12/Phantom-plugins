// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.db.core.DbDataSource
import dev.phantom.db.core.Dbms
import dev.phantom.db.core.exec.*
import java.sql.Types

class DbExportTest : BasePlatformTestCase() {
  private val rows = DbOutcome.Rows(
    "SELECT * FROM shop.reservation WHERE id < 3",
    listOf(DbColumn("id", Types.BIGINT, "BIGINT", "java.lang.Long"), DbColumn("guest name", Types.VARCHAR, "VARCHAR", "java.lang.String"),
           DbColumn("paid", Types.BOOLEAN, "BOOLEAN", "java.lang.Boolean")),
    listOf(arrayOf(1L, "Kim, \"Sang\"", true), arrayOf(2L, null, false), arrayOf(3L, "김\n상현", null)),
    truncated = false, elapsedMs = 3)

  fun testCsvAndTsv() {
    assertEquals("id,guest name,paid\r\n1,\"Kim, \"\"Sang\"\"\",true\r\n2,,false\r\n3,\"김\n상현\",\r\n",
                 DbExportFormat.CSV.render(rows, null, Dbms.MYSQL))
    assertTrue(DbExportFormat.TSV.render(rows, null, Dbms.MYSQL).startsWith("id\tguest name\tpaid\r\n1\t\"Kim, \"\"Sang\"\"\"\ttrue\r\n"))
  }

  fun testJsonKeepsTypesAndUnicode() {
    assertEquals("""
      [
        {"id": 1, "guest name": "Kim, \"Sang\"", "paid": true},
        {"id": 2, "guest name": null, "paid": false},
        {"id": 3, "guest name": "김\n상현", "paid": null}
      ]
    """.trimIndent(), DbExportFormat.JSON.render(rows, null, Dbms.MYSQL))
  }

  fun testInsertsQuoteForTheDatabase() {
    val table = DbExportFormat.tableSql(Dbms.MYSQL, SingleTableSelect.parse(rows.sql))
    val mysql = DbExportFormat.INSERT.render(rows, table, Dbms.MYSQL).lines()
    assertEquals("INSERT INTO shop.reservation (id, `guest name`, paid) VALUES (1, 'Kim, \"Sang\"', TRUE);", mysql[0])
    assertEquals("INSERT INTO shop.reservation (id, `guest name`, paid) VALUES (2, NULL, FALSE);", mysql[1])
    val pg = DbExportFormat.INSERT.render(rows, null, Dbms.POSTGRESQL).lines()
    assertTrue(pg[0], pg[0].startsWith("INSERT INTO table_name (id, \"guest name\", paid)"))
  }

  fun testInsertMultirowAndUpdate() {
    assertEquals("INSERT INTO t (id, `guest name`, paid)\nVALUES (1, 'Kim, \"Sang\"', TRUE),\n       (2, NULL, FALSE),\n       (3, '김\n상현', NULL);\n",
                 DbExportFormat.INSERT_MULTIROW.render(rows, "t", Dbms.MYSQL))
    assertEquals("", DbExportFormat.INSERT_MULTIROW.render(DbOutcome.Rows("", rows.columns, emptyList(), false, 0), "t", Dbms.MYSQL))
    val byKey = DbExportFormat.UPDATE.render(rows, "t", Dbms.MYSQL, keys = listOf(0)).lines()
    assertEquals("UPDATE t SET `guest name` = 'Kim, \"Sang\"', paid = TRUE WHERE id = 1;", byKey[0])
    assertEquals("UPDATE t SET `guest name` = NULL, paid = FALSE WHERE id = 2;", byKey[1])
    // no key: the row is found by every column, NULL ones with IS NULL
    val noKey = DbExportFormat.UPDATE.render(rows, null, Dbms.POSTGRESQL).lines()
    assertEquals("UPDATE table_name SET id = 2, \"guest name\" = NULL, paid = FALSE WHERE id = 2 AND \"guest name\" IS NULL AND paid = FALSE;", noKey[1])
  }

  fun testOneRowXmlAndPretty() {
    assertEquals("1, 'Kim, \"Sang\"', TRUE, 2, NULL, FALSE, 3, '김\n상현', NULL", DbExportFormat.ONE_ROW.render(rows, null, Dbms.MYSQL))
    val ids = DbOutcome.Rows("", rows.columns.take(1), listOf(arrayOf(1L), arrayOf(2L)), false, 0)
    assertEquals("1, 2", DbExportFormat.ONE_ROW.render(ids, null, Dbms.MYSQL))
    assertEquals("""
      <?xml version="1.0" encoding="UTF-8"?>
      <data>
        <row>
          <value column="id">2</value>
          <value column="guest name" null="true"/>
          <value column="paid">false</value>
        </row>
      </data>

    """.trimIndent(), DbExportFormat.XML.render(DbOutcome.Rows("", rows.columns, listOf(rows.rows[1]), false, 0), null, Dbms.MYSQL))
    assertEquals("""
      +----+-------------+-------+
      | id | guest name  | paid  |
      +----+-------------+-------+
      |  1 | Kim, "Sang" | true  |
      |  2 | NULL        | false |
      |  3 | 김 상현        | NULL  |
      +----+-------------+-------+

    """.trimIndent(), DbExportFormat.PRETTY.render(rows, null, Dbms.MYSQL))
  }

  /** Excel: a workbook whose one sheet has the header and the rows; numbers and booleans typed, NULL an empty cell. */
  fun testExcelWorkbook() {
    val file = java.nio.file.Files.createTempFile("export", ".xlsx")
    DbXlsx.file(file, DbExportFormat.XLSX) { it.append(DbExportFormat.XLSX.render(rows, null, Dbms.MYSQL)) }
    val parts = java.util.zip.ZipFile(file.toFile()).use { zip -> zip.entries().toList().associate { it.name to zip.getInputStream(it).readBytes().decodeToString() } }
    assertEquals(setOf("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/_rels/workbook.xml.rels", "xl/worksheets/sheet1.xml"), parts.keys)
    val sheet = parts.getValue("xl/worksheets/sheet1.xml")
    assertTrue(sheet, sheet.endsWith("</sheetData></worksheet>"))
    assertTrue(sheet, sheet.contains("""<row r="1"><c r="A1" t="inlineStr"><is><t xml:space="preserve">id</t></is></c>"""))
    assertTrue(sheet, sheet.contains("""<row r="2"><c r="A2"><v>1</v></c><c r="B2" t="inlineStr"><is><t xml:space="preserve">Kim, "Sang"</t></is></c><c r="C2" t="b"><v>1</v></c></row>"""))
    assertTrue(sheet, sheet.contains("""<row r="3"><c r="A3"><v>2</v></c><c r="C3" t="b"><v>0</v></c></row>"""))
    // well formed
    javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(sheet.byteInputStream())
    assertEquals(listOf("A", "Z", "AA", "AZ", "BA"), listOf(0, 25, 26, 51, 52).map(DbXlsx::column))
    assertFalse(DbExportFormat.XLSX.text)
  }

  fun testColumnStatistics() {
    val expected = listOf("qty", "rows      5", "NULL      1 (20.0%)", "distinct  2", "sum       6", "avg       1.5", "min       1", "max       2", "",
                          "most frequent", "  1     2 (40.0%)", "  2     2 (40.0%)", "  NULL  1 (20.0%)", "").joinToString("\n")
    assertEquals(expected, DbColumnStatistics.text("qty", listOf(1, 2, null, 1, 2)))
    val names = DbColumnStatistics.text("name", listOf("b", "a", "b"))
    assertTrue(names, names.contains("min       a\nmax       b\n"))
    assertTrue(names, names.contains("  b  2 (66.7%)"))
  }

  fun testMarkdown() {
    assertEquals("| id | guest name | paid |\n| --- | --- | --- |\n| 1 | Kim, \"Sang\" | true |\n| 2 | NULL | false |\n| 3 | 김 상현 | NULL |\n",
                 DbExportFormat.MARKDOWN.render(rows, null, Dbms.MYSQL))
  }

  fun testHistoryKeepsTheNewestOncePerDataSource() {
    val history = DbQueryHistory.getInstance(project)
    history.clear()
    val ds = DbDataSource().apply { id = "ds1"; name = "local" }
    history.record(ds, DbOutcome.Updated("UPDATE a SET b = 1", 2, 1))
    history.record(ds, rows)
    history.record(ds, DbOutcome.Failed("SELECT nope", "Unknown column 'nope'\nat line 1", 1))
    history.record(ds, DbOutcome.Updated("UPDATE a SET b = 1", 0, 1))
    assertEquals(listOf("UPDATE a SET b = 1", "SELECT nope", rows.sql), history.entries.map { it.sql })
    assertEquals(listOf("0 updated", "Unknown column 'nope'", "3 rows"), history.entries.map { it.summary })
    assertTrue(history.entries[1].failed)
    repeat(DbQueryHistory.LIMIT + 5) { history.record(ds, DbOutcome.Updated("DELETE FROM t WHERE id = $it", 1, 1)) }
    assertEquals(DbQueryHistory.LIMIT, history.entries.size)
    history.clear()
  }
}
