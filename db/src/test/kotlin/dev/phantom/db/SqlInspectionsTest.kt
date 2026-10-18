// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.db.core.*
import dev.phantom.db.core.exec.*
import kotlinx.coroutines.runBlocking

class SqlInspectionsTest : BasePlatformTestCase() {
  companion object {
    init {
      System.setProperty("idea.load.plugins.id", "dev.phantom.db,org.jetbrains.kotlin")
    }

    val SCHEMA_FREE: List<Class<out LocalInspectionTool>> = listOf(
      SqlWithoutWhereInspection::class.java, SqlJoinWithoutOnInspection::class.java, SqlUnusedCteInspection::class.java,
      SqlRedundantAliasInspection::class.java, SqlShadowingAliasInspection::class.java, SqlInsertValuesInspection::class.java,
      SqlUnionColumnCountInspection::class.java, SqlMultipleLimitInspection::class.java, SqlRedundantOrderingDirectionInspection::class.java,
      SqlCaseVsCoalesceInspection::class.java, SqlRedundantElseNullInspection::class.java, SqlShouldBeInGroupByInspection::class.java,
      SqlDerivedTableAliasInspection::class.java, SqlMissingColumnAliasesInspection::class.java,
      SqlNullComparisonInspection::class.java, SqlConstantConditionInspection::class.java, SqlAggregateMisuseInspection::class.java,
      SqlRedundantCoalesceInspection::class.java, SqlRedundantLimitInspection::class.java, SqlUnusedSubqueryItemInspection::class.java,
      SqlDuplicateColumnInspection::class.java, SqlOrderByPositionInspection::class.java, SqlAddNotNullColumnInspection::class.java,
      SqlDeprecatedTypeInspection::class.java, SqlAutoIncrementDuplicateInspection::class.java, SqlFunctionSpaceInspection::class.java,
    )
    val SCHEMA: List<Class<out LocalInspectionTool>> = listOf(
      SqlAmbiguousColumnInspection::class.java, SqlInsertNullIntoNotNullInspection::class.java,
      SqlStringLengthExceededInspection::class.java, SqlTypeMismatchInspection::class.java,
      SqlUsingColumnsInspection::class.java, SqlNotNullColumnTestInspection::class.java,
      SqlInsertIntoGeneratedColumnInspection::class.java, SqlMisleadingReferenceInspection::class.java,
    )
  }

  private val dataSources = ArrayList<DbDataSource>()

  override fun tearDown() {
    try {
      for (ds in dataSources) {
        DbConnectionService.getInstance(project).disconnect(ds.id)
        DbDataSourceService.getInstance(project).remove(ds.id)
      }
    }
    catch (e: Throwable) {
      addSuppressedException(e)
    }
    finally {
      super.tearDown()
    }
  }

  private fun applyFix(name: String) {
    val fix = myFixture.getAllQuickFixes().filter { it.text == name }
    assertEquals(myFixture.getAllQuickFixes().map { it.text }.toString(), 1, fix.size)
    myFixture.launchAction(fix.single())
  }

  /** Messages of the Phantom SQL inspections on [text], in order. */
  private fun findings(text: String, dataSource: DbDataSource? = null, name: String = "q.sql"): List<String> {
    myFixture.enableInspections(*(SCHEMA_FREE + SCHEMA).toTypedArray())
    val file = myFixture.configureByText(name, text)
    if (dataSource != null) DbConsoles.attach(project, file.virtualFile, dataSource.id)
    return myFixture.doHighlighting(HighlightSeverity.INFORMATION)
      .filter { it.inspectionToolId?.startsWith("PhantomSql") == true }
      .sortedBy { it.startOffset }
      .map { "${it.inspectionToolId!!.removePrefix("PhantomSql")}: ${it.text}" }
  }

