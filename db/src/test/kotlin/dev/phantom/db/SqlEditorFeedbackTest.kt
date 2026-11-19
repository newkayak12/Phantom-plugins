// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.db.core.*
import dev.phantom.db.core.exec.*
import kotlinx.coroutines.runBlocking

/** Errors while typing and after a run, completion by context, and colors by what a name is. */
class SqlEditorFeedbackTest : BasePlatformTestCase() {
  companion object {
    init {
      System.setProperty("idea.load.plugins.id", "dev.phantom.db,org.jetbrains.kotlin")
    }
  }

  private fun messages(sql: String) = SqlSyntaxCheck.check(sql).map { sql.substring(it.start, it.end) to it.message }

  private fun word(sql: String) = messages(sql).map { it.first }

  private fun applied(sql: String): String {
    val fix = SqlSyntaxCheck.check(sql).single().fix!!
    return sql.replaceRange(fix.start, fix.end, fix.text)
  }

  fun testValidStatementsOfEveryDialectAreLeftAlone() {
    listOf(
      "SELECT a, b FROM t WHERE a = 1 GROUP BY a HAVING count(*) > 1 ORDER BY a LIMIT 1",
      "SELECT EXTRACT(YEAR FROM d), TRIM(BOTH ' ' FROM x), SUBSTRING(a FROM 1 FOR 2) FROM t WHERE a IS DISTINCT FROM b ORDER BY 1",
      "SELECT rank() OVER (PARTITION BY a ORDER BY b), percentile_cont(0.5) WITHIN GROUP (ORDER BY c), count(*) FILTER (WHERE d > 1) FROM t",
      "SELECT 1 UNION ALL SELECT 2 ORDER BY 1",
      "SELECT a form FROM t",
      "SELECT a FROM t form WHERE a = 1",
      "WITH r AS (SELECT 1) SELECT * FROM r",
      "INSERT INTO t (a) SELECT a FROM s ON DUPLICATE KEY UPDATE a = 1",
      "INSERT INTO t (a) VALUES (1), (2)",
      "CREATE TABLE t (id INT, name VARCHAR(10))",
      "GRANT SELECT ON t TO GROUP staff",
      "SELECT 'it''s', \"q\", `x y`, \$\$ body \$\$, E'a\\'b' FROM t",
      "SELECT * FROM t WHERE d > now() - INTERVAL 1 MONTH",
      "SELECT a FROM t FOR UPDATE",
      "UPDATE t SET a = 1 WHERE b = 2",
      "DELETE FROM t WHERE a IN (SELECT a FROM s)",
      "VACUUM ANALYZE t",
      "STOP SLAVE",
      "lbl: LOOP",
      "SELECT",
      "SELECT a FROM",
    ).forEach { assertEquals(it, emptyList<Any>(), messages(it)) }
  }

  fun testParenthesesAndUnclosedText() {
    assertEquals(listOf(")"), word("SELECT a) FROM t"))
    assertEquals(listOf("("), word("SELECT count( FROM t").filter { it == "(" })
    assertEquals(listOf("'"), word("SELECT 'abc FROM t"))
    assertEquals(listOf("/"), word("SELECT 1 /* oops"))
    assertEquals(listOf("`"), word("SELECT `abc FROM t"))
  }

  fun testCommas() {
    assertEquals(",", word("SELECT a, FROM t").single())
    assertEquals("SELECT a FROM t", applied("SELECT a, FROM t"))
    assertEquals(",", word("SELECT , a FROM t").single())
    assertEquals(",", word("SELECT a,, b FROM t").single())
    assertEquals(",", word("INSERT INTO t VALUES (1, )").single())
    assertEquals("SELECT a, b FROM t", applied("SELECT a, b, FROM t"))
    assertEquals("SELECT a b, c FROM t", applied("SELECT a b c FROM t"))
  }

  fun testMisspelledKeywordsOfferTheWord() {
    assertEquals("SELECT 1", applied("SELEC 1"))
    assertEquals("SELECT a FROM t", applied("SELECT a FORM t"))
    assertEquals("SELECT a FROM t WHERE a = 1", applied("SELECT a FROM t WHRE a = 1"))
    assertEquals("SELECT a FROM t GROUP BY a", applied("SELECT a FROM t GRUOP BY a"))
    assertEquals("SELECT a FROM t ORDER BY a", applied("SELECT a FROM t ODER BY a"))
    assertEquals("SELECT a FROM t JOIN s ON 1 = 1", applied("SELECT a FROM t JION s ON 1 = 1"))
  }

