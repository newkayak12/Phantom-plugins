// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
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