  fun testWithoutWhereAndJoinWithoutOn() {
    assertEquals(listOf("WithoutWhere: UPDATE", "WithoutWhere: DELETE"), findings("UPDATE t SET a = 1;\nDELETE FROM t;"))
    assertEmpty(findings("UPDATE t SET a = 1 WHERE id = 2; DELETE FROM t WHERE id = 3; INSERT INTO t (a) VALUES (1) ON DUPLICATE KEY UPDATE a = 2;"))
    assertEquals(listOf("WithoutWhere: DELETE"), findings("WITH old AS (SELECT id FROM t) DELETE FROM old;"))

    assertEquals(listOf("JoinWithoutOn: JOIN", "JoinWithoutOn: JOIN"),
                 findings("SELECT * FROM a JOIN b WHERE a.x = 1; SELECT * FROM a LEFT JOIN b, c WHERE c.y = 1;"))
    assertEmpty(findings("""
      SELECT * FROM a JOIN b ON b.a_id = a.id JOIN c USING (id) CROSS JOIN d NATURAL JOIN e
        LEFT JOIN (f JOIN g ON g.f = f.id) ON f.a = a.id JOIN LATERAL (SELECT 1 x) l ON TRUE;
    """.trimIndent()))
  }

  fun testCtesAliasesAndShadowing() {
    assertEquals(listOf("UnusedCte: unused"),
                 findings("WITH used AS (SELECT 1 a), unused AS (SELECT 2 b) SELECT * FROM used u JOIN used v ON v.a = u.a;"))
    assertEmpty(findings("WITH RECURSIVE n AS (SELECT 1 i UNION ALL SELECT i + 1 FROM n WHERE i < 5) SELECT i FROM n;"))

    assertEquals(listOf("RedundantAlias: users"), findings("SELECT users.id FROM users users;"))
    assertEquals(listOf("RedundantAlias: AS users"), findings("SELECT * FROM users AS users;"))

    assertEquals(listOf("ShadowingAlias: r", "ShadowingAlias: r"),
                 findings("SELECT * FROM room r JOIN rate r ON 1 = 1 WHERE EXISTS (SELECT 1 FROM rate r WHERE r.id = 1);")
                   .filter { it.startsWith("Shadowing") })
    // a derived table may reuse its inner alias as its own name, and CTE bodies are not nested in the main query
    assertEmpty(findings("SELECT x.id FROM (SELECT x.id FROM t x) x; WITH c AS (SELECT u.id FROM users u) SELECT u.id FROM c JOIN users u ON u.id = c.id;"))
  }

  fun testInsertValuesUnionAndLimit() {
    assertEquals(listOf("InsertValues: (1, 2)", "InsertValues: b", "InsertValues: (1)"),
                 findings("INSERT INTO t (a, b, c) VALUES (1, 2), (1, 2, 3); INSERT INTO t (a, b, b) VALUES (1, 2, 3); INSERT INTO t (a, b) VALUES (1);"))
    assertEquals(listOf("InsertValues: SELECT"), findings("INSERT INTO t (a, b) SELECT x FROM s;"))
    assertEmpty(findings("INSERT INTO t (a, b) VALUES (f(1, 2), ?), (3, (SELECT max(x) FROM s)); INSERT INTO t (a, b) SELECT * FROM s; INSERT INTO t VALUES (1, 2, 3);"))

    assertEquals(listOf("UnionColumnCount: SELECT"), findings("SELECT a, b FROM t UNION ALL SELECT a FROM s;"))
    assertEmpty(findings("SELECT a, b FROM t UNION SELECT c, d FROM s UNION SELECT * FROM u; (SELECT a FROM t LIMIT 1) UNION (SELECT b FROM s LIMIT 1);"))

    assertEquals(listOf("MultipleLimit: FETCH"), findings("SELECT a FROM t LIMIT 5 FETCH FIRST 3 ROWS ONLY;"))
    assertEmpty(findings("SELECT a FROM t WHERE a IN (SELECT b FROM s LIMIT 1) LIMIT 10 OFFSET 5;"))
  }

