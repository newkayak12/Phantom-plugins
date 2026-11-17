// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core

import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import java.math.BigDecimal

/** Where an item of a query stands: the clause keyword it belongs to, looking out of the parentheses around it. */
object SqlClauses {
  private val CLAUSES = setOf("SELECT", "FROM", "JOIN", "WHERE", "ON", "HAVING", "SET", "VALUES", "GROUP", "ORDER", "LIMIT", "OFFSET",
                              "WHEN", "THEN", "ELSE", "USING", "RETURNING", "UPDATE", "INTO", "WINDOW", "PARTITION")

  /** The conditions: a comparison there filters rows (or picks a CASE branch). */
  val CONDITIONS: Set<String> = setOf("WHERE", "ON", "HAVING", "WHEN")

  fun clauseOf(items: List<SqlItem>, index: Int): String? {
    var depth = items[index].depth
    for (j in index - 1 downTo 0) {
      val item = items[j]
      if (item.depth > depth) continue
      depth = item.depth
      if (item.keyword in CLAUSES) return item.keyword
    }
    return null
  }

  /** The index of the `)` that closes the `(` at [open]. */
  fun closing(items: List<SqlItem>, open: Int): Int? =
    (open + 1 until items.size).firstOrNull { items[it].depth == items[open].depth && items[it].type == SqlTokens.RPAREN }

  /** The arguments of the call whose `(` is at [open], with the positions of the commas between them. */
  class Arguments(val values: List<List<SqlItem>>, val commas: List<Int>)

  fun arguments(items: List<SqlItem>, open: Int, close: Int): Arguments {
    val depth = items[open].depth + 1
    val values = ArrayList<List<SqlItem>>()
    val commas = ArrayList<Int>()
    var current = ArrayList<SqlItem>()
    for (i in open + 1 until close) {
      if (items[i].depth == depth && items[i].type == SqlTokens.COMMA) {
        values.add(current)
        commas.add(i)
        current = ArrayList()
      }
      else current.add(items[i])
    }
    if (current.isNotEmpty() || commas.isNotEmpty()) values.add(current)
    return Arguments(values, commas)
  }

  /** The column name a select-list entry gives its result: its alias, or the column it is; null for an unnamed expression. */
  fun outputName(entry: List<SqlItem>): String? {
    entry.lastOrNull { it.element is SqlColumnAlias }?.let { return (it.element as SqlColumnAlias).name }
    return (entry.singleOrNull()?.element as? SqlColumnRef)?.columnName?.takeIf { it.isNotEmpty() }
  }

  /** Whether [element] (a statement) has an outer join anywhere, whose columns may be NULL whatever their definition. */
  fun hasOuterJoin(element: PsiElement): Boolean {
    var leaf: PsiElement? = PsiTreeUtil.firstChild(element)
    val end = element.textRange.endOffset
    while (leaf != null && leaf.textRange.startOffset < end) {
      if (leaf.node.elementType == SqlTokens.KEYWORD && leaf.text.uppercase() in setOf("LEFT", "RIGHT", "FULL")) {
        val next = PsiTreeUtil.nextLeaf(leaf)?.let { skipBlanks(it) }
        if (next != null && next.text.uppercase() in setOf("JOIN", "OUTER")) return true
      }
      leaf = PsiTreeUtil.nextLeaf(leaf)
    }
    return false
  }

  private fun skipBlanks(leaf: PsiElement): PsiElement? {
    var e: PsiElement? = leaf
    while (e != null && (e is PsiWhiteSpace || e is PsiComment)) e = PsiTreeUtil.nextLeaf(e)
    return e
  }
}

