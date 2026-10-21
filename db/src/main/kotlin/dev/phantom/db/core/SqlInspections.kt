// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core

import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil

/** A significant child of a query (statement or subquery) and its depth in parentheses; `(` and its `)` share the outer depth. */
class SqlItem(val element: PsiElement, val depth: Int) {
  val type: IElementType = element.node.elementType
  val keyword: String? = if (type == SqlTokens.KEYWORD) element.text.uppercase() else null
  val text: String get() = element.text

  fun isKeyword(vararg words: String): Boolean = keyword != null && keyword in words
  fun isOperator(text: String): Boolean = type == SqlTokens.OPERATOR && element.text == text
}

/** The flat view of a query the inspections work on: items, UNION arms, select lists, INSERT rows. */
object SqlQueries {
  /** Aggregate functions of the supported dialects; with OVER they are window functions instead. */
  val AGGREGATES: Set<String> = setOf("COUNT", "SUM", "AVG", "MIN", "MAX", "GROUP_CONCAT", "STRING_AGG", "ARRAY_AGG", "JSON_AGG", "JSONB_AGG",
                                      "JSON_ARRAYAGG", "JSON_OBJECTAGG", "JSON_OBJECT_AGG", "LISTAGG", "BIT_AND", "BIT_OR", "BIT_XOR", "BOOL_AND",
                                      "BOOL_OR", "EVERY", "STDDEV", "STDDEV_POP", "STDDEV_SAMP", "VARIANCE", "VAR_POP", "VAR_SAMP", "ANY_VALUE")
  private val DML = setOf("SELECT", "WITH", "INSERT", "UPDATE", "DELETE", "REPLACE")
  private val SET_OPERATORS = setOf("UNION", "EXCEPT", "INTERSECT", "MINUS")
  private val SELECT_LIST_END = setOf("FROM", "INTO", "WHERE", "GROUP", "HAVING", "ORDER", "LIMIT", "WINDOW", "FETCH", "OFFSET", "FOR")
  private val KOTLIN_TEMPLATE = Regex("\\$[A-Za-z_{]")

  fun isDml(statement: SqlStatement): Boolean = statement.firstKeyword in DML

  /**
   * False for SQL injected into a Kotlin string with templates: the text is not the whole statement then, so checks
   * about what is missing (WHERE, ON) would be guesses.
   */
  fun isComplete(file: PsiFile): Boolean {
    val manager = InjectedLanguageManager.getInstance(file.project)
    if (!manager.isInjectedFragment(file)) return true
    val host = manager.getInjectionHost(file) ?: return true
    return !(host.language.id == "kotlin" && KOTLIN_TEMPLATE.containsMatchIn(host.text))
  }

  fun items(scope: PsiElement): List<SqlItem> {
    val children = ArrayList<PsiElement>()
    var child = scope.firstChild
    while (child != null) {
      if (child !is PsiWhiteSpace && child !is PsiComment && child.node.elementType != SqlTokens.SEMICOLON) children.add(child)
      child = child.nextSibling
    }
    if (scope is SqlSubquery) {
      if (children.firstOrNull()?.node?.elementType == SqlTokens.LPAREN) children.removeAt(0)
      if (children.lastOrNull()?.node?.elementType == SqlTokens.RPAREN) children.removeAt(children.size - 1)
    }
    val result = ArrayList<SqlItem>(children.size)
    var depth = 0
    for (c in children) {
      val type = c.node.elementType
      if (type == SqlTokens.RPAREN) depth = maxOf(0, depth - 1)
      result.add(SqlItem(c, depth))
      if (type == SqlTokens.LPAREN) depth++
    }
    return result
  }

  /** The arms of `a UNION b EXCEPT c`, without the operators. */
  fun arms(items: List<SqlItem>): List<List<SqlItem>> {
    val result = ArrayList<List<SqlItem>>()
    var current = ArrayList<SqlItem>()
    var skipQuantifier = false
    for (item in items) {
      if (item.depth == 0 && item.keyword in SET_OPERATORS) {
        result.add(current)
        current = ArrayList()
        skipQuantifier = true
        continue
      }
      if (skipQuantifier && item.depth == 0 && item.isKeyword("ALL", "DISTINCT")) continue
      skipQuantifier = false
      current.add(item)
    }
    result.add(current)
    return result
  }