  fun testRedundantCodeAndCase() {
    assertEquals(listOf("RedundantOrderingDirection: ASC", "RedundantOrderingDirection: ASC"),
                 findings("SELECT a, row_number() OVER (ORDER BY b ASC) FROM t ORDER BY a ASC, b DESC;"))
    assertEquals(listOf("RedundantElseNull: ELSE NULL"), findings("SELECT CASE WHEN a > 1 THEN 'x' ELSE NULL END FROM t;"))
    assertEquals(listOf("CaseVsCoalesce: CASE WHEN t.a IS NULL THEN 0 ELSE t.a END", "CaseVsCoalesce: CASE WHEN b IS NOT NULL THEN b ELSE 'none' END"),
                 findings("SELECT CASE WHEN t.a IS NULL THEN 0 ELSE t.a END, CASE WHEN b IS NOT NULL THEN b ELSE 'none' END FROM t;"))
    assertEmpty(findings("""
      SELECT CASE WHEN count(r) > 0 THEN true ELSE false END,
             CASE a WHEN 1 THEN 'one' ELSE NULL END IS NULL,
             CASE WHEN a IS NULL THEN 0 ELSE b END,
             count(DISTINCT CASE WHEN o.released_at IS NULL THEN o.id END)
      FROM t GROUP BY a;
    """.trimIndent()).filter { !it.startsWith("RedundantElseNull") })
    // the query checks leave DDL alone: the user's generated column keeps its ELSE NULL
    assertEmpty(findings("ALTER TABLE t ADD COLUMN m TINYINT GENERATED ALWAYS AS (CASE WHEN r IS NULL THEN 1 ELSE NULL END) STORED;"))
  }

  fun testGroupBy() {
    assertEquals(listOf("ShouldBeInGroupBy: name"), findings("SELECT name, count(*) FROM t;"))
    assertEmpty(findings("""
      SELECT name, count(*) FROM t GROUP BY name;
      SELECT count(*), max(a), sum(CASE WHEN b > 0 THEN b END) FROM t;
      SELECT a, count(*) OVER (PARTITION BY b) FROM t;
      SELECT any_value(name), count(*) FROM t;
      SELECT (SELECT max(x) FROM s WHERE s.t = t.id), id FROM t;
    """.trimIndent()))
  }

  fun testDerivedTableAliasOnlyForMysql() {
    assertEmpty(findings("SELECT * FROM (SELECT 1 a);"))
    val mysql = DbDataSource().apply { name = "my"; dbms = Dbms.MYSQL; url = "jdbc:mysql://127.0.0.1:1/none"; user = "x" }
    DbDataSourceService.getInstance(project).save(mysql, CharArray(0))
    dataSources.add(mysql)
    assertEquals(listOf("DerivedTableAlias: (SELECT 1 a)"),
                 findings("SELECT * FROM (SELECT 1 a) JOIN (SELECT 2 b) AS x ON TRUE WHERE a IN (SELECT 1);", mysql).filter { it.startsWith("Derived") })
    applyFix(PhantomDbBundle.message("sql.fix.add.alias"))
    myFixture.checkResult("SELECT * FROM (SELECT 1 a) AS derived JOIN (SELECT 2 b) AS x ON TRUE WHERE a IN (SELECT 1);")
  }

  fun testMissingColumnAliasesIsOffByDefaultButWorks() {
    assertEquals(listOf("MissingColumnAliases: count(*)"),
                 findings("SELECT d.a FROM (SELECT a, count(*), b AS c FROM t GROUP BY a, b) d; WITH x (n) AS (SELECT count(*) FROM t) SELECT n FROM x;")
                   .filter { it.startsWith("Missing") })
  }

  fun testNullComparisonAndConstantConditions() {
    assertEquals(listOf("NullComparison: = NULL", "NullComparison: <> NULL", "NullComparison: != NULL"),
                 findings("SELECT * FROM t WHERE a = NULL OR b <> NULL; SELECT CASE WHEN c != NULL THEN 1 END FROM t;"))
    assertEmpty(findings("UPDATE t SET a = NULL WHERE b IS NULL; INSERT INTO t (a) VALUES (1) ON DUPLICATE KEY UPDATE a = NULL; SELECT * FROM t WHERE a <=> NULL;"))
    findings("SELECT * FROM t WHERE a <> NULL;")
    applyFix(PhantomDbBundle.message("sql.fix.replace.with", "IS NOT NULL"))
    myFixture.checkResult("SELECT * FROM t WHERE a IS NOT NULL;")

    assertEquals(listOf("ConstantCondition: 1 = 0", "ConstantCondition: 'x' = 'x'", "ConstantCondition: 2 > 1"),
                 findings("SELECT * FROM t WHERE 1 = 0 OR 'x' = 'x'; SELECT * FROM a JOIN b ON 2 > 1;"))
    assertEmpty(findings("SELECT * FROM t WHERE a = 1 AND 'a' = 'A' AND a + 1 = 2 AND 1 = a AND 2 = 1 + a; SELECT 1 = 0 FROM t;"))
  }