/** `a = NULL` and `a <> NULL` in a condition: a comparison with NULL is never true; `IS NULL` is meant. */
class SqlNullComparisonInspection : SqlInspection() {
  override fun checkQuery(query: SqlScope, items: List<SqlItem>, holder: ProblemsHolder) {
    for ((i, op) in items.withIndex()) {
      if (op.type != SqlTokens.OPERATOR || op.text !in setOf("=", "<>", "!=")) continue
      val left = items.getOrNull(i - 1)?.takeIf { it.depth == op.depth && it.isKeyword("NULL") }
      val right = items.getOrNull(i + 1)?.takeIf { it.depth == op.depth && it.isKeyword("NULL") }
      if (left == null && right == null) continue
      if (SqlClauses.clauseOf(items, i) !in SqlClauses.CONDITIONS) continue
      val equal = op.text == "="
      val message = PhantomDbBundle.message(if (equal) "sql.inspection.null.comparison.equal" else "sql.inspection.null.comparison.not.equal")
      if (right != null) {
        val replacement = if (equal) "IS NULL" else "IS NOT NULL"
        register(holder, op.element, right.element, message, ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                 SqlTextFix(PhantomDbBundle.message("sql.fix.replace.with", replacement), replacement))
      }
      else register(holder, left!!.element, op.element, message)
    }
  }
}

/** `1 = 0`, `'a' = 'a'` in a condition: two literals compared, so the condition is always true or always false. */
class SqlConstantConditionInspection : SqlInspection() {
  private val comparisons = setOf("=", "<>", "!=", "<", ">", "<=", ">=")

  override fun checkQuery(query: SqlScope, items: List<SqlItem>, holder: ProblemsHolder) {
    for (i in 1 until items.size - 1) {
      val op = items[i]
      if (op.type != SqlTokens.OPERATOR || op.text !in comparisons) continue
      val (left, right) = items[i - 1] to items[i + 1]
      if (left.depth != op.depth || right.depth != op.depth) continue
      // `a + 1 = 1`, `1 = 1 + a`, `x.y`: the literal is part of a longer expression
      val before = items.getOrNull(i - 2)
      val after = items.getOrNull(i + 2)
      if (before != null && (before.type == SqlTokens.OPERATOR || before.type == SqlTokens.DOT || before.isKeyword("COLLATE", "BINARY", "INTERVAL"))) continue
      if (after != null && (after.type == SqlTokens.OPERATOR || after.type == SqlTokens.DOT || after.type == SqlTokens.LPAREN || after.isKeyword("COLLATE"))) continue
      if (SqlClauses.clauseOf(items, i) !in SqlClauses.CONDITIONS) continue
      val value = evaluate(left, op.text, right) ?: continue
      register(holder, left.element, right.element,
               PhantomDbBundle.message(if (value) "sql.inspection.constant.condition.true" else "sql.inspection.constant.condition.false"),
               ProblemHighlightType.WEAK_WARNING)
    }
  }

  private fun evaluate(left: SqlItem, op: String, right: SqlItem): Boolean? {
    val order = when {
      left.type == SqlTokens.NUMBER && right.type == SqlTokens.NUMBER -> {
        val a = left.text.toBigDecimalOrNull() ?: return null
        val b = right.text.toBigDecimalOrNull() ?: return null
        a.compareTo(b)
      }
      // only identical strings: whether 'a' equals 'A' depends on the collation
      left.type == SqlTokens.STRING && right.type == SqlTokens.STRING && left.text == right.text -> 0
      else -> return null
    }
    return when (op) {
      "=" -> order == 0
      "<>", "!=" -> order != 0
      "<" -> order < 0
      ">" -> order > 0
      "<=" -> order <= 0
      else -> order >= 0
    }
  }

  private fun String.toBigDecimalOrNull(): BigDecimal? = try { BigDecimal(this) } catch (_: NumberFormatException) { null }
}

