// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.exec

import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.treeStructure.treetable.ListTreeTableModelOnColumns
import com.intellij.ui.treeStructure.treetable.TreeColumnInfo
import com.intellij.ui.treeStructure.treetable.TreeTable
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import dev.phantom.db.core.PhantomDbBundle
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode

/**
 * One step of a query plan. [cost] in the database's own units, [rows] estimated (or actual, after ANALYZE), [timeMs] the
 * real time spent in the step and below it, all loops together; each `null` when the plan does not say.
 */
class DbPlanNode(val title: String, val detail: String, val cost: Double?, val rows: Double?, val timeMs: Double?, val children: List<DbPlanNode>) {
  fun all(): Sequence<DbPlanNode> = sequenceOf(this) + children.asSequence().flatMap { it.all() }
  override fun toString(): String = title
}

/** A plan read from what EXPLAIN printed: PostgreSQL and MySQL/MariaDB JSON, MySQL's `->` tree, else plain indented text (H2). */
class DbPlan(val root: DbPlanNode, val text: String) {
  /** The step that costs the most by itself: its own time if the plan has times, else its own cost (children's subtracted). */
  val hottest: DbPlanNode? by lazy {
    val byTime = root.all().any { it.timeMs != null }
    fun value(n: DbPlanNode) = if (byTime) n.timeMs else n.cost
    root.all().filter { value(it) != null }
      .maxByOrNull { n -> (value(n)!! - n.children.sumOf { value(it) ?: 0.0 }).coerceAtLeast(0.0) }
  }