  fun testAggregatesCoalesceAndSubqueryLimits() {
    assertEquals(listOf("AggregateMisuse: count", "AggregateMisuse: max", "AggregateMisuse: sum"),
                 findings("SELECT a FROM t WHERE count(*) > 1 GROUP BY max(b); SELECT max(sum(a)) FROM t GROUP BY b;").filter { it.startsWith("Aggregate") })
    assertEmpty(findings("""
      SELECT a, count(*) FROM t GROUP BY a HAVING count(*) > 1 ORDER BY count(*);
      SELECT sum(count(*)) OVER () FROM t GROUP BY a;
      SELECT * FROM t WHERE a > (SELECT max(b) FROM s);
      SELECT t.max FROM t WHERE row_number() OVER (ORDER BY a) > 1;
    """.trimIndent()).filter { it.startsWith("Aggregate") })

    assertEquals(listOf("RedundantCoalesce: , b", "RedundantCoalesce: , NULL", "RedundantCoalesce: COALESCE(a)"),
                 findings("SELECT coalesce(a, 0, b), COALESCE(a, b, NULL), COALESCE(a) FROM t;"))
    assertEmpty(findings("SELECT COALESCE(a, b, 0), COALESCE(NULL, a) FROM t;"))
    findings("SELECT COALESCE(a, 'none', b, c) FROM t;")
    applyFix(PhantomDbBundle.message("sql.fix.remove.arguments"))
    myFixture.checkResult("SELECT COALESCE(a, 'none') FROM t;")

    assertEquals(listOf("RedundantLimit: ORDER BY", "RedundantLimit: LIMIT", "RedundantLimit: ORDER BY"),
                 findings("SELECT * FROM t WHERE EXISTS (SELECT 1 FROM s ORDER BY x LIMIT 1) AND a IN (SELECT b FROM s ORDER BY b);"))
    assertEmpty(findings("SELECT * FROM t WHERE NOT EXISTS (SELECT 1 FROM s LIMIT 0) AND a IN (SELECT b FROM s ORDER BY b LIMIT 3); SELECT * FROM (SELECT a FROM t ORDER BY a) d;")
                  .filter { it.startsWith("RedundantLimit") })
  }