  /** The items of each select-list entry of [arm], or null when the arm has no SELECT at its top level. */
  fun selectList(arm: List<SqlItem>): List<List<SqlItem>>? {
    val select = arm.indexOfFirst { it.depth == 0 && it.isKeyword("SELECT") }
    if (select < 0) return null
    val entries = ArrayList<List<SqlItem>>()
    var current = ArrayList<SqlItem>()
    var i = select + 1
    while (i < arm.size && arm[i].depth == 0 && arm[i].isKeyword("DISTINCT", "ALL", "DISTINCTROW")) i++
    while (i < arm.size) {
      val item = arm[i]
      if (item.depth == 0 && item.keyword in SELECT_LIST_END) break
      if (item.depth == 0 && item.type == SqlTokens.COMMA) {
        entries.add(current)
        current = ArrayList()
      }
      else current.add(item)
      i++
    }
    if (current.isNotEmpty()) entries.add(current)
    return entries
  }

  /** Columns an arm returns, or null when it cannot be counted (`*`, no SELECT). */
  fun columnCount(arm: List<SqlItem>): Int? {
    val significant = arm.singleOrNull()
    if (significant != null && significant.element is SqlSubquery) return columnCount(arms(items(significant.element)).first())
    val list = selectList(arm) ?: return null
    if (list.any { entry -> entry.any { it.depth == 0 && it.isOperator("*") } }) return null
    return list.size
  }

  /** The column list, target and rows of an INSERT or REPLACE with `(columns) VALUES (...)`. */
  class Insert(val target: SqlTableRef?, val columns: List<InsertColumn>, val rows: List<Row>, val select: List<SqlItem>?)

  /** One name of the column list; also a name that is a keyword elsewhere (`date`, `day`), which the parser leaves unmarked. */
  class InsertColumn(val element: PsiElement) {
    val name: String get() = SqlNames.unquote(element.text)
  }

  /** One `( ... )` of VALUES: its parentheses and the items of each value. */
  class Row(val open: SqlItem, val close: SqlItem, val values: List<List<SqlItem>>)

  fun insert(statement: SqlStatement): Insert? {
    val items = items(statement)
    val start = items.indexOfFirst { it.depth == 0 && it.isKeyword("INSERT", "REPLACE", "SELECT", "UPDATE", "DELETE") }
    if (start < 0 || !items[start].isKeyword("INSERT", "REPLACE")) return null
    val targetAt = (start until items.size).firstOrNull { items[it].depth == 0 && items[it].element is SqlTableRef }
    val target = targetAt?.let { items[it].element as SqlTableRef }
    val columns = ArrayList<InsertColumn>()
    if (targetAt != null && items.getOrNull(targetAt + 1)?.let { it.depth == 0 && it.type == SqlTokens.LPAREN } == true) {
      var i = targetAt + 2
      while (i < items.size && !(items[i].depth == 0 && items[i].type == SqlTokens.RPAREN)) {
        val item = items[i]
        if (item.depth == 1 && item.type != SqlTokens.COMMA) columns.add(InsertColumn(item.element))
        i++
      }
    }
    val values = items.indexOfFirst { it.depth == 0 && it.isKeyword("VALUES", "VALUE") }
    val rows = ArrayList<Row>()
    if (values >= 0) {
      var i = values + 1
      while (i < items.size) {
        if (items[i].depth == 0 && items[i].isKeyword("ROW")) i++
        val open = items.getOrNull(i) ?: break
        if (open.depth != 0 || open.type != SqlTokens.LPAREN) break
        val row = ArrayList<List<SqlItem>>()
        var value = ArrayList<SqlItem>()
        var j = i + 1
        while (j < items.size && !(items[j].depth == 0 && items[j].type == SqlTokens.RPAREN)) {
          val item = items[j]
          if (item.depth == 1 && item.type == SqlTokens.COMMA) {
            row.add(value)
            value = ArrayList()
          }
          else value.add(item)
          j++
        }
        val close = items.getOrNull(j) ?: break
        if (value.isNotEmpty() || row.isNotEmpty()) row.add(value)
        rows.add(Row(open, close, row))
        i = j + 1
        if (items.getOrNull(i)?.let { it.depth == 0 && it.type == SqlTokens.COMMA } != true) break
        i++
      }
    }
    val selectArm = if (values < 0) arms(items.drop(start)).first().takeIf { arm -> arm.any { it.depth == 0 && it.isKeyword("SELECT") } } else null
    return Insert(target, columns, rows, selectArm)
  }

