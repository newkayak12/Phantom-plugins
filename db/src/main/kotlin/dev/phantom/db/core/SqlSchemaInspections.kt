// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core

import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import dev.phantom.db.core.exec.DbColumnDetail
import dev.phantom.db.core.exec.DbColumnInfo

/**
 * Inspections that need the database's columns. Silent unless the data source is known for sure and its schema is
 * loaded (or loads within the usual wait off the EDT); a table or column that cannot be found is the unknown-name
 * warning's business, not theirs.
 */
abstract class SqlSchemaInspection : SqlInspection() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
    val file = holder.file as? SqlFile ?: return PsiElementVisitor.EMPTY_VISITOR
    val dataSource = SqlSchema.dataSourceFor(file, strict = true) ?: return PsiElementVisitor.EMPTY_VISITOR
    if (SqlSchema.getInstance(file.project).snapshot(dataSource) == null) return PsiElementVisitor.EMPTY_VISITOR
    return super.buildVisitor(holder, isOnTheFly)
  }

  protected fun dbmsOf(element: PsiElement): Dbms? = SqlSchema.dataSourceFor(element.containingFile, strict = true)?.dbms

  /** The database column [ref] names, when it resolves to one. */
  protected fun columnOf(ref: SqlColumnRef): DbColumnInfo? =
    ((SqlResolver.column(ref) as? SqlResolution.Found)?.target as? SqlDbTarget)?.column

  protected fun tableOf(ref: SqlTableRef): SqlDbTarget? = (SqlResolver.table(ref) as? SqlResolution.Found)?.target as? SqlDbTarget
}

/** An unqualified column that more than one table of the query has. */
class SqlAmbiguousColumnInspection : SqlSchemaInspection() {
  override fun checkQuery(query: SqlScope, items: List<SqlItem>, holder: ProblemsHolder) {
    // USING and NATURAL joins merge the shared columns into one
    if (items.any { it.depth == 0 && it.isKeyword("USING", "NATURAL") }) return
    val tables = items.withIndex().filter { (i, it) ->
      it.element is SqlTableRef && items.getOrNull(i - 1)?.isKeyword("INTO") != true // the INSERT target is not read
    }.map { it.value.element as SqlTableRef }
    if (tables.size < 2) return
    val targets = tables.associateWith { tableOf(it) }
    val schema = SqlSchema.getInstance(query.project)
    val aliases = SqlScopes.direct(query, SqlColumnAlias::class.java).map { it.name.lowercase() }.toSet()
    for (item in items) {
      val ref = item.element as? SqlColumnRef ?: continue
      if (ref.qualifier != null || ref.isInsertColumn || ref.columnName.lowercase() in aliases) continue
      val owners = tables.filter { table ->
        val target = targets[table] ?: return@filter false
        schema.columns(target.dataSource, target.table)?.any { SqlNames.same(it.name, ref.columnName) } == true
      }
      if (owners.size < 2) continue
      val names = owners.map { it.alias?.name ?: it.tableName }
      val fixes = names.distinct().map { SqlTextFix(PhantomDbBundle.message("sql.fix.qualify", it), "$it.", SqlTextFix.Mode.INSERT_BEFORE) }
      holder.registerProblem(ref, PhantomDbBundle.message("sql.inspection.ambiguous.column", ref.columnName, names.joinToString(", ")),
                             *fixes.toTypedArray())
    }
  }
}

/** INSERT that leaves out a NOT NULL column with no default, or puts NULL into one. */
class SqlInsertNullIntoNotNullInspection : SqlSchemaInspection() {
  override fun checkStatement(statement: SqlStatement, holder: ProblemsHolder) {
    val insert = SqlQueries.insert(statement) ?: return
    if (insert.columns.isEmpty()) return
    val tableRef = insert.target ?: return
    val target = tableOf(tableRef) ?: return
    val details = SqlSchema.getInstance(statement.project).columnDetails(target.dataSource, target.table) ?: return
    val listed = insert.columns.map { it.name.lowercase() }.toSet()
    val mysql = target.dataSource.dbms == Dbms.MYSQL || target.dataSource.dbms == Dbms.MARIADB
    for (column in details) {
      if (column.name.lowercase() in listed || !required(column, mysql)) continue
      holder.registerProblem(tableRef, PhantomDbBundle.message("sql.inspection.insert.missing.not.null", column.name), ProblemHighlightType.GENERIC_ERROR_OR_WARNING)
    }
    for (row in insert.rows) {
      for ((i, value) in row.values.withIndex()) {
        val single = value.singleOrNull() ?: continue
        if (!single.isKeyword("NULL")) continue
        val name = insert.columns.getOrNull(i)?.name ?: continue
        val column = details.firstOrNull { SqlNames.same(it.name, name) } ?: continue
        if (column.nullable || column.autoIncrement) continue
        holder.registerProblem(single.element, PhantomDbBundle.message("sql.inspection.insert.null.not.null", column.name))
      }
    }
  }

  /** NOT NULL with nothing to fill it: no default, no auto-increment, and (MySQL) not an ENUM, whose first value is the default. */
  private fun required(column: DbColumnDetail, mysql: Boolean): Boolean =
    !column.nullable && column.default == null && !column.autoIncrement && !(mysql && column.typeName.startsWith("ENUM", ignoreCase = true))
}