/** An aggregate in WHERE, ON or GROUP BY, which run before rows are grouped, or an aggregate inside another. */
class SqlAggregateMisuseInspection : SqlInspection() {
  override fun checkQuery(query: SqlScope, items: List<SqlItem>, holder: ProblemsHolder) {
    val calls = ArrayList<IntRange>()
    for ((i, item) in items.withIndex()) {
      if (item.type != SqlTokens.IDENTIFIER || item.text.uppercase() !in SqlQueries.AGGREGATES) continue
      if (items.getOrNull(i + 1)?.type != SqlTokens.LPAREN || items.getOrNull(i - 1)?.type == SqlTokens.DOT) continue
      val close = SqlClauses.closing(items, i + 1) ?: continue
      if (items.getOrNull(close + 1)?.isKeyword("OVER") == true) continue // a window function
      calls.add(i..close)
    }
    for (call in calls) {
      val item = items[call.first]
      val name = item.text
      if (calls.any { it != call && call.first in it }) {
        holder.registerProblem(item.element, PhantomDbBundle.message("sql.inspection.aggregate.nested", name))
        continue
      }
      val clause = SqlClauses.clauseOf(items, call.first)
      if (clause in setOf("WHERE", "ON", "GROUP")) {
        holder.registerProblem(item.element, PhantomDbBundle.message("sql.inspection.aggregate.in.clause", name, if (clause == "GROUP") "GROUP BY" else clause!!))
      }
    }
  }
}

/** COALESCE arguments that can never be returned: after a non-NULL literal, a trailing NULL, or a single argument. */
class SqlRedundantCoalesceInspection : SqlInspection() {
  override fun checkQuery(query: SqlScope, items: List<SqlItem>, holder: ProblemsHolder) {
    for ((i, item) in items.withIndex()) {
      if (item.type != SqlTokens.IDENTIFIER || !item.text.equals("COALESCE", ignoreCase = true)) continue
      if (items.getOrNull(i + 1)?.type != SqlTokens.LPAREN) continue
      val close = SqlClauses.closing(items, i + 1) ?: continue
      val args = SqlClauses.arguments(items, i + 1, close)
      if (args.values.isEmpty() || args.values.any { it.isEmpty() }) continue
      if (args.values.size == 1) {
        register(holder, item.element, items[close].element, PhantomDbBundle.message("sql.inspection.coalesce.single"), ProblemHighlightType.WEAK_WARNING)
        continue
      }
      val constant = args.values.dropLast(1).indexOfFirst { isConstant(it) }
      val from = when {
        constant >= 0 -> constant
        args.values.last().singleOrNull()?.isKeyword("NULL") == true -> args.values.size - 2
        else -> continue
      }
      val message = if (constant >= 0) PhantomDbBundle.message("sql.inspection.coalesce.after.constant")
                    else PhantomDbBundle.message("sql.inspection.coalesce.trailing.null")
      register(holder, items[args.commas[from]].element, args.values.last().last().element, message, ProblemHighlightType.LIKE_UNUSED_SYMBOL,
               SqlTextFix(PhantomDbBundle.message("sql.fix.remove.arguments"), ""))
    }
  }

  private fun isConstant(value: List<SqlItem>): Boolean {
    val single = value.singleOrNull() ?: return false
    return single.type == SqlTokens.NUMBER || single.type == SqlTokens.STRING ||
           single.isKeyword("TRUE", "FALSE", "CURRENT_DATE", "CURRENT_TIME", "CURRENT_TIMESTAMP")
  }
}

/** ORDER BY and LIMIT in an EXISTS subquery, and ORDER BY in an IN subquery without a limit: they change nothing. */
class SqlRedundantLimitInspection : SqlInspection() {
  override fun checkQuery(query: SqlScope, items: List<SqlItem>, holder: ProblemsHolder) {
    if (query !is SqlSubquery) return
    val before = PsiTreeUtil.skipWhitespacesAndCommentsBackward(query)
    if (before?.node?.elementType != SqlTokens.KEYWORD) return
    val exists = before.text.equals("EXISTS", ignoreCase = true)
    if (!exists && !before.text.equals("IN", ignoreCase = true)) return
    val limited = items.any { it.depth == 0 && it.isKeyword("LIMIT", "FETCH", "OFFSET", "TOP") }
    for ((i, item) in items.withIndex()) {
      if (item.depth != 0) continue
      if (item.isKeyword("ORDER") && items.getOrNull(i + 1)?.isKeyword("BY") == true && (exists || !limited)) {
        register(holder, item.element, items[i + 1].element, PhantomDbBundle.message("sql.inspection.redundant.order.in.subquery"),
                 ProblemHighlightType.LIKE_UNUSED_SYMBOL)
      }
      // LIMIT 0 makes EXISTS false; any other limit leaves it as it is
      if (exists && item.isKeyword("LIMIT") && items.getOrNull(i + 1)?.let { it.type == SqlTokens.NUMBER && it.text.trimStart('0').isEmpty() } != true) {
        holder.registerProblem(item.element, PhantomDbBundle.message("sql.inspection.redundant.limit.in.exists"), ProblemHighlightType.LIKE_UNUSED_SYMBOL)
      }
    }
  }
}