  /** A `CASE ... END` in a list of items: the positions of its WHEN, THEN and ELSE at its own level, and of its END. */
  class Case(val start: Int, val end: Int, val whens: List<Int>, val thens: List<Int>, val elseAt: Int?)

  fun cases(items: List<SqlItem>): List<Case> {
    val result = ArrayList<Case>()
    for ((start, item) in items.withIndex()) {
      if (!item.isKeyword("CASE")) continue
      val depth = item.depth
      var nest = 0
      val whens = ArrayList<Int>()
      val thens = ArrayList<Int>()
      var elseAt: Int? = null
      for (i in start until items.size) {
        val it = items[i]
        if (it.depth < depth) break
        if (it.depth != depth) continue
        when (it.keyword) {
          "CASE" -> nest++
          "END" -> {
            nest--
            if (nest == 0) {
              result.add(Case(start, i, whens, thens, elseAt))
              break
            }
          }
          "WHEN" -> if (nest == 1) whens.add(i)
          "THEN" -> if (nest == 1) thens.add(i)
          "ELSE" -> if (nest == 1) elseAt = i
        }
      }
    }
    return result
  }

  /** Text for comparing two expressions: no blanks, any case. */
  fun normalized(items: List<SqlItem>): String = items.joinToString("") { it.text.filterNot(Char::isWhitespace) }.lowercase()

  /** Whether [subquery] is the body of `WITH name [(columns)] AS [NOT] [MATERIALIZED] (...)`. */
  fun isCteBody(subquery: SqlSubquery): Boolean = cteOf(subquery) != null

  fun cteOf(subquery: SqlSubquery): SqlCteName? {
    var prev = PsiTreeUtil.skipWhitespacesAndCommentsBackward(subquery)
    while (prev != null && prev.node.elementType == SqlTokens.KEYWORD && prev.text.uppercase() in setOf("AS", "NOT", "MATERIALIZED")) {
      prev = PsiTreeUtil.skipWhitespacesAndCommentsBackward(prev)
    }
    if (prev is SqlCteName) return prev
    // `x (a, b) AS (...)`: the column list is plain tokens
    if (prev?.node?.elementType == SqlTokens.RPAREN) {
      var p: PsiElement? = prev
      while (p != null && p !is SqlCteName && p.node.elementType != SqlTokens.COMMA) p = PsiTreeUtil.skipWhitespacesAndCommentsBackward(p)
      return p as? SqlCteName
    }
    return null
  }

  /** The alias that follows a derived table: `(SELECT ...) d` or `(SELECT ...) AS d`. */
  fun aliasAfter(element: PsiElement): SqlAlias? {
    var next = PsiTreeUtil.skipWhitespacesAndCommentsForward(element)
    if (next?.node?.elementType == SqlTokens.KEYWORD && next.text.equals("AS", ignoreCase = true)) next = PsiTreeUtil.skipWhitespacesAndCommentsForward(next)
    return next as? SqlAlias
  }
}

/**
 * Base of the SQL inspections: they look at SELECT, INSERT, UPDATE, DELETE and WITH statements, once per statement
 * and once per subquery, and at CREATE and ALTER statements through [checkDdl]. Other statements are left alone.
 */