  fun testUnusedSubqueryItemsDuplicatesAndPositions() {
    assertEquals(listOf("UnusedSubqueryItem: b", "UnusedSubqueryItem: count(*) n"),
                 findings("SELECT d.a FROM (SELECT a, b FROM t) d; WITH c AS (SELECT a, count(*) n FROM t GROUP BY a) SELECT a FROM c;"))
    assertEmpty(findings("""
      SELECT * FROM (SELECT a, b FROM t) d;
      SELECT d.* FROM (SELECT a, b FROM t) d;
      SELECT count(*) FROM (SELECT a, b FROM t) d;
      SELECT d.a FROM (SELECT DISTINCT a, b FROM t) d;
      SELECT x.a FROM (SELECT a, count(*) c FROM t GROUP BY a HAVING c > 1) x;
      WITH c AS (SELECT a, b FROM t) SELECT c.a FROM c JOIN s ON s.b = c.b;
      WITH RECURSIVE n (i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 3) SELECT i FROM n;
    """.trimIndent()))

    assertEquals(listOf("DuplicateColumn: a", "DuplicateColumn: a", "DuplicateColumn: b AS a", "DuplicateColumn: id"),
                 findings("""
                   UPDATE t SET a = 1, b = 2, a = 3 WHERE id = 1;
                   INSERT INTO t (a) VALUES (1) ON DUPLICATE KEY UPDATE a = 2, a = 3;
                   SELECT d.a FROM (SELECT a, b AS a FROM t) d;
                   CREATE TABLE x (id BIGINT, name TEXT, id INT, PRIMARY KEY (id));
                 """.trimIndent()).filter { it.startsWith("Duplicate") })
    assertEmpty(findings("UPDATE t JOIN s ON s.id = t.id SET t.a = 1, s.a = 2; SELECT a, a FROM t; CREATE TABLE y (id INT, KEY k (id), UNIQUE (id));")
                  .filter { it.startsWith("Duplicate") })

    assertEquals(listOf("OrderByPosition: 3", "OrderByPosition: 0", "OrderByPosition: 2"),
                 findings("SELECT a, b FROM t ORDER BY 3; SELECT a FROM t GROUP BY 0; SELECT a FROM t UNION SELECT b FROM s ORDER BY 2 DESC;"))
    assertEmpty(findings("SELECT a, b FROM t GROUP BY 1, 2 ORDER BY 2 DESC, 1 LIMIT 5; SELECT * FROM t ORDER BY 7; SELECT a, row_number() OVER (ORDER BY 5) FROM t;"))
  }

  fun testDdlChecksFollowTheDialect() {
    val ddl = "ALTER TABLE t ADD COLUMN c INT NOT NULL, ADD d INT NOT NULL DEFAULT 0, ADD CONSTRAINT u UNIQUE (c), ADD e SERIAL NOT NULL;\n" +
              "CREATE TABLE z (id INT(11) NOT NULL, flag TINYINT(1), n BIGINT(20) UNSIGNED ZEROFILL, f FLOAT(7,2), name VARCHAR(20));"
    // with no data source the dialect is unknown: nothing
    assertEmpty(findings(ddl))
    val pg = DbDataSource().apply { name = "pg"; dbms = Dbms.POSTGRESQL; url = "jdbc:postgresql://127.0.0.1:1/none"; user = "x" }
    val mysql = DbDataSource().apply { name = "my"; dbms = Dbms.MYSQL; url = "jdbc:mysql://127.0.0.1:1/none"; user = "x" }
    for (ds in listOf(pg, mysql)) {
      DbDataSourceService.getInstance(project).save(ds, CharArray(0))
      dataSources.add(ds)
    }
    assertEquals(listOf("AddNotNullColumn: ADD COLUMN c"), findings(ddl, pg))
    assertEquals(listOf("DeprecatedType: (11)", "DeprecatedType: (20)", "DeprecatedType: ZEROFILL", "DeprecatedType: (7,2)"), findings(ddl, mysql))
  }

  fun testAutoIncrementAndMysqlFunctionSpace() {
    assertEquals(listOf("AutoIncrementDuplicate: AUTO_INCREMENT"),
                 findings("CREATE TABLE t (id BIGINT AUTO_INCREMENT PRIMARY KEY, seq INT AUTO_INCREMENT, n INT) AUTO_INCREMENT = 10;"))
    assertEmpty(findings("CREATE TABLE t (id BIGINT AUTO_INCREMENT PRIMARY KEY, n INT) AUTO_INCREMENT = 10;"))

    val query = "SELECT COUNT (*), MAX(id), NOW (), my_func (1), t.sum (2) FROM t;"
    // the space only matters to MySQL
    assertEmpty(findings(query))
    val mysql = DbDataSource().apply { name = "my"; dbms = Dbms.MYSQL; url = "jdbc:mysql://127.0.0.1:1/none"; user = "x" }
    DbDataSourceService.getInstance(project).save(mysql, CharArray(0))
    dataSources.add(mysql)
    assertEquals(listOf("FunctionSpace: COUNT (", "FunctionSpace: NOW ("), findings(query, mysql))
    findings("SELECT COUNT (*) FROM t;", mysql)
    applyFix(PhantomDbBundle.message("sql.fix.remove.space"))
    myFixture.checkResult("SELECT COUNT(*) FROM t;")
  }