/** A string literal longer than the VARCHAR/CHAR column it is written to. */
class SqlStringLengthExceededInspection : SqlSchemaInspection() {
  override fun checkStatement(statement: SqlStatement, holder: ProblemsHolder) {
    val mysql = dbmsOf(statement).let { it == Dbms.MYSQL || it == Dbms.MARIADB }
    SqlQueries.insert(statement)?.let { insert ->
      for (row in insert.rows) {
        for ((i, value) in row.values.withIndex()) {
          val literal = value.singleOrNull()?.takeIf { it.type == SqlTokens.STRING } ?: continue
          val column = (insert.columns.getOrNull(i)?.element as? SqlColumnRef)?.let(::columnOf) ?: continue
          check(literal.element, column, mysql, holder)
        }
      }
    }
    // UPDATE ... SET a = 'x', b = 'y'
    val items = SqlQueries.items(statement)
    val set = items.indexOfFirst { it.depth == 0 && it.isKeyword("SET") }
    if (set < 0 || items.take(set).none { it.depth == 0 && it.isKeyword("UPDATE") }) return
    for (i in set + 1 until items.size - 2) {
      val ref = items[i]
      if (ref.depth == 0 && ref.keyword in SET_END) break
      val (op, value) = items[i + 1] to items[i + 2]
      if (ref.depth != 0 || ref.element !is SqlColumnRef || !op.isOperator("=") || value.type != SqlTokens.STRING) continue
      // only `a = 'x'` as the whole assignment, not `a = 'x' || b`
      val after = items.getOrNull(i + 3)
      if (after != null && !(after.depth == 0 && (after.type == SqlTokens.COMMA || after.keyword in SET_END))) continue
      columnOf(ref.element)?.let { check(value.element, it, mysql, holder) }
    }
  }

  private fun check(literal: PsiElement, column: DbColumnInfo, mysql: Boolean, holder: ProblemsHolder) {
    val size = SIZED.find(column.typeName.trim())?.groupValues?.get(2)?.toIntOrNull() ?: return
    val text = SqlLiterals.content(literal.text, mysql) ?: return
    val length = text.codePointCount(0, text.length)
    if (length > size) {
      holder.registerProblem(literal, PhantomDbBundle.message("sql.inspection.string.length", length, column.typeName, column.name))
    }
  }

  private companion object {
    val SET_END = setOf("WHERE", "ORDER", "LIMIT", "RETURNING", "FROM")
    val SIZED =Regex("^(VARCHAR|CHAR|CHARACTER VARYING|CHARACTER|NVARCHAR|NCHAR|VARCHAR2)\\s*\\((\\d+)\\)", RegexOption.IGNORE_CASE)
  }
}

/** `amount = 'abc'`: a number column compared with text that is not a number. */
class SqlTypeMismatchInspection : SqlSchemaInspection() {
  private val comparisons = setOf("=", "<>", "!=", "<", ">", "<=", ">=", "<=>")

  override fun checkQuery(query: SqlScope, items: List<SqlItem>, holder: ProblemsHolder) {
    val mysql = dbmsOf(query).let { it == Dbms.MYSQL || it == Dbms.MARIADB }
    for (i in 1 until items.size - 1) {
      val op = items[i]
      if (op.type != SqlTokens.OPERATOR || op.text !in comparisons) continue
      val (left, right) = items[i - 1] to items[i + 1]
      val (ref, literal) = when {
        left.element is SqlColumnRef && right.type == SqlTokens.STRING -> left.element to right.element
        right.element is SqlColumnRef && left.type == SqlTokens.STRING -> right.element to left.element
        else -> continue
      }
      // `a = 'x' || b` and the like: the literal is part of a longer expression
      if (items.getOrNull(i + 2)?.let { it.type == SqlTokens.OPERATOR || it.type == SqlTokens.DOT } == true) continue
      val column = columnOf(ref as SqlColumnRef) ?: continue
      if (!NUMERIC.matches(column.typeName.trim())) continue
      val text = SqlLiterals.content(literal.text, mysql) ?: continue
      if (NUMBER.matches(text)) continue
      holder.registerProblem(literal, PhantomDbBundle.message("sql.inspection.type.mismatch", column.name, column.typeName), ProblemHighlightType.WEAK_WARNING)
    }
  }

  private companion object {
    val NUMERIC = Regex("^(TINYINT|SMALLINT|MEDIUMINT|INT|INTEGER|BIGINT|INT2|INT4|INT8|DECIMAL|NUMERIC|NUMBER|FLOAT|FLOAT4|FLOAT8|DOUBLE|DOUBLE PRECISION|REAL|SERIAL|BIGSERIAL|SMALLSERIAL)\\b.*",
                        RegexOption.IGNORE_CASE)
    val NUMBER = Regex("\\s*[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?\\s*")
  }
}

/** The text of a SQL string literal. */
object SqlLiterals {
  /** `'it''s'` → `it's`; `N'x'`, `E'x'` and (MySQL) backslash escapes too. Null for `$$...$$`, `X'..'` and `B'..'`. */
  fun content(literal: String, backslashEscapes: Boolean): String? {
    var text = literal
    val prefix = text.takeWhile { it != '\'' }
    if (prefix.length > 2 || prefix.any { it.uppercaseChar() in "XB" } || text.length < prefix.length + 2 || !text.endsWith("'")) return null
    val escapes = backslashEscapes || prefix.equals("E", ignoreCase = true)
    text = text.substring(prefix.length + 1, text.length - 1)
    val out = StringBuilder()
    var i = 0
    while (i < text.length) {
      val c = text[i]
      when {
        c == '\'' && i + 1 < text.length && text[i + 1] == '\'' -> { out.append('\''); i += 2 }
        escapes && c == '\\' && i + 1 < text.length -> { out.append(text[i + 1]); i += 2 }
        else -> { out.append(c); i++ }
      }
    }
    return out.toString()
  }
}