abstract class SqlInspection : LocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
    val file = holder.file as? SqlFile ?: return PsiElementVisitor.EMPTY_VISITOR
    if (!SqlQueries.isComplete(file)) return PsiElementVisitor.EMPTY_VISITOR
    return object : PsiElementVisitor() {
      override fun visitElement(element: PsiElement) {
        when (element) {
          is SqlStatement -> if (SqlQueries.isDml(element)) {
            checkStatement(element, holder)
            checkQuery(element, SqlQueries.items(element), holder)
          }
          else if (element.firstKeyword in DDL) checkDdl(element, holder)
          is SqlSubquery -> {
            val statement = PsiTreeUtil.getParentOfType(element, SqlStatement::class.java) ?: return
            if (SqlQueries.isDml(statement)) checkQuery(element, SqlQueries.items(element), holder)
          }
        }
      }
    }
  }

  protected open fun checkStatement(statement: SqlStatement, holder: ProblemsHolder) {}

  /** CREATE and ALTER statements, which the query checks leave alone. */
  protected open fun checkDdl(statement: SqlStatement, holder: ProblemsHolder) {}

  /** [query] is the statement or a subquery; [items] are its own, not those of nested subqueries. */
  protected open fun checkQuery(query: SqlScope, items: List<SqlItem>, holder: ProblemsHolder) {}

  protected fun register(holder: ProblemsHolder, from: PsiElement, to: PsiElement, message: String,
                         type: ProblemHighlightType = ProblemHighlightType.GENERIC_ERROR_OR_WARNING, vararg fixes: LocalQuickFix) {
    holder.registerProblem(holder.manager.createProblemDescriptor(from, to, message, type, holder.isOnTheFly, *fixes))
  }

  private companion object {
    val DDL = setOf("CREATE", "ALTER")
  }
}

/** Replaces the problem's range (or inserts before or after it) in the SQL text, which may be injected in a string. */
class SqlTextFix(
  private val name: String,
  private val replacement: String,
  private val mode: Mode = Mode.REPLACE,
) : LocalQuickFix {
  enum class Mode { REPLACE, DELETE_WITH_SPACE_BEFORE, INSERT_BEFORE, INSERT_AFTER }

  override fun getFamilyName(): String = name

  override fun generatePreview(project: Project, previewDescriptor: ProblemDescriptor): IntentionPreviewInfo = IntentionPreviewInfo.EMPTY

  override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
    val start = descriptor.startElement ?: return
    val end = descriptor.endElement ?: start
    val documents = PsiDocumentManager.getInstance(project)
    val document = documents.getDocument(start.containingFile) ?: return
    var from = start.textRange.startOffset
    var to = end.textRange.endOffset
    when (mode) {
      Mode.REPLACE -> {}
      Mode.DELETE_WITH_SPACE_BEFORE -> (start.prevSibling as? PsiWhiteSpace)?.let { from = it.textRange.startOffset }
      Mode.INSERT_BEFORE -> to = from
      Mode.INSERT_AFTER -> from = to
    }
    document.replaceString(from, to, replacement)
    documents.commitDocument(document)
  }
}

/** `UPDATE t SET ...` and `DELETE FROM t` with no WHERE: every row changes. */
class SqlWithoutWhereInspection : SqlInspection() {
  override fun checkStatement(statement: SqlStatement, holder: ProblemsHolder) {
    val items = SqlQueries.items(statement)
    val start = items.indexOfFirst { it.depth == 0 && it.isKeyword("SELECT", "INSERT", "REPLACE", "UPDATE", "DELETE") }
    if (start < 0 || !items[start].isKeyword("UPDATE", "DELETE")) return
    if (items.drop(start).any { it.depth == 0 && it.isKeyword("WHERE") }) return
    val key = if (items[start].isKeyword("UPDATE")) "sql.inspection.without.where.update" else "sql.inspection.without.where.delete"
    holder.registerProblem(items[start].element, PhantomDbBundle.message(key))
  }
}

/** `a JOIN b` with neither ON nor USING (CROSS and NATURAL joins excepted): every row of a meets every row of b. */
class SqlJoinWithoutOnInspection : SqlInspection() {
  private val ends = setOf("JOIN", "LEFT", "RIGHT", "INNER", "OUTER", "FULL", "CROSS", "NATURAL", "WHERE", "GROUP", "HAVING", "ORDER",
                           "LIMIT", "OFFSET", "FETCH", "WINDOW", "UNION", "EXCEPT", "INTERSECT", "MINUS", "SET", "RETURNING", "FOR", "INTO")

