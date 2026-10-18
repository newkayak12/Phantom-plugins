// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.exec

import dev.phantom.db.core.Dbms

/**
 * The statements behind a table's data view: one page of rows with the typed filter and order, and the row count.
 * All four supported databases page with `LIMIT n OFFSET m`. The filter and order are SQL as typed, with or without
 * the leading `WHERE` / `ORDER BY`.
 */
object DbTableQuery {
  fun select(dbms: Dbms, table: DbTableRef, where: String, orderBy: String, limit: Int, offset: Int): String = buildString {
    append("SELECT * FROM ").append(DbNames.table(dbms, table))
    clause(where, "WHERE")?.let { append(" WHERE ").append(it) }
    clause(orderBy, "ORDER BY")?.let { append(" ORDER BY ").append(it) }
    append(" LIMIT ").append(limit)
    if (offset > 0) append(" OFFSET ").append(offset)
  }

  fun count(dbms: Dbms, table: DbTableRef, where: String): String =
    "SELECT COUNT(*) FROM " + DbNames.table(dbms, table) + (clause(where, "WHERE")?.let { " WHERE $it" }.orEmpty())

  /** [text] without a leading [keyword] and trailing `;`, or null when nothing is left. */
  internal fun clause(text: String, keyword: String): String? {
    var t = text.trim().removeSuffix(";").trim()
    val pattern = Regex("^" + keyword.replace(" ", "\\s+") + "\\b", RegexOption.IGNORE_CASE)
    t = t.replace(pattern, "").trim()
    return t.ifEmpty { null }
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
