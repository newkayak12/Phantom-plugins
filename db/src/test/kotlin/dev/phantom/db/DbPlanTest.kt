// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.db.core.*
import dev.phantom.db.core.exec.*
import kotlinx.coroutines.runBlocking

/** EXPLAIN output read into a plan tree: PostgreSQL and MySQL JSON, MySQL's ANALYZE tree, H2's text. */
class DbPlanTest : BasePlatformTestCase() {
  companion object {
    init {
      System.setProperty("idea.load.plugins.id", "dev.phantom.db,org.jetbrains.kotlin")
    }

    private val POSTGRES = """
      [
        {
          "Plan": {
            "Node Type": "Hash Join", "Join Type": "Inner", "Startup Cost": 1.09, "Total Cost": 39.5, "Plan Rows": 10,
            "Actual Total Time": 0.9, "Actual Rows": 3, "Actual Loops": 1, "Hash Cond": "(o.user_id = u.id)",
            "Plans": [
              { "Node Type": "Seq Scan", "Relation Name": "orders", "Alias": "o", "Total Cost": 30.4, "Plan Rows": 2040,
                "Actual Total Time": 0.7, "Actual Rows": 2040, "Actual Loops": 1, "Filter": "(total > 10)" },
              { "Node Type": "Hash", "Total Cost": 1.05, "Plan Rows": 3, "Actual Total Time": 0.05, "Actual Rows": 3, "Actual Loops": 1,
                "Plans": [
                  { "Node Type": "Index Scan", "Index Name": "users_pkey", "Relation Name": "users", "Alias": "u", "Total Cost": 1.05,
                    "Plan Rows": 3, "Actual Total Time": 0.01, "Actual Rows": 1, "Actual Loops": 3, "Index Cond": "(id < 4)" }
                ]
              }
            ]
          },
          "Planning Time": 0.2,
          "Execution Time": 1.1
        }
      ]
    """.trimIndent()

    private val MYSQL = """
      {
        "query_block": {
          "select_id": 1,
          "cost_info": { "query_cost": "4.75" },
          "nested_loop": [
            { "table": { "table_name": "u", "access_type": "ALL", "rows_examined_per_scan": 5, "rows_produced_per_join": 5,
                         "cost_info": { "read_cost": "0.25", "eval_cost": "0.50", "prefix_cost": "0.75" }, "used_columns": ["id"] } },
            { "table": { "table_name": "o", "access_type": "ref", "key": "fk_user", "rows_examined_per_scan": 2, "rows_produced_per_join": 10,
                         "cost_info": { "read_cost": "3.00", "eval_cost": "1.00", "prefix_cost": "4.75" }, "attached_condition": "(o.total > 10)" } }
          ]
        }
      }
    """.trimIndent()

    private val MYSQL_ANALYZE = """
      -> Nested loop inner join  (cost=4.75 rows=10) (actual time=0.10..2.50 rows=10 loops=1)
          -> Table scan on u  (cost=0.75 rows=5) (actual time=0.05..0.20 rows=5 loops=1)
          -> Index lookup on o using fk_user (user_id=u.id)  (cost=0.80 rows=2) (actual time=0.10..0.40 rows=2 loops=5)
    """.trimIndent()
  }

  fun testPostgresJson() {
    val plan = DbPlan.parse(POSTGRES)!!
    val root = plan.root
    assertEquals("Hash Join", root.title)
    assertEquals(39.5, root.cost)
    assertEquals(listOf("Seq Scan on orders o", "Hash"), root.children.map { it.title })
    assertEquals("Index Scan using users_pkey on users u", root.children[1].children.single().title)
    assertTrue(root.detail, root.detail.contains("Hash Cond: (o.user_id = u.id)"))
    // actual rows and time count all loops
    assertEquals(3.0, root.children[1].children.single().rows)
    assertEquals(0.03, root.children[1].children.single().timeMs!!, 1e-9)
    // with times, the step that takes longest by itself: the scan (0.7 ms) over the join's own 0.15 ms
    assertSame(root.children[0], plan.hottest)
    assertTrue(plan.text, plan.text.startsWith("-> Hash Join  (cost=39.50 rows=3 time=0.90 ms)"))
  }

  fun testMysqlJsonAndTree() {
    val plan = DbPlan.parse(MYSQL)!!
    assertEquals("query block #1", plan.root.title)
    assertEquals(4.75, plan.root.cost)
    assertEquals(listOf("table u (ALL)", "table o (ref)"), plan.root.children.map { it.title })
    assertEquals(10.0, plan.root.children[1].rows)
    assertTrue(plan.root.children[1].detail.contains("key: fk_user"))
    // costs only: the table read costing the most by itself
    assertSame(plan.root.children[1], plan.hottest)

    val tree = DbPlan.parse(MYSQL_ANALYZE)!!
    assertEquals("Nested loop inner join", tree.root.title)
    assertEquals(listOf("Table scan on u", "Index lookup on o using fk_user (user_id=u.id)"), tree.root.children.map { it.title })
    assertEquals(4.75, tree.root.cost)
    assertEquals(2.0, tree.root.children[1].timeMs!!, 1e-9)
    assertEquals(10.0, tree.root.children[1].rows)
    assertSame(tree.root.children[1], tree.hottest)
    assertEquals(MYSQL_ANALYZE, tree.text)
  }

  fun testH2TextFallbackAndResultPanel() {
    val ds = DbDataSource().apply {
      name = "plan"
      dbms = Dbms.H2
      url = "jdbc:h2:mem:plan${System.nanoTime()};DB_CLOSE_DELAY=-1"
      user = "sa"
    }
    DbDataSourceService.getInstance(project).save(ds, CharArray(0))
    try {
      runBlocking { DbExecutor.execute(project, ds, listOf("CREATE TABLE t (id INT PRIMARY KEY, v INT)")) }
      val outcomes = runBlocking { DbExecutor.execute(project, ds, listOf(DbExplain.sql(Dbms.H2, "SELECT v FROM t WHERE id = 1", false)!!)) }
      val plan = DbPlan.parse(outcomes.single() as DbOutcome.Rows)!!
      assertTrue(plan.root.all().any { it.title.contains("PRIMARY_KEY", ignoreCase = true) })
      assertNull(plan.root.cost)

      val panel = DbResultPanel(project)
      Disposer.register(testRootDisposable, panel)
      panel.showPlan(outcomes)
      assertNotNull(panel.shownPlan)
      // a failure shows as usual
      panel.showPlan(listOf(DbOutcome.Failed("EXPLAIN x", "syntax error", 1)))
      assertNull(panel.shownPlan)
      assertTrue(panel.shownStatus.contains("syntax error"))
    }
    finally {
      DbConnectionService.getInstance(project).disconnect(ds.id)
      DbDataSourceService.getInstance(project).remove(ds.id)
    }
  }

  fun testJsonReader() {
    assertEquals(mapOf("a" to listOf(1L, 2.5, "x\"y", true, null), "b" to mapOf<String, Any?>()), DbJson.parse("""{"a": [1, 2.5, "x\"y", true, null], "b": {}}"""))
    try {
      DbJson.parse("{\"a\": }")
      fail("not JSON")
    }
    catch (_: IllegalStateException) {
    }
  }
}