  override fun checkQuery(query: SqlScope, items: List<SqlItem>, holder: ProblemsHolder) {
    for ((index, item) in items.withIndex()) {
      if (!item.isKeyword("JOIN")) continue
      var back = index - 1
      while (back >= 0 && items[back].depth == item.depth && items[back].isKeyword("LEFT", "RIGHT", "FULL", "INNER", "OUTER")) back--
      if (back >= 0 && items[back].depth == item.depth && items[back].isKeyword("CROSS", "NATURAL")) continue
      if (hasCondition(items, index)) continue
      holder.registerProblem(item.element, PhantomDbBundle.message("sql.inspection.join.without.on"))
    }
  }

  private fun hasCondition(items: List<SqlItem>, join: Int): Boolean {
    val depth = items[join].depth
    for (i in join + 1 until items.size) {
      val it = items[i]
      if (it.depth < depth) return false
      if (it.depth > depth) continue
      if (it.isKeyword("ON", "USING")) return true
      if (it.keyword in ends || it.type == SqlTokens.COMMA) return false
    }
    return false
  }
}

/** A CTE nothing in the statement reads. */
class SqlUnusedCteInspection : SqlInspection() {
  override fun checkStatement(statement: SqlStatement, holder: ProblemsHolder) {
    val ctes = PsiTreeUtil.findChildrenOfType(statement, SqlCteName::class.java)
    if (ctes.isEmpty()) return
    val tables = PsiTreeUtil.findChildrenOfType(statement, SqlTableRef::class.java)
    val qualifiers = PsiTreeUtil.findChildrenOfType(statement, SqlQualifier::class.java)
    for (cte in ctes) {
      val body = bodyOf(cte)
      fun outside(e: PsiElement) = body == null || !PsiTreeUtil.isAncestor(body, e, false)
      val used = tables.any { it.schemaName == null && SqlNames.same(it.tableName, cte.name) && outside(it) } ||
                 qualifiers.any { it.schemaName == null && SqlNames.same(it.qualifierName, cte.name) && outside(it) }
      if (!used) holder.registerProblem(cte, PhantomDbBundle.message("sql.inspection.unused.cte", cte.name), ProblemHighlightType.LIKE_UNUSED_SYMBOL)
    }
  }

  private fun bodyOf(cte: SqlCteName): SqlSubquery? {
    var next = PsiTreeUtil.skipWhitespacesAndCommentsForward(cte)
    while (next != null && next !is SqlSubquery && next.node.elementType != SqlTokens.COMMA && next !is SqlCteName) {
      next = PsiTreeUtil.skipWhitespacesAndCommentsForward(next)
    }
    return next as? SqlSubquery
  }
}

/** `FROM users users`: an alias that repeats the table name. */
class SqlRedundantAliasInspection : SqlInspection() {
  override fun checkQuery(query: SqlScope, items: List<SqlItem>, holder: ProblemsHolder) {
    for (item in items) {
      val alias = item.element as? SqlAlias ?: continue
      val table = alias.table ?: continue
      if (!SqlNames.same(alias.name, table.tableName)) continue
      // remove `AS users` or ` users`
      val from = PsiTreeUtil.skipWhitespacesAndCommentsForward(table) ?: continue
      register(holder, from, alias, PhantomDbBundle.message("sql.inspection.redundant.alias", alias.name), ProblemHighlightType.LIKE_UNUSED_SYMBOL,
               SqlTextFix(PhantomDbBundle.message("sql.fix.remove.alias"), "", SqlTextFix.Mode.DELETE_WITH_SPACE_BEFORE))
    }
  }
}

