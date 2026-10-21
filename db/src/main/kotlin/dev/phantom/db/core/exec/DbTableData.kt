// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.exec

import dev.phantom.db.core.Dbms
import dev.phantom.db.core.dialect

/** How a database pages: `LIMIT n OFFSET m`, or the standard `OFFSET m ROWS FETCH NEXT n ROWS ONLY` (SQL Server, Oracle). */
enum class DbPaging {
  LIMIT,
  FETCH,
  /** SQL Server: OFFSET needs an ORDER BY, so a page without one is ordered by nothing in particular. */
  FETCH_ORDERED;

  companion object {
    /** By dialect, or for a data source without one by its URL: SQL Server and Oracle take FETCH, the rest LIMIT. */
    fun of(dataSource: dev.phantom.db.core.DbDataSource): DbPaging {
      val url = dataSource.url.trim().lowercase()
      return when {
        dataSource.dialect().hasDialect -> LIMIT
        url.startsWith("jdbc:sqlserver:") || url.startsWith("jdbc:jtds:sqlserver:") -> FETCH_ORDERED
        url.startsWith("jdbc:oracle:") -> FETCH
        else -> LIMIT
      }
    }
  }
}

/**
 * The statements behind a table's data view: one page of rows with the typed filter and order, and the row count.
 * The filter and order are SQL as typed, with or without the leading `WHERE` / `ORDER BY`; each goes on lines of its
 * own (the filter in parentheses), so a `--` comment in it cannot swallow what follows.
 */
object DbTableQuery {
  fun select(dbms: Dbms, table: DbTableRef, where: String, orderBy: String, limit: Int, offset: Int, paging: DbPaging = DbPaging.LIMIT): String = buildString {
    append("SELECT * FROM ").append(DbNames.table(dbms, table))
    clause(where, "WHERE")?.let { append(" WHERE (\n").append(it).append("\n)") }
    val order = clause(orderBy, "ORDER BY")
    when (paging) {
      DbPaging.LIMIT -> {
        order?.let { append(" ORDER BY ").append(it).append('\n') }
        append(" LIMIT ").append(limit)
        if (offset > 0) append(" OFFSET ").append(offset)
      }
      DbPaging.FETCH, DbPaging.FETCH_ORDERED -> {
        if (order != null) append(" ORDER BY ").append(order).append('\n')
        else if (paging == DbPaging.FETCH_ORDERED) append(" ORDER BY (SELECT NULL)")
        append(" OFFSET ").append(offset).append(" ROWS FETCH NEXT ").append(limit).append(" ROWS ONLY")
      }
    }
  }

  fun count(dbms: Dbms, table: DbTableRef, where: String): String =
    "SELECT COUNT(*) FROM " + DbNames.table(dbms, table) + (clause(where, "WHERE")?.let { " WHERE (\n$it\n)" }.orEmpty())

  /** [text] without a leading [keyword] and trailing `;`, or null when nothing is left. */
  internal fun clause(text: String, keyword: String): String? {
    var t = text.trim().removeSuffix(";").trim()
    val pattern = Regex("^" + keyword.replace(" ", "\\s+") + "\\b", RegexOption.IGNORE_CASE)
    t = t.replace(pattern, "").trim()
    return t.ifEmpty { null }
  }

  /** Why the typed filter or order cannot be used, or null: a `;` that would end the statement and start another. */
  fun problem(vararg fragments: String): String? =
    fragments.firstOrNull { breaksStatement(it.trim().removeSuffix(";")) }?.let { dev.phantom.db.core.PhantomDbBundle.message("db.data.semicolon") }

  /** A `;` outside quotes and comments. */
  internal fun breaksStatement(text: String): Boolean {
    var i = 0
    while (i < text.length) {
      val c = text[i]
      when {
        c == '\'' || c == '"' || c == '`' -> {
          i = text.indexOf(c, i + 1).let { if (it < 0) text.length else it }
        }
        c == '-' && text.startsWith("--", i) -> i = text.indexOf('\n', i).let { if (it < 0) text.length else it }
        c == '/' && text.startsWith("/*", i) -> i = text.indexOf("*/", i + 2).let { if (it < 0) text.length else it + 1 }
        c == ';' -> return true
      }
      i++
    }
    return false
  }

  /** The ORDER BY after a click on [column]'s header: the column, then the column descending, then none. */
  fun nextOrder(dbms: Dbms, column: String, current: String): String {
    val name = DbNames.identifier(dbms, column)
    val now = (clause(current, "ORDER BY") ?: "").replace(Regex("\\s+"), " ")
    return when {
      now.equals(name, ignoreCase = true) || now.equals("$name ASC", ignoreCase = true) -> "$name DESC"
      now.equals("$name DESC", ignoreCase = true) -> ""
      else -> name
    }
  }

  /** The offset of the last page for [count] rows. */
  fun lastPageOffset(count: Long, pageSize: Int): Int = if (count <= 0) 0 else (((count - 1) / pageSize) * pageSize).toInt()
}

/**
 * Following a foreign key from a row: the filter that selects the rows on the other side. [valueOf] gives the row's
 * value for a column name (any case), or [Missing] when the result does not have that column.
 */
object DbForeignKeyNavigation {
  object Missing

  /** The referenced row: `ref_col = <value of fk col>` for each column pair. */
  fun referenced(dbms: Dbms, fk: DbForeignKey, valueOf: (String) -> Any?): String? =
    condition(dbms, fk.refColumns.zip(fk.columns), valueOf)

  /** Rows of [fk]'s table that reference this row: `fk_col = <value of referenced col>`. */
  fun referencing(dbms: Dbms, fk: DbForeignKey, valueOf: (String) -> Any?): String? =
    condition(dbms, fk.columns.zip(fk.refColumns), valueOf)

  private fun condition(dbms: Dbms, pairs: List<Pair<String, String>>, valueOf: (String) -> Any?): String? {
    val parts = pairs.map { (target, source) ->
      val value = valueOf(source)
      if (value === Missing) return null
      DbNames.quote(dbms, target) + if (value == null) " IS NULL" else " = " + DbStatement.literal(value, dbms)
    }
    return parts.joinToString(" AND ").ifEmpty { null }
  }
}