  fun testMissingParts() {
    assertEquals("FROM", word("SELECT a FROM WHERE b = 1").single())
    assertEquals("WHERE", word("SELECT a FROM t WHERE ORDER BY a").single())
    assertEquals("SELECT a FROM t ORDER BY a", applied("SELECT a FROM t ORDER a"))
    assertEquals("SELECT 1; SELECT 2", applied("SELECT 1 SELECT 2"))
    assertEquals("SELECT a FROM t WHERE a = 1 AND b = 2", applied("SELECT a FROM t WHERE a = 1 b = 2"))
  }

  fun testClausesInTheWrongOrder() {
    assertEquals("WHERE", word("SELECT a FROM t ORDER BY a WHERE a = 1").single())
    assertEquals("GROUP", word("SELECT a FROM t HAVING a > 1 GROUP BY a").single())
    assertEquals("ORDER", word("SELECT a FROM t LIMIT 1 ORDER BY a").single())
  }

  fun testErrorsReachTheEditorWithAQuickFix() {
    myFixture.configureByText("a.sql", "SELECT a, FROM t;\nSELECT 1;")
    val errors = myFixture.doHighlighting(HighlightSeverity.ERROR)
    assertEquals(",", errors.single().text)
    myFixture.launchAction(myFixture.findSingleIntention("Remove the comma"))
    assertEquals("SELECT a FROM t;\nSELECT 1;", myFixture.editor.document.text)
  }

  fun testDatabaseErrorPositions() {
    assertEquals(7, DbErrorPosition.find("SELECT nope FROM t", "ERROR: column \"nope\" does not exist\n  Position: 8"))
    assertEquals(9, DbErrorPosition.find("SELECT * FORM t", "Syntax error in SQL statement \"SELECT * [*]FORM t\""))
    assertEquals("SELECT a\nFROM t WHRE x".indexOf("WHRE"), DbErrorPosition.find("SELECT a\nFROM t WHRE x", "You have an error in your SQL syntax; check the manual near 'WHRE x' at line 2"))
    assertEquals(7, DbErrorPosition.find("SELECT nope FROM t", "Unknown column 'nope' in 'field list'"))
    assertEquals(14, DbErrorPosition.find("SELECT * FROM nope", "Table 'db.nope' doesn't exist"))
    assertNull(DbErrorPosition.find("SELECT 1", "Connection refused"))
  }

  fun testAFailedRunMarksTheTokenInTheEditorUntilTheNextEdit() {
    val ds = DbDataSource().apply {
      name = "feedback"
      dbms = Dbms.H2
      url = "jdbc:h2:mem:feedback_${System.nanoTime()};DB_CLOSE_DELAY=-1"
      user = "sa"
    }
    DbDataSourceService.getInstance(project).save(ds, CharArray(0))
    try {
      val sql = "SELECT nope FROM dual"
      val failed = runBlocking { DbExecutor.execute(project, ds, listOf(sql)) }.single() as DbOutcome.Failed
      myFixture.configureByText("a.sql", "SELECT 1;\n$sql;")
      DbErrorMarkers.mark(myFixture.editor, failed.sql, failed.message)
      val range = DbErrorMarkers.markedRange(myFixture.editor)!!
      assertEquals("nope", myFixture.editor.document.text.substring(range.first, range.last + 1))
      myFixture.type("x")
      assertNull(DbErrorMarkers.markedRange(myFixture.editor))
    }
    finally {
      DbConnectionService.getInstance(project).disconnect(ds.id)
      DbDataSourceService.getInstance(project).remove(ds.id)
    }
  }

  private fun lookups(text: String): List<String> {
    myFixture.configureByText("a.sql", text)
    myFixture.completeBasic()
    return myFixture.lookupElementStrings.orEmpty()
  }