/** An alias declared twice in one query, or hiding a name of the query around it. */
class SqlShadowingAliasInspection : SqlInspection() {
  override fun checkQuery(query: SqlScope, items: List<SqlItem>, holder: ProblemsHolder) {
    val aliases = items.mapNotNull { it.element as? SqlAlias }
    val seen = HashSet<String>()
    for (alias in aliases) {
      if (!seen.add(alias.name.lowercase())) {
        holder.registerProblem(alias, PhantomDbBundle.message("sql.inspection.duplicate.alias", alias.name))
      }
    }
    if (query !is SqlSubquery || SqlQueries.isCteBody(query)) return
    val ownAlias = SqlQueries.aliasAfter(query)
    for (alias in aliases) {
      for (outer in SqlScopes.chain(query)) {
        val hidden = SqlScopes.direct(outer, SqlAlias::class.java).any { it != ownAlias && SqlNames.same(it.name, alias.name) } ||
                     SqlScopes.direct(outer, SqlTableRef::class.java).any { it.alias == null && SqlNames.same(it.tableName, alias.name) }
        if (hidden) {
          holder.registerProblem(alias, PhantomDbBundle.message("sql.inspection.shadowing.alias", alias.name), ProblemHighlightType.WEAK_WARNING)
          break
        }
      }
    }
  }
}

/** INSERT whose rows do not have as many values as columns are listed, or that lists a column twice. */
class SqlInsertValuesInspection : SqlInspection() {
  override fun checkStatement(statement: SqlStatement, holder: ProblemsHolder) {
    val insert = SqlQueries.insert(statement) ?: return
    val seen = HashSet<String>()
    for (column in insert.columns) {
      if (!seen.add(column.name.lowercase())) holder.registerProblem(column.element, PhantomDbBundle.message("sql.inspection.insert.duplicate.column", column.name))
    }
    if (insert.columns.isEmpty()) return
    val expected = insert.columns.size
    for (row in insert.rows) {
      if (row.values.size != expected) {
        register(holder, row.open.element, row.close.element, PhantomDbBundle.message("sql.inspection.insert.values.count", row.values.size, expected))
      }
    }
    val select = insert.select ?: return
    val count = SqlQueries.columnCount(select) ?: return
    if (count != expected) {
      val keyword = select.first { it.depth == 0 && it.isKeyword("SELECT") }
      holder.registerProblem(keyword.element, PhantomDbBundle.message("sql.inspection.insert.select.count", count, expected))
    }
  }
}

/** `SELECT a, b ... UNION SELECT c`: the arms of a set operation return different numbers of columns. */
class SqlUnionColumnCountInspection : SqlInspection() {
  override fun checkQuery(query: SqlScope, items: List<SqlItem>, holder: ProblemsHolder) {
    val arms = SqlQueries.arms(items)
    if (arms.size < 2) return
    val counts = arms.map { SqlQueries.columnCount(it) ?: return }
    val first = counts.first()
    for ((i, count) in counts.withIndex()) {
      if (count == first) continue
      val anchor = arms[i].firstOrNull { it.depth == 0 && it.isKeyword("SELECT") }?.element ?: arms[i].firstOrNull()?.element ?: continue
      holder.registerProblem(anchor, PhantomDbBundle.message("sql.inspection.union.count", count, first))
    }
  }
}

/** Two LIMIT (or LIMIT and FETCH FIRST) clauses in one query. */
class SqlMultipleLimitInspection : SqlInspection() {
  override fun checkQuery(query: SqlScope, items: List<SqlItem>, holder: ProblemsHolder) {
    for (arm in SqlQueries.arms(items)) {
      val limits = arm.filter { it.depth == 0 && it.isKeyword("LIMIT", "FETCH") }
      for (extra in limits.drop(1)) holder.registerProblem(extra.element, PhantomDbBundle.message("sql.inspection.multiple.limit"))
    }
  }
}

/** `ORDER BY x ASC`: ascending is the default. */
class SqlRedundantOrderingDirectionInspection : SqlInspection() {
  private val ends = setOf("LIMIT", "OFFSET", "FETCH", "UNION", "EXCEPT", "INTERSECT", "MINUS", "FOR", "ROWS", "RANGE", "GROUPS",
                           "SEPARATOR", "WINDOW", "INTO", "LOCK")