  fun testGeneratedColumnsAndMisleadingReferencesOnH2() {
    val ds = DbDataSource().apply {
      name = "shop"
      dbms = Dbms.H2
      url = "jdbc:h2:mem:sql_gen_${System.nanoTime()};DB_CLOSE_DELAY=-1"
      user = "sa"
    }
    DbDataSourceService.getInstance(project).save(ds, CharArray(0))
    dataSources.add(ds)
    runBlocking {
      DbExecutor.execute(project, ds, listOf(
        "CREATE TABLE item (id BIGINT AUTO_INCREMENT PRIMARY KEY, price INT, total INT GENERATED ALWAYS AS (price * 2))",
        "CREATE TABLE orders (order_no BIGINT PRIMARY KEY, item_id BIGINT)",
      ))
      SqlSchema.getInstance(project).load(ds)!!.let { snapshot ->
        for (name in listOf("item", "orders")) {
          val table = snapshot.find(name, null).single()
          DbMetadataCache.getInstance(project).columns(ds, table)
          DbObjectCache.getInstance(project).columnDetails(ds, table)
        }
      }
    }
    assertEquals(listOf("InsertIntoGeneratedColumn: total"), findings("INSERT INTO item (price, total) VALUES (1, 2);", ds))
    assertEmpty(findings("INSERT INTO item (id, price) VALUES (1, 2); INSERT INTO item (price, total) VALUES (1, DEFAULT);", ds))

    assertEquals(listOf("MisleadingReference: id"), findings("SELECT * FROM item i WHERE i.id IN (SELECT id FROM orders);", ds))
    applyFix(PhantomDbBundle.message("sql.fix.qualify", "i"))
    myFixture.checkResult("SELECT * FROM item i WHERE i.id IN (SELECT i.id FROM orders);")
    assertEmpty(findings("SELECT * FROM item i WHERE i.id IN (SELECT item_id FROM orders); " +
                         "SELECT * FROM item WHERE EXISTS (SELECT 1 FROM orders o WHERE o.item_id = item.id); " +
                         "SELECT * FROM item WHERE id IN (SELECT x FROM (SELECT 1 x) d);", ds))
  }

  fun testQuickFixes() {
    findings("SELECT a FROM t ORDER BY a ASC;")
    applyFix(PhantomDbBundle.message("sql.fix.remove.asc"))
    myFixture.checkResult("SELECT a FROM t ORDER BY a;")

    findings("SELECT CASE WHEN a IS NULL THEN 0 ELSE a END FROM t;")
    applyFix(PhantomDbBundle.message("sql.fix.replace.with", "COALESCE(a, 0)"))
    myFixture.checkResult("SELECT COALESCE(a, 0) FROM t;")

    findings("SELECT CASE WHEN a > 1 THEN 1 ELSE NULL END FROM t;")
    applyFix(PhantomDbBundle.message("sql.fix.remove.else.null"))
    myFixture.checkResult("SELECT CASE WHEN a > 1 THEN 1 END FROM t;")

    findings("SELECT users.id FROM users AS users;")
    applyFix(PhantomDbBundle.message("sql.fix.remove.alias"))
    myFixture.checkResult("SELECT users.id FROM users;")
  }

  /** The SQL of the user's project (Flyway migrations, scripts, seed data, JdbcTemplate strings): nothing to report. */
  fun testUserProjectSqlHasNoFindings() {
    val dir = javaClass.getResource("/sql-samples")!!.toURI().let(java.nio.file.Path::of)
    val files = java.nio.file.Files.list(dir).use { s -> s.filter { it.toString().endsWith(".sql") }.sorted().toList() }
    assertTrue(files.size > 20)
    val all = files.flatMap { path -> findings(java.nio.file.Files.readString(path), name = path.fileName.toString()).map { "${path.fileName}: $it" } }
    assertEmpty(all)
  }