/** A column of a derived table or CTE that the rest of the statement never reads. */
class SqlUnusedSubqueryItemInspection : SqlInspection() {
  override fun checkQuery(query: SqlScope, items: List<SqlItem>, holder: ProblemsHolder) {
    if (query !is SqlSubquery) return
    val cte = SqlQueries.cteOf(query)
    if (cte == null && SqlQueries.aliasAfter(query) == null) return
    if (cte != null && PsiTreeUtil.skipWhitespacesAndCommentsForward(cte)?.node?.elementType == SqlTokens.LPAREN) return
    val statement = PsiTreeUtil.getParentOfType(query, SqlStatement::class.java) ?: return
    val arms = SqlQueries.arms(items)
    if (arms.size != 1) return
    // DISTINCT: every column decides which rows are duplicates
    if (arms[0].any { it.depth == 0 && it.isKeyword("DISTINCT", "DISTINCTROW") }) return
    val list = SqlQueries.selectList(arms[0]) ?: return
    if (list.any { entry -> entry.any { it.depth == 0 && it.isOperator("*") } }) return
    val outside = outsideLeaves(statement, query)
    if (outside.any { it.node.elementType == SqlTokens.KEYWORD && it.text.uppercase() in setOf("RECURSIVE", "NATURAL") }) return
    if (outside.withIndex().any { (i, leaf) -> leaf.text == "*" && isStar(outside.getOrNull(i - 1)) }) return

    val listed = list.flatten().map { it.element }.toSet()
    val used = HashSet<String>()
    for (ref in PsiTreeUtil.findChildrenOfType(statement, SqlColumnRef::class.java)) {
      val inside = PsiTreeUtil.isAncestor(query, ref, false)
      // inside, a name counts from HAVING or ORDER BY (an alias used there), not from the select list itself
      if (inside && listed.any { PsiTreeUtil.isAncestor(it, ref, false) }) continue
      used.add(ref.columnName.lowercase())
    }
    val names = list.map { SqlClauses.outputName(it)?.lowercase() }
    if (names.none { it != null && it in used }) return // nothing is read by name: the subquery is used for its rows
    for ((entry, name) in list.zip(names)) {
      if (name == null || name in used || entry.isEmpty()) continue
      register(holder, entry.first().element, entry.last().element, PhantomDbBundle.message("sql.inspection.unused.subquery.item", name),
               ProblemHighlightType.LIKE_UNUSED_SYMBOL)
    }
  }

  private fun outsideLeaves(statement: SqlStatement, query: SqlSubquery): List<PsiElement> {
    val result = ArrayList<PsiElement>()
    var leaf: PsiElement? = PsiTreeUtil.firstChild(statement)
    while (leaf != null && leaf.textRange.startOffset < statement.textRange.endOffset) {
      if (leaf !is PsiWhiteSpace && leaf !is PsiComment && !PsiTreeUtil.isAncestor(query, leaf, false)) result.add(leaf)
      leaf = PsiTreeUtil.nextLeaf(leaf)
    }
    return result
  }