  override fun checkQuery(query: SqlScope, items: List<SqlItem>, holder: ProblemsHolder) {
    for ((index, item) in items.withIndex()) {
      if (!item.isKeyword("ORDER") || items.getOrNull(index + 1)?.isKeyword("BY") != true) continue
      for (i in index + 2 until items.size) {
        val it = items[i]
        if (it.depth < item.depth) break
        if (it.depth > item.depth) continue
        if (it.keyword in ends) break
        if (it.isKeyword("ASC")) {
          holder.registerProblem(it.element, PhantomDbBundle.message("sql.inspection.redundant.asc"), ProblemHighlightType.LIKE_UNUSED_SYMBOL,
                                 SqlTextFix(PhantomDbBundle.message("sql.fix.remove.asc"), "", SqlTextFix.Mode.DELETE_WITH_SPACE_BEFORE))
        }
      }
    }
  }
}

/** `CASE WHEN x IS NULL THEN y ELSE x END` is `COALESCE(x, y)`. */
class SqlCaseVsCoalesceInspection : SqlInspection() {
  override fun checkQuery(query: SqlScope, items: List<SqlItem>, holder: ProblemsHolder) {
    for (case in SqlQueries.cases(items)) {
      if (case.whens.size != 1 || case.thens.size != 1 || case.elseAt == null) continue
      val whenAt = case.whens[0]
      if (whenAt != case.start + 1) continue // `CASE x WHEN ...` compares values
      val thenAt = case.thens[0]
      val condition = items.subList(whenAt + 1, thenAt)
      if (condition.size < 3 || !condition.last().isKeyword("NULL")) continue
      val not = condition[condition.size - 2].isKeyword("NOT")
      val isAt = condition.size - if (not) 3 else 2
      val depth = items[case.start].depth
      if (isAt < 1 || !condition[isAt].isKeyword("IS") || condition.subList(isAt, condition.size).any { it.depth != depth }) continue
      val tested = condition.subList(0, isAt)
      val thenValue = items.subList(thenAt + 1, case.elseAt)
      val elseValue = items.subList(case.elseAt + 1, case.end)
      if (thenValue.isEmpty() || elseValue.isEmpty()) continue
      val (kept, fallback) = if (not) thenValue to elseValue else elseValue to thenValue
      if (SqlQueries.normalized(kept) != SqlQueries.normalized(tested)) continue
      val text = "COALESCE(${source(tested)}, ${source(fallback)})"
      register(holder, items[case.start].element, items[case.end].element, PhantomDbBundle.message("sql.inspection.case.coalesce"),
               ProblemHighlightType.WEAK_WARNING, SqlTextFix(PhantomDbBundle.message("sql.fix.replace.with", text), text))
    }
  }

  private fun source(items: List<SqlItem>): String {
    val file = items.first().element.containingFile.text
    return file.substring(items.first().element.textRange.startOffset, items.last().element.textRange.endOffset)
  }
}

/** `ELSE NULL` in a CASE: a CASE without ELSE already gives NULL. */
class SqlRedundantElseNullInspection : SqlInspection() {
  override fun checkQuery(query: SqlScope, items: List<SqlItem>, holder: ProblemsHolder) {
    for (case in SqlQueries.cases(items)) {
      val elseAt = case.elseAt ?: continue
      if (elseAt + 2 != case.end || !items[elseAt + 1].isKeyword("NULL")) continue
      register(holder, items[elseAt].element, items[elseAt + 1].element, PhantomDbBundle.message("sql.inspection.redundant.else.null"),
               ProblemHighlightType.LIKE_UNUSED_SYMBOL,
               SqlTextFix(PhantomDbBundle.message("sql.fix.remove.else.null"), "", SqlTextFix.Mode.DELETE_WITH_SPACE_BEFORE))
    }
  }
}

/** `SELECT name, count(*) FROM t` with no GROUP BY: `name` is neither grouped nor aggregated. */
class SqlShouldBeInGroupByInspection : SqlInspection() {
  override fun checkQuery(query: SqlScope, items: List<SqlItem>, holder: ProblemsHolder) {
    for (arm in SqlQueries.arms(items)) {
      if (arm.any { it.depth == 0 && it.isKeyword("GROUP") }) continue
      val list = SqlQueries.selectList(arm)?.flatten() ?: continue
      val aggregateRanges = ArrayList<IntRange>()
      for ((i, item) in list.withIndex()) {
        if (item.type != SqlTokens.IDENTIFIER || item.text.uppercase() !in SqlQueries.AGGREGATES) continue
        val open = list.getOrNull(i + 1)?.takeIf { it.type == SqlTokens.LPAREN && it.depth == item.depth } ?: continue
        val close = (i + 2 until list.size).firstOrNull { list[it].depth == open.depth && list[it].type == SqlTokens.RPAREN } ?: continue
        if (list.getOrNull(close + 1)?.isKeyword("OVER") == true) continue // a window function, not a grouping aggregate
        aggregateRanges.add(i..close)
      }
      if (aggregateRanges.isEmpty()) continue
      for ((i, item) in list.withIndex()) {
        val column = item.element as? SqlColumnRef ?: continue
        if (aggregateRanges.any { i in it }) continue
        holder.registerProblem(column, PhantomDbBundle.message("sql.inspection.group.by", column.columnName))
      }
    }
  }
}