  fun testKeywordsFollowTheClause() {
    assertTrue(lookups("<caret>").containsAll(listOf("SELECT", "INSERT INTO", "DELETE FROM")))
    assertTrue(lookups("SELECT a FROM t WHERE a = 1 O<caret>").containsAll(listOf("OR", "ORDER BY")))
    assertTrue(lookups("SELECT a FROM t L<caret>").containsAll(listOf("LEFT JOIN", "LIMIT")))
    assertTrue(lookups("SELECT a FROM t ORDER BY a D<caret>").contains("DESC"))
    assertTrue(lookups("select a from t where a = 1 o<caret>").contains("order by"))
  }

  fun testFunctionsAreOfferedAndGetTheirParentheses() {
    lookups("SELECT coa<caret>")
    val typed = myFixture.editor.document.text
    assertTrue(typed, typed.startsWith("SELECT coalesce("))
    assertEquals("SELECT coalesce(".length, myFixture.editor.caretModel.offset)
    myFixture.configureByText("a.sql", "SELECT now<caret>")
    myFixture.completeBasic()
    myFixture.lookup?.let { myFixture.finishLookup('\n') }
    assertEquals("SELECT now()", myFixture.editor.document.text)
  }

  fun testColumnsOfSubqueriesAndCtes() {
    assertSameElements(lookups("SELECT d.<caret> FROM (SELECT id, name AS title FROM t) d"), "id", "title")
    assertTrue(lookups("WITH r AS (SELECT total, id FROM t) SELECT r.<caret> FROM r").containsAll(listOf("total", "id")))
    assertTrue(lookups("SELECT tot<caret> FROM (SELECT count(*) AS total FROM t) d").contains("total"))
  }

  fun testDataTypesInCreateTable() {
    assertTrue(lookups("CREATE TABLE t (id BIG<caret>)").contains("BIGINT"))
  }

  fun testCompletionOpensOnADotAndBeforeATable() {
    assertTrue(SqlTypedHandler.popsUp('.', "SELECT o", 8))
    assertTrue(SqlTypedHandler.popsUp(' ', "SELECT a FROM", 13))
    assertFalse(SqlTypedHandler.popsUp(' ', "SELECT a", 8))
    assertFalse(SqlTypedHandler.popsUp('.', "SELECT 1 ", 9))
  }

  private fun colored(sql: String): Map<String, Set<String>> {
    myFixture.configureByText("a.sql", sql)
    return myFixture.doHighlighting().filter { it.forcedTextAttributesKey != null }.groupBy({ it.forcedTextAttributesKey.externalName }, { it.text }).mapValues { it.value.toSet() }
  }

  fun testNamesAreColoredByWhatTheyAre() {
    val colors = colored("WITH r AS (SELECT 1 AS n) SELECT count(*), o.id, CAST(o.x AS INT) FROM s.orders o JOIN r ON r.n = o.id;")
    assertTrue(colors.toString(), "orders" in colors.getValue("PHANTOM_SQL_TABLE"))
    assertTrue(colors.toString(), "s" in colors.getValue("PHANTOM_SQL_SCHEMA"))
    assertTrue(colors.toString(), "id" in colors.getValue("PHANTOM_SQL_COLUMN"))
    assertTrue(colors.toString(), "o" in colors.getValue("PHANTOM_SQL_ALIAS"))
    assertTrue(colors.toString(), "r" in colors.getValue("PHANTOM_SQL_CTE"))
    assertTrue(colors.toString(), "count" in colors.getValue("PHANTOM_SQL_FUNCTION"))
    assertTrue(colors.toString(), "INT" in colors.getValue("PHANTOM_SQL_DATA_TYPE"))
  }

  fun testAKeywordFunctionIsAFunctionAndAKeywordTableIsNot() {
    val colors = colored("SELECT left(a, 1) FROM t LEFT JOIN u ON 1 = 1;")
    assertEquals(setOf("left"), colors.getValue("PHANTOM_SQL_FUNCTION"))
  }

  fun testColorPageDemoUsesOnlyKnownTags() {
    val page = SqlColorSettingsPage()
    val tags = Regex("<([a-z])>").findAll(page.demoText).map { it.groupValues[1] }.toSet()
    assertTrue(page.additionalHighlightingTagToDescriptorMap!!.keys.containsAll(tags))
    assertTrue(page.attributeDescriptors.size >= 20)
  }
}