  fun testSchemaInspectionsOnH2() {
    val ds = DbDataSource().apply {
      name = "hotel"
      dbms = Dbms.H2
      url = "jdbc:h2:mem:sql_insp_${System.nanoTime()};DB_CLOSE_DELAY=-1"
      user = "sa"
    }
    DbDataSourceService.getInstance(project).save(ds, CharArray(0))
    dataSources.add(ds)
    runBlocking {
      DbExecutor.execute(project, ds, listOf(
        "CREATE TABLE guest (id BIGINT PRIMARY KEY, name VARCHAR(40) NOT NULL, email VARCHAR(80))",
        "CREATE TABLE reservation (id BIGINT AUTO_INCREMENT PRIMARY KEY, guest_id BIGINT NOT NULL, room_no INT, " +
        "status VARCHAR(10) DEFAULT 'NEW' NOT NULL, note VARCHAR(5))",
      ))
      SqlSchema.getInstance(project).load(ds)!!.let { snapshot ->
        for (name in listOf("guest", "reservation")) {
          val table = snapshot.find(name, null).single()
          DbMetadataCache.getInstance(project).columns(ds, table)
          DbObjectCache.getInstance(project).columnDetails(ds, table)
        }
      }
    }

    assertEquals(listOf("AmbiguousColumn: id"), findings("SELECT id, name, room_no FROM guest g JOIN reservation r ON r.guest_id = g.id;", ds))
    applyFix(PhantomDbBundle.message("sql.fix.qualify", "g"))
    myFixture.checkResult("SELECT g.id, name, room_no FROM guest g JOIN reservation r ON r.guest_id = g.id;")
    assertEmpty(findings("SELECT g.id, name FROM guest g JOIN reservation r ON r.guest_id = g.id ORDER BY name;", ds))
    assertEmpty(findings("SELECT id FROM guest WHERE id IN (SELECT guest_id FROM reservation);", ds))
    assertEmpty(findings("INSERT INTO reservation (guest_id) SELECT id FROM guest;", ds))

    assertEquals(listOf("InsertNullIntoNotNull: reservation", "InsertNullIntoNotNull: NULL"),
                 findings("INSERT INTO reservation (room_no) VALUES (1); INSERT INTO reservation (guest_id, status) VALUES (1, NULL);", ds))
    assertEmpty(findings("INSERT INTO reservation (guest_id, room_no) VALUES (1, NULL); INSERT INTO reservation (id, guest_id) VALUES (NULL, 2);", ds))

    assertEquals(listOf("StringLengthExceeded: 'toolong'", "StringLengthExceeded: 'abcdef'"),
                 findings("INSERT INTO reservation (guest_id, note) VALUES (1, 'toolong'); UPDATE reservation SET note = 'abcdef' WHERE id = 1;", ds))
    assertEmpty(findings("INSERT INTO reservation (guest_id, note) VALUES (1, 'it''s'); UPDATE reservation SET note = 'ab' || note WHERE id = 1;", ds))

    assertEquals(listOf("TypeMismatch: 'abc'"), findings("SELECT * FROM reservation WHERE room_no = 'abc' OR room_no = '12' OR status = 'abc';", ds))

    assertEquals(listOf("UsingColumns: email", "UsingColumns: status"),
                 findings("SELECT * FROM guest g JOIN reservation r USING (email); SELECT * FROM reservation r JOIN guest g USING (id, status);", ds))
    assertEmpty(findings("SELECT * FROM guest g JOIN reservation r USING (id); SELECT * FROM (SELECT 1 status) d JOIN reservation r USING (status);", ds))

    assertEquals(listOf("NotNullColumnTest: name IS NULL", "NotNullColumnTest: r.guest_id IS NOT NULL"),
                 findings("SELECT * FROM guest WHERE name IS NULL OR email IS NULL; SELECT * FROM reservation r WHERE r.guest_id IS NOT NULL;", ds))
    // an outer join puts NULLs into NOT NULL columns
    assertEmpty(findings("SELECT g.id FROM guest g LEFT JOIN reservation r ON r.guest_id = g.id WHERE r.guest_id IS NULL;", ds))

    // without a data source the schema checks say nothing
    assertEmpty(findings("SELECT id FROM guest g JOIN reservation r ON r.guest_id = g.id;", name = "detached.sql"))
  }
}
