// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.exec

import com.intellij.openapi.project.Project
import dev.phantom.db.core.DbDataSource
import dev.phantom.db.core.Dbms
import dev.phantom.db.core.dialect
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A table with rows that hold the searched text: how many, and the WHERE that shows them. */
data class DbFullTextHit(val table: DbTableRef, val count: Long, val where: String)

/** What a search found, and the tables it could not search (with the database's message). */
data class DbFullTextResult(val hits: List<DbFullTextHit>, val failed: List<Pair<DbTableRef, String>>, val searched: Int)

/**
 * Full-text Search (DataGrip's, on a data source or schema): every table's text columns are searched for the text,
 * ignoring case, with one COUNT per table; a number is also matched exactly in the numeric columns. Views are not
 * searched (they repeat their tables' rows and can be slow).
 */
object DbFullTextSearch {
  private val TEXT = Regex("(?i)char|text|clob|string|enum|set\\b|citext|name")
  private val NOT_TEXT = Regex("(?i)binary|blob|bytea|bit")
  private val NUMERIC = Regex("(?i)int|decimal|numeric|number|float|double|real|serial|money")

  suspend fun run(project: Project, dataSource: DbDataSource, schemas: List<DbSchemaRef>, text: String,
                  progress: (DbTableRef, Int, Int) -> Unit = { _, _, _ -> }): DbFullTextResult {
    val cache = DbMetadataCache.getInstance(project)
    val dbms = dataSource.dialect()
    val tables = schemas.flatMap { runCatching { cache.tables(dataSource, it) }.getOrDefault(emptyList()) }.filterNot { it.isView }
    val hits = mutableListOf<DbFullTextHit>()
    val failed = mutableListOf<Pair<DbTableRef, String>>()
    tables.forEachIndexed { i, table ->
      currentCoroutineContext().ensureActive()
      progress(table, i, tables.size)
      val columns = runCatching { cache.columns(dataSource, table) }.getOrElse { failed += table to (it.message ?: it.javaClass.simpleName); return@forEachIndexed }
      val where = condition(dbms, columns, text) ?: return@forEachIndexed
      val sql = "SELECT COUNT(*) FROM ${DbNames.table(dbms, table)} WHERE $where"
      when (val outcome = DbExecutor.execute(project, dataSource, listOf(sql), history = false).single()) {
        is DbOutcome.Rows -> (outcome.rows.firstOrNull()?.firstOrNull() as? Number)?.toLong()?.takeIf { it > 0 }?.let { hits += DbFullTextHit(table, it, where) }
        is DbOutcome.Failed -> failed += table to outcome.message
        is DbOutcome.Updated -> {}
      }
    }
    return DbFullTextResult(hits, failed, tables.size)
  }

  /** The WHERE matching [text] in [columns]: a case-insensitive LIKE on text columns, = on numeric ones for a number; null when no column can hold it. */
  internal fun condition(dbms: Dbms, columns: List<DbColumnInfo>, text: String): String? {
    val pattern = DbStatement.literal("%" + text.uppercase().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%", dbms)
    val number = text.trim().toBigDecimalOrNull()
    val parts = columns.mapNotNull { c ->
      val name = DbNames.identifier(dbms, c.name)
      when {
        TEXT.containsMatchIn(c.typeName) && !NOT_TEXT.containsMatchIn(c.typeName) -> "UPPER($name) LIKE $pattern ESCAPE '!'"
        number != null && NUMERIC.containsMatchIn(c.typeName) -> "$name = ${number.toPlainString()}"
        else -> null
      }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" OR ")
  }
}