  /** `*` after SELECT, a comma or a dot is all columns; after anything else it is a multiplication or `count(*)`. */
  private fun isStar(previous: PsiElement?): Boolean {
    previous ?: return false
    val type = previous.node.elementType
    return type == SqlTokens.COMMA || type == SqlTokens.DOT ||
           type == SqlTokens.KEYWORD && previous.text.uppercase() in setOf("SELECT", "DISTINCT", "ALL", "DISTINCTROW")
  }
}

/** The same column twice: in UPDATE ... SET, among the columns of a derived table or CTE, or in CREATE TABLE. */
class SqlDuplicateColumnInspection : SqlInspection() {
  override fun checkStatement(statement: SqlStatement, holder: ProblemsHolder) {
    val items = SqlQueries.items(statement)
    var assignments = false
    val seen = HashSet<String>()
    for ((i, item) in items.withIndex()) {
      if (item.depth != 0) continue
      if (item.isKeyword("SET") || item.isKeyword("UPDATE") && items.getOrNull(i - 1)?.isKeyword("KEY") == true) {
        assignments = true
        seen.clear()
        continue
      }
      if (item.keyword in setOf("WHERE", "ORDER", "LIMIT", "RETURNING", "FROM")) assignments = false
      if (!assignments) continue
      val ref = item.element as? SqlColumnRef ?: continue
      val previous = items.getOrNull(i - 1) ?: continue
      if (!(previous.type == SqlTokens.COMMA || previous.isKeyword("SET", "UPDATE")) || items.getOrNull(i + 1)?.isOperator("=") != true) continue
      val key = (ref.qualifier?.qualifierName?.lowercase()?.plus(".") ?: "") + ref.columnName.lowercase()
      if (!seen.add(key)) holder.registerProblem(ref, PhantomDbBundle.message("sql.inspection.duplicate.set.column", ref.columnName))
    }
  }

  override fun checkQuery(query: SqlScope, items: List<SqlItem>, holder: ProblemsHolder) {
    if (query !is SqlSubquery) return
    val cte = SqlQueries.cteOf(query)
    if (cte == null && SqlQueries.aliasAfter(query) == null) return
    if (cte != null && PsiTreeUtil.skipWhitespacesAndCommentsForward(cte)?.node?.elementType == SqlTokens.LPAREN) return
    val list = SqlQueries.selectList(SqlQueries.arms(items).first()) ?: return
    val seen = HashSet<String>()
    for (entry in list) {
      val name = SqlClauses.outputName(entry) ?: continue
      if (!seen.add(name.lowercase())) {
        register(holder, entry.first().element, entry.last().element, PhantomDbBundle.message("sql.inspection.duplicate.output.column", name))
      }
    }
  }

  override fun checkDdl(statement: SqlStatement, holder: ProblemsHolder) {
    val items = SqlQueries.items(statement)
    if (items.firstOrNull()?.isKeyword("CREATE") != true) return
    val table = items.indexOfFirst { it.depth == 0 && it.isKeyword("TABLE") }
    if (table < 0 || items.subList(0, table).any { it.isKeyword("INDEX", "VIEW", "TRIGGER", "FUNCTION", "PROCEDURE") }) return
    val open = (table + 1 until items.size).firstOrNull { items[it].depth == 0 && items[it].type == SqlTokens.LPAREN } ?: return
    val close = SqlClauses.closing(items, open) ?: return
    val seen = HashSet<String>()
    for (definition in SqlClauses.arguments(items, open, close).values) {
      val first = definition.firstOrNull() ?: continue
      if (first.isKeyword(*NOT_COLUMNS) || first.text.uppercase() in NOT_COLUMNS) continue
      val name = SqlNames.unquote(first.text)
      if (!seen.add(name.lowercase())) holder.registerProblem(first.element, PhantomDbBundle.message("sql.inspection.duplicate.table.column", name))
    }
  }

  private companion object {
    val NOT_COLUMNS = arrayOf("CONSTRAINT", "PRIMARY", "UNIQUE", "KEY", "INDEX", "FOREIGN", "CHECK", "FULLTEXT", "SPATIAL", "LIKE", "EXCLUDE", "PERIOD")
  }
}

