// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core

import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder

/**
 * `ALTER TABLE t ADD c INT NOT NULL` without a default fails on PostgreSQL and H2 once the table has rows. MySQL and
 * MariaDB fill the column with an implicit default, so there it is fine; with no data source the dialect is unknown
 * and nothing is reported.
 */
class SqlAddNotNullColumnInspection : SqlInspection() {
  override fun checkDdl(statement: SqlStatement, holder: ProblemsHolder) {
    val dbms = SqlSchema.dataSourceFor(holder.file, strict = false)?.dialect() ?: return
    if (dbms == Dbms.MYSQL || dbms == Dbms.MARIADB) return
    val items = SqlQueries.items(statement)
    if (items.firstOrNull()?.isKeyword("ALTER") != true || items.getOrNull(1)?.isKeyword("TABLE") != true) return
    val table = items.indexOfFirst { it.element is SqlTableRef }
    if (table < 0) return
    for (action in split(items, table + 1)) {
      if (action.firstOrNull()?.isKeyword("ADD") != true) continue
      var i = 1
      if (action.getOrNull(i)?.isKeyword("COLUMN") == true) i++
      if (action.getOrNull(i)?.isKeyword("IF") == true) i += 3 // IF NOT EXISTS
      val name = action.getOrNull(i) ?: continue
      if (name.type == SqlTokens.LPAREN || name.isKeyword(*NOT_COLUMNS) || name.text.uppercase() in NOT_COLUMNS) continue
      val rest = action.drop(i + 1)
      val notNull = rest.withIndex().any { (j, it) -> it.depth == 0 && it.isKeyword("NOT") && rest.getOrNull(j + 1)?.isKeyword("NULL") == true }
      if (!notNull || rest.any { it.text.uppercase() in FILLED }) continue
      register(holder, action.first().element, name.element, PhantomDbBundle.message("sql.inspection.add.not.null.column", SqlNames.unquote(name.text)))
    }
  }

  /** The comma-separated actions of an ALTER TABLE, from [start]. */
  private fun split(items: List<SqlItem>, start: Int): List<List<SqlItem>> {
    val result = ArrayList<List<SqlItem>>()
    var current = ArrayList<SqlItem>()
    for (i in start until items.size) {
      if (items[i].depth == 0 && items[i].type == SqlTokens.COMMA) {
        result.add(current)
        current = ArrayList()
      }
      else current.add(items[i])
    }
    result.add(current)
    return result
  }

  private companion object {
    val NOT_COLUMNS = arrayOf("CONSTRAINT", "PRIMARY", "UNIQUE", "INDEX", "KEY", "FOREIGN", "CHECK", "FULLTEXT", "SPATIAL", "PARTITION")
    /** Something gives the new rows a value: a default, a sequence, or a computed column. */
    val FILLED = setOf("DEFAULT", "AUTO_INCREMENT", "GENERATED", "IDENTITY", "AS", "SERIAL", "BIGSERIAL", "SMALLSERIAL", "SERIAL4", "SERIAL8")
  }
}

/**
 * Column types MySQL 8.0.17 deprecated: an integer display width (`INT(11)`; `TINYINT(1)` stays, it marks a boolean),
 * `ZEROFILL`, and `FLOAT(M,D)` / `DOUBLE(M,D)`. MySQL data sources only; MariaDB keeps them.
 */
class SqlDeprecatedTypeInspection : SqlInspection() {
  override fun checkDdl(statement: SqlStatement, holder: ProblemsHolder) {
    if (SqlSchema.dataSourceFor(holder.file, strict = false)?.dialect() != Dbms.MYSQL) return
    val items = SqlQueries.items(statement)
    for ((i, item) in items.withIndex()) {
      val word = item.text.uppercase()
      // ZEROFILL is not a keyword here, so the parser may have taken it for a column name
      if (word == "ZEROFILL") {
        holder.registerProblem(item.element, PhantomDbBundle.message("sql.inspection.deprecated.zerofill"), ProblemHighlightType.WEAK_WARNING)
        continue
      }
      if (item.type != SqlTokens.KEYWORD && item.type != SqlTokens.IDENTIFIER || word !in INTEGERS && word !in FLOATS) continue
      if (items.getOrNull(i - 1)?.type == SqlTokens.DOT || items.getOrNull(i + 1)?.type != SqlTokens.LPAREN) continue
      val close = SqlClauses.closing(items, i + 1) ?: continue
      val args = SqlClauses.arguments(items, i + 1, close).values
      if (args.any { it.singleOrNull()?.type != SqlTokens.NUMBER }) continue
      val message = when {
        word in INTEGERS && args.size == 1 -> {
          if (word == "TINYINT" && args[0].single().text == "1") continue
          PhantomDbBundle.message("sql.inspection.deprecated.display.width", word)
        }
        word in FLOATS && args.size == 2 -> PhantomDbBundle.message("sql.inspection.deprecated.float.precision", word)
        else -> continue
      }
      register(holder, items[i + 1].element, items[close].element, message, ProblemHighlightType.WEAK_WARNING,
               SqlTextFix(PhantomDbBundle.message("sql.fix.remove.type.arguments"), ""))
    }
  }

  private companion object {
    val INTEGERS = setOf("TINYINT", "SMALLINT", "MEDIUMINT", "INT", "INTEGER", "BIGINT")
    val FLOATS = setOf("FLOAT", "DOUBLE", "REAL")
  }
}

/** Two `AUTO_INCREMENT` columns in one CREATE TABLE: MySQL, MariaDB and H2 allow one per table. */
class SqlAutoIncrementDuplicateInspection : SqlInspection() {
  override fun checkDdl(statement: SqlStatement, holder: ProblemsHolder) {
    val items = SqlQueries.items(statement)
    if (items.firstOrNull()?.isKeyword("CREATE") != true || items.take(4).none { it.isKeyword("TABLE") }) return
    val table = items.indexOfFirst { it.element is SqlTableRef }
    if (table < 0) return
    val open = (table + 1 until items.size).firstOrNull { items[it].depth == 0 && items[it].type == SqlTokens.LPAREN } ?: return
    val close = SqlClauses.closing(items, open) ?: return
    val depth = items[open].depth + 1
    val autoColumns = SqlClauses.arguments(items, open, close).values.mapNotNull { definition ->
      definition.firstOrNull { it.depth == depth && it.text.equals("AUTO_INCREMENT", ignoreCase = true) }
    }
    for (extra in autoColumns.drop(1)) {
      holder.registerProblem(extra.element, PhantomDbBundle.message("sql.inspection.auto.increment.duplicate"))
    }
  }
}