  companion object {
    /** `null` for an empty output. */
    fun parse(output: String): DbPlan? {
      val text = output.trim()
      if (text.isEmpty()) return null
      val root = when {
        text.startsWith("[") || text.startsWith("{") -> runCatching { fromJson(DbJson.parse(text)) }.getOrNull()
        text.startsWith("->") -> mysqlTree(text)
        else -> null
      } ?: indented(text)
      val shown = if (text.startsWith("[") || text.startsWith("{")) render(root) else text
      return DbPlan(root, shown)
    }

    /** What a one-statement EXPLAIN result holds: every row's cells, one row per line. */
    fun parse(rows: DbOutcome.Rows): DbPlan? =
      parse(rows.rows.joinToString("\n") { row -> row.joinToString("\t") { it?.toString().orEmpty() } })

    private fun fromJson(json: Any?): DbPlanNode? {
      val first = (json as? List<*>)?.firstOrNull() ?: json
      val obj = first as? Map<*, *> ?: return null
      (obj["Plan"] as? Map<*, *>)?.let { return postgres(it) }
      for ((key, value) in obj) if (value is Map<*, *>) return mysql(key.toString(), value)
      return null
    }

    private fun postgres(p: Map<*, *>): DbPlanNode {
      val relation = p["Relation Name"]?.let { r -> " on $r" + (p["Alias"]?.takeIf { it != r }?.let { " $it" } ?: "") }.orEmpty()
      val index = p["Index Name"]?.let { " using $it" }.orEmpty()
      val title = "${p["Node Type"]}$index$relation"
      val detail = DETAIL_KEYS.mapNotNull { k -> p[k]?.let { "$k: ${if (it is List<*>) it.joinToString() else it}" } }.joinToString("; ")
      val loops = num(p["Actual Loops"]) ?: 1.0
      val time = num(p["Actual Total Time"])?.let { it * loops }
      val rows = num(p["Actual Rows"])?.let { it * loops } ?: num(p["Plan Rows"])
      val children = (p["Plans"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().map { postgres(it) }
      return DbPlanNode(title, detail, num(p["Total Cost"]), rows, time, children)
    }

    private fun mysql(key: String, o: Map<*, *>): DbPlanNode {
      val title = when (key) {
        "table" -> "table ${o["table_name"] ?: ""}" + (o["access_type"]?.let { " ($it)" } ?: "")
        "query_block" -> "query block" + (o["select_id"]?.let { " #$it" } ?: "")
        else -> key.replace('_', ' ')
      }
      val detail = listOf("key", "possible_keys", "ref", "attached_condition", "message", "using_index", "using_filesort", "using_temporary_table")
        .mapNotNull { k -> o[k]?.let { "$k: ${if (it is List<*>) it.joinToString() else it}" } }.joinToString("; ")
      val costInfo = o["cost_info"] as? Map<*, *>
      val cost = costInfo?.let { c -> num(c["query_cost"]) ?: num(c["prefix_cost"]) ?: num(c["sort_cost"]) ?: num(c["read_cost"]) } ?: num(o["cost"])
      val rows = num(o["r_rows"]) ?: num(o["rows_produced_per_join"]) ?: num(o["rows_examined_per_scan"]) ?: num(o["rows"])
      val time = num(o["r_total_time_ms"])?.let { t -> t * (num(o["r_loops"]) ?: 1.0) }
      val children = ArrayList<DbPlanNode>()
      for ((k, v) in o) {
        if (k in MYSQL_SKIP) continue
        when (v) {
          is Map<*, *> -> children += mysql(k.toString(), v)
          is List<*> -> for (e in v.filterIsInstance<Map<*, *>>()) {
            for ((k2, v2) in e) if (v2 is Map<*, *> && k2 !in MYSQL_SKIP) children += mysql(k2.toString(), v2)
          }
        }
      }
      return DbPlanNode(title, detail, cost, rows, time, children)
    }

    private val COST = Regex("""\(cost=([\d.e+]+)(?:\.\.[\d.e+]+)? rows=([\d.e+]+)\)""")
    private val ACTUAL = Regex("""\(actual time=[\d.]+\.\.([\d.]+) rows=([\d.e+]+) loops=(\d+)\)""")

    /** MySQL's EXPLAIN ANALYZE / FORMAT=TREE: `-> step  (cost=… rows=…) (actual time=a..b rows=… loops=…)`, nested by indentation. */
    private fun mysqlTree(text: String): DbPlanNode? {
      val lines = text.lines().filter { it.trimStart().startsWith("->") }
      if (lines.isEmpty()) return null
      return nest(lines.map { line ->
        val body = line.trimStart().removePrefix("->").trim()
        val actual = ACTUAL.find(body)
        val estimate = COST.find(body)
        val title = body.substringBefore("  (").substringBefore(" (cost=").substringBefore(" (actual").trim()
        val loops = actual?.groupValues?.get(3)?.toDoubleOrNull() ?: 1.0
        Line(line.length - line.trimStart().length, title, "", estimate?.groupValues?.get(1)?.toDoubleOrNull(),
             actual?.groupValues?.get(2)?.toDoubleOrNull()?.times(loops) ?: estimate?.groupValues?.get(2)?.toDoubleOrNull(),
             actual?.groupValues?.get(1)?.toDoubleOrNull()?.times(loops))
      })
    }

    /** Plain text, one step per line, nested by indentation (H2's plan). */
    private fun indented(text: String): DbPlanNode =
      nest(text.lines().filter { it.isNotBlank() }.map { Line(it.length - it.trimStart().length, it.trim(), "", null, null, null) })

    private class Line(val indent: Int, val title: String, val detail: String, val cost: Double?, val rows: Double?, val time: Double?) {
      val children = ArrayList<Line>()
      fun node(): DbPlanNode = DbPlanNode(title, detail, cost, rows, time, children.map { it.node() })
    }

    /** Lines under a less indented one are its children; several top lines get a common root. */
    private fun nest(lines: List<Line>): DbPlanNode {
      val tops = ArrayList<Line>()
      val stack = ArrayList<Line>()
      for (line in lines) {
        while (stack.isNotEmpty() && stack.last().indent >= line.indent) stack.removeAt(stack.size - 1)
        if (stack.isEmpty()) tops += line else stack.last().children += line
        stack += line
      }
      return if (tops.size == 1) tops[0].node()
      else DbPlanNode(PhantomDbBundle.message("db.plan.root"), "", null, null, null, tops.map { it.node() })
    }

    /** The tree as indented text, for the Text tab of a plan that came as JSON. */
    fun render(root: DbPlanNode): String = buildString {
      fun go(n: DbPlanNode, depth: Int) {
        append("  ".repeat(depth)).append("-> ").append(n.title)
        val figures = listOfNotNull(n.cost?.let { "cost=${format(it)}" }, n.rows?.let { "rows=${format(it)}" }, n.timeMs?.let { "time=${format(it)} ms" })
        if (figures.isNotEmpty()) append("  (").append(figures.joinToString(" ")).append(")")
        append('\n')
        if (n.detail.isNotEmpty()) append("  ".repeat(depth + 2)).append(n.detail).append('\n')
        n.children.forEach { go(it, depth + 1) }
      }
      go(root, 0)
    }

    fun format(v: Double): String = if (v == Math.floor(v) && v < 1e15) v.toLong().toString() else "%.2f".format(java.util.Locale.ROOT, v)

    private fun num(v: Any?): Double? = when (v) {
      is Number -> v.toDouble()
      is String -> v.toDoubleOrNull()
      else -> null
    }

    private val DETAIL_KEYS = listOf("Join Type", "Index Cond", "Hash Cond", "Merge Cond", "Filter", "Join Filter", "Sort Key", "Group Key", "Strategy")
    private val MYSQL_SKIP = setOf("cost_info", "r_engine_stats", "used_columns", "used_key_parts", "possible_keys")
  }
}

/** A small JSON reader for EXPLAIN output: objects keep their key order. */
internal object DbJson {
  fun parse(text: String): Any? = Reader(text).run { value().also { skip(); if (i < s.length) error("trailing text at $i") } }

  private class Reader(val s: String) {
    var i = 0

    fun skip() {
      while (i < s.length && s[i].isWhitespace()) i++
    }

    fun value(): Any? {
      skip()
      if (i >= s.length) error("unexpected end")
      return when (val c = s[i]) {
        '{' -> obj()
        '[' -> arr()
        '"' -> str()
        't' -> word("true", true)
        'f' -> word("false", false)
        'n' -> word("null", null)
        else -> if (c == '-' || c.isDigit()) number() else error("unexpected '$c' at $i")
      }
    }

    private fun word(w: String, v: Any?): Any? {
      if (!s.startsWith(w, i)) error("unexpected text at $i")
      i += w.length
      return v
    }

    /** Whole numbers stay whole (`select_id: 1`), others are doubles. */
    private fun number(): Number {
      val start = i
      while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
      val text = s.substring(start, i)
      return text.toLongOrNull() ?: text.toDouble()
    }

    private fun str(): String = buildString {
      i++
      while (true) {
        if (i >= s.length) error("unterminated string")
        val c = s[i++]
        when (c) {
          '"' -> return@buildString
          '\\' -> {
            when (val e = s[i++]) {
              'n' -> append('\n')
              't' -> append('\t')
              'r' -> append('\r')
              'b' -> append('\b')
              'f' -> append('\u000c')
              'u' -> {
                append(s.substring(i, i + 4).toInt(16).toChar())
                i += 4
              }
              else -> append(e)
            }
          }
          else -> append(c)
        }
      }
    }

    private fun obj(): Map<String, Any?> {
      i++
      val m = LinkedHashMap<String, Any?>()
      skip()
      if (s[i] == '}') return m.also { i++ }
      while (true) {
        skip()
        val k = str()
        skip()
        if (s[i++] != ':') error("':' expected at ${i - 1}")
        m[k] = value()
        skip()
        when (s[i++]) {
          ',' -> continue
          '}' -> return m
          else -> error("',' or '}' expected at ${i - 1}")
        }
      }
    }

    private fun arr(): List<Any?> {
      i++
      val l = ArrayList<Any?>()
      skip()
      if (s[i] == ']') return l.also { i++ }
      while (true) {
        l += value()
        skip()
        when (s[i++]) {
          ',' -> continue
          ']' -> return l
          else -> error("',' or ']' expected at ${i - 1}")
        }
      }
    }
  }
}

/** The plan as a tree with cost, rows and time columns, the costliest step highlighted; the text form in a second tab. */
class DbPlanView(val plan: DbPlan) : JPanel(BorderLayout()) {
  init {
    val root = node(plan.root)
    val columns = arrayOf<ColumnInfo<*, *>>(
      TreeColumnInfo(PhantomDbBundle.message("db.plan.column.step")),
      figure(PhantomDbBundle.message("db.plan.column.cost")) { it.cost },
      figure(PhantomDbBundle.message("db.plan.column.rows")) { it.rows },
      figure(PhantomDbBundle.message("db.plan.column.time")) { it.timeMs },
    )
    val table = TreeTable(ListTreeTableModelOnColumns(root, columns))
    table.setRootVisible(true)
    table.setTreeCellRenderer(object : ColoredTreeCellRenderer() {
      override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
        val n = (value as? DefaultMutableTreeNode)?.userObject as? DbPlanNode ?: return
        val hot = n === plan.hottest
        append(n.title, if (hot) SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JBColor.RED) else SimpleTextAttributes.REGULAR_ATTRIBUTES)
        if (n.detail.isNotEmpty()) append("  " + n.detail, SimpleTextAttributes.GRAYED_ATTRIBUTES)
        if (hot) toolTipText = PhantomDbBundle.message("db.plan.hottest")
      }
    })
    TreeUtil.expandAll(table.tree)
    table.columnModel.getColumn(0).preferredWidth = JBUI.scale(520)
    val tabs = JBTabbedPane()
    tabs.addTab(PhantomDbBundle.message("db.plan.tab.tree"), JBScrollPane(table))
    tabs.addTab(PhantomDbBundle.message("db.plan.tab.text"), JBScrollPane(JBTextArea(plan.text).apply {
      isEditable = false
      font = JBUI.Fonts.create(java.awt.Font.MONOSPACED, font.size)
      border = JBUI.Borders.empty(4, 8)
    }))
    add(tabs, BorderLayout.CENTER)
  }

  private fun node(n: DbPlanNode): DefaultMutableTreeNode = DefaultMutableTreeNode(n).apply { n.children.forEach { add(node(it)) } }

  private fun figure(name: String, get: (DbPlanNode) -> Double?) = object : ColumnInfo<DefaultMutableTreeNode, String>(name) {
    override fun valueOf(item: DefaultMutableTreeNode?): String =
      ((item?.userObject as? DbPlanNode)?.let(get))?.let { DbPlan.format(it) }.orEmpty()
  }
}