/** `ORDER BY 3` or `GROUP BY 3` when the select list has fewer columns. */
class SqlOrderByPositionInspection : SqlInspection() {
  private val ends = setOf("LIMIT", "OFFSET", "FETCH", "HAVING", "WINDOW", "FOR", "ORDER", "INTO", "LOCK", "WITH")

  override fun checkQuery(query: SqlScope, items: List<SqlItem>, holder: ProblemsHolder) {
    val arms = SqlQueries.arms(items)
    val first = SqlQueries.columnCount(arms.first())
    for (arm in arms) {
      val own = SqlQueries.columnCount(arm)
      for ((i, item) in arm.withIndex()) {
        if (item.depth != 0 || !item.isKeyword("ORDER", "GROUP") || arm.getOrNull(i + 1)?.isKeyword("BY") != true) continue
        // ORDER BY sorts the whole set operation: positions count in the first arm
        val count = (if (item.isKeyword("ORDER")) first else own) ?: continue
        var start = i + 2
        for (j in i + 2..arm.size) {
          val it = arm.getOrNull(j)
          val end = it == null || it.depth == 0 && (it.type == SqlTokens.COMMA || it.keyword in ends && j > i + 2)
          if (!end) continue
          val entry = arm.subList(start, j).filterNot { it.depth == 0 && it.isKeyword("ASC", "DESC", "NULLS", "FIRST", "LAST") }
          val position = entry.singleOrNull()?.takeIf { it.type == SqlTokens.NUMBER }
          val n = position?.text?.toIntOrNull()
          if (position != null && n != null && (n < 1 || n > count)) {
            holder.registerProblem(position.element, PhantomDbBundle.message("sql.inspection.position.out.of.range", n, count))
          }
          if (it == null || it.type != SqlTokens.COMMA) break
          start = j + 1
        }
      }
    }
  }
}

/**
 * A space between a built-in function name and `(` on MySQL: without the IGNORE_SPACE SQL mode, `COUNT (*)` is read as
 * a call of a stored function named COUNT and fails. Only the functions the MySQL parser treats this way are reported.
 */
class SqlFunctionSpaceInspection : SqlInspection() {
  override fun checkQuery(query: SqlScope, items: List<SqlItem>, holder: ProblemsHolder) {
    val dbms = SqlSchema.dataSourceFor(holder.file, strict = false)?.dialect() ?: return
    if (dbms != Dbms.MYSQL && dbms != Dbms.MARIADB) return
    for (i in 0 until items.size - 1) {
      val name = items[i]
      val paren = items[i + 1]
      if (paren.type != SqlTokens.LPAREN || name.text.uppercase() !in FUNCTIONS) continue
      if (name.element.textRange.endOffset >= paren.element.textRange.startOffset) continue
      if (items.getOrNull(i - 1)?.type == SqlTokens.DOT) continue
      register(holder, name.element, paren.element, PhantomDbBundle.message("sql.inspection.function.space", name.text),
               ProblemHighlightType.GENERIC_ERROR_OR_WARNING, SqlTextFix(PhantomDbBundle.message("sql.fix.remove.space"), name.text + "("))
    }
  }

  private companion object {
    /** The functions MySQL parses specially (its manual's "Function Name Parsing" list). */
    val FUNCTIONS = setOf("ADDDATE", "BIT_AND", "BIT_OR", "BIT_XOR", "CAST", "COUNT", "CURDATE", "CURTIME", "DATE_ADD", "DATE_SUB", "EXTRACT",
                          "GROUP_CONCAT", "MAX", "MID", "MIN", "NOW", "POSITION", "SESSION_USER", "STD", "STDDEV", "STDDEV_POP", "STDDEV_SAMP",
                          "SUBDATE", "SUBSTR", "SUBSTRING", "SUM", "SYSDATE", "SYSTEM_USER", "TRIM", "VARIANCE", "VAR_POP", "VAR_SAMP")
  }
}