/** MySQL: `FROM (SELECT ...)` needs an alias ("Every derived table must have its own alias"). */
class SqlDerivedTableAliasInspection : SqlInspection() {
  override fun checkQuery(query: SqlScope, items: List<SqlItem>, holder: ProblemsHolder) {
    if (items.none { it.element is SqlSubquery }) return
    val dbms = SqlSchema.dataSourceFor(holder.file, strict = false)?.dialect() ?: return
    if (dbms != Dbms.MYSQL && dbms != Dbms.MARIADB) return
    for ((index, item) in items.withIndex()) {
      val subquery = item.element as? SqlSubquery ?: continue
      if (!inTableList(items, index)) continue
      if (SqlQueries.aliasAfter(subquery) != null) continue
      holder.registerProblem(subquery, PhantomDbBundle.message("sql.inspection.derived.alias"),
                             SqlTextFix(PhantomDbBundle.message("sql.fix.add.alias"), " AS " + freeName(query), SqlTextFix.Mode.INSERT_AFTER))
    }
  }

  private fun inTableList(items: List<SqlItem>, index: Int): Boolean {
    val depth = items[index].depth
    var i = index - 1
    if (i >= 0 && items[i].isKeyword("LATERAL")) i--
    val previous = items.getOrNull(i) ?: return false
    if (previous.depth != depth) return false
    if (previous.isKeyword("FROM", "JOIN")) return true
    if (previous.type != SqlTokens.COMMA) return false
    // a comma in a FROM list: the clause keyword before it is FROM or a JOIN
    for (j in i - 1 downTo 0) {
      val it = items[j]
      if (it.depth != depth) continue
      if (it.isKeyword("FROM", "JOIN")) return true
      if (it.keyword in setOf("SELECT", "WHERE", "SET", "VALUES", "GROUP", "ORDER", "HAVING", "ON", "BY")) return false
    }
    return false
  }

  private fun freeName(query: SqlScope): String {
    val taken = PsiTreeUtil.findChildrenOfType(query, SqlDeclaration::class.java).map { it.name.lowercase() }.toSet()
    return generateSequence(1) { it + 1 }.map { if (it == 1) "derived" else "derived$it" }.first { it !in taken }
  }
}

/** A derived table or CTE returning an expression without a name, so the outer query cannot refer to it. */
class SqlMissingColumnAliasesInspection : SqlInspection() {
  override fun checkQuery(query: SqlScope, items: List<SqlItem>, holder: ProblemsHolder) {
    if (query !is SqlSubquery) return
    val cte = SqlQueries.cteOf(query)
    if (cte == null && SqlQueries.aliasAfter(query) == null) return
    // `WITH x (a, b) AS (...)` names the columns itself
    if (cte != null && PsiTreeUtil.skipWhitespacesAndCommentsForward(cte)?.node?.elementType == SqlTokens.LPAREN) return
    val list = SqlQueries.selectList(SqlQueries.arms(items).first()) ?: return
    for (entry in list) {
      if (entry.isEmpty() || entry.any { it.element is SqlColumnAlias }) continue
      val single = entry.singleOrNull()
      if (single != null && single.element is SqlColumnRef) continue
      if (entry.any { it.depth == 0 && it.isOperator("*") }) continue
      register(holder, entry.first().element, entry.last().element, PhantomDbBundle.message("sql.inspection.missing.column.alias"))
    }
  }
}
