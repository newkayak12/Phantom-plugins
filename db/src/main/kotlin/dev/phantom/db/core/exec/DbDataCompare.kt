// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.exec

import com.intellij.openapi.project.Project
import dev.phantom.db.core.DbDataSource
import dev.phantom.db.core.Dbms
import dev.phantom.db.core.PhantomDbBundle
import java.math.BigDecimal
import java.time.temporal.TemporalAccessor

/**
 * Two tables' rows compared: [columns] are the columns both have (left spelling, right spelling), [key] the indices of
 * the ones a row is matched by (the primary key, else all of them). [changed] rows have the same key and other values.
 */
class DbDataDiff(val columns: List<Pair<String, String>>, val key: List<Int>, val keyed: Boolean,
                 val onlyLeft: List<Array<Any?>>, val onlyRight: List<Array<Any?>>, val changed: List<Pair<Array<Any?>, Array<Any?>>>, val same: Long) {
  val identical: Boolean get() = onlyLeft.isEmpty() && onlyRight.isEmpty() && changed.isEmpty()
}

/**
 * Compare Content (DataGrip's, for two tables of the same or different data sources): the rows of both are read page by
 * page and matched by the primary key, else by every column (then a row is either in both or only in one). Values are
 * compared after making numbers, dates and bytes comparable across drivers. Up to [MAX_ROWS] rows of the left table
 * are held in memory.
 */
object DbDataCompare {
  const val MAX_ROWS: Int = 200_000

  suspend fun run(project: Project, left: DbDataSource, leftTable: DbTableRef, right: DbDataSource, rightTable: DbTableRef): DbDataDiff {
    val objects = DbObjectCache.getInstance(project)
    val leftColumns = objects.columnDetails(left, leftTable).map { it.name }
    val rightColumns = objects.columnDetails(right, rightTable).map { it.name }
    val columns = leftColumns.mapNotNull { l -> rightColumns.firstOrNull { it.equals(l, ignoreCase = true) }?.let { l to it } }
    if (columns.isEmpty()) throw DbException(PhantomDbBundle.message("db.data.compare.no.common", leftTable.name, rightTable.name))
    val pk = objects.primaryKey(left, leftTable).columns.ifEmpty { objects.primaryKey(right, rightTable).columns }
    val keyed = pk.isNotEmpty() && pk.all { k -> columns.any { it.first.equals(k, true) || it.second.equals(k, true) } }
    val key = if (keyed) pk.map { k -> columns.indexOfFirst { it.first.equals(k, true) || it.second.equals(k, true) } } else columns.indices.toList()
    fun keyOf(row: Array<Any?>) = key.map { normal(row[it]) }

    val waiting = LinkedHashMap<List<Any?>, ArrayDeque<Array<Any?>>>()
    var count = 0
    read(project, left, leftTable, columns.map { it.first }) { row ->
      if (++count > MAX_ROWS) throw DbException(PhantomDbBundle.message("db.data.compare.too.many", leftTable.name, MAX_ROWS))
      waiting.getOrPut(keyOf(row)) { ArrayDeque() }.add(row)
    }
    val onlyRight = ArrayList<Array<Any?>>()
    val changed = ArrayList<Pair<Array<Any?>, Array<Any?>>>()
    var same = 0L
    read(project, right, rightTable, columns.map { it.second }) { row ->
      val k = keyOf(row)
      val match = waiting[k]?.removeFirstOrNull()
      if (match != null && waiting[k]!!.isEmpty()) waiting.remove(k)
      when {
        match == null -> onlyRight += row
        match.indices.all { normal(match[it]) == normal(row[it]) } -> same++
        else -> changed += match to row
      }
    }
    return DbDataDiff(columns, key, keyed, waiting.values.flatten(), onlyRight, changed, same)
  }

  /** [table]'s rows, page by page, as the values of [names] in that order. */
  private suspend fun read(project: Project, dataSource: DbDataSource, table: DbTableRef, names: List<String>, onRow: (Array<Any?>) -> Unit) {
    DbTableExport.pages(project, dataSource, table, "", "") { page ->
      val indices = names.map { n -> page.columns.indexOfFirst { it.name.equals(n, ignoreCase = true) } }
      page.rows.forEach { row -> onRow(Array(indices.size) { i -> indices[i].let { if (it < 0) null else row[it] } }) }
    }
  }

  /** A value as it compares across drivers: numbers by value, dates and times by their ISO text, bytes by content. */
  internal fun normal(value: Any?): Any? = when (value) {
    null -> null
    is BigDecimal -> value.stripTrailingZeros().toPlainString()
    is Double, is Float -> if ((value as Number).toDouble().isFinite()) BigDecimal(value.toString()).stripTrailingZeros().toPlainString() else value.toString()
    is Number -> BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
    is java.sql.Timestamp -> value.toLocalDateTime().toString()
    is java.sql.Date -> value.toLocalDate().toString()
    is java.sql.Time -> value.toLocalTime().toString()
    is ByteArray -> value.joinToString("") { "%02x".format(it) }
    is TemporalAccessor -> value.toString()
    is Boolean -> value
    else -> value.toString()
  }

  /** The statements that make the right table hold the left one's rows: INSERT the missing, DELETE the extra, UPDATE the changed. */
  fun sync(diff: DbDataDiff, dbms: Dbms, table: String): List<String> {
    val names = diff.columns.map { DbNames.identifier(dbms, it.second) }
    fun where(row: Array<Any?>) = diff.key.joinToString(" AND ") { i ->
      names[i] + if (row[i] == null) " IS NULL" else " = " + DbStatement.literal(row[i], dbms)
    }
    val statements = ArrayList<String>()
    diff.onlyRight.forEach { statements += "DELETE FROM $table WHERE ${where(it)};" }
    diff.changed.forEach { (l, r) ->
      val set = diff.columns.indices.filter { normal(l[it]) != normal(r[it]) }
      statements += "UPDATE $table SET ${set.joinToString(", ") { names[it] + " = " + DbStatement.literal(l[it], dbms) }} WHERE ${where(r)};"
    }
    diff.onlyLeft.forEach { row ->
      statements += "INSERT INTO $table (${names.joinToString(", ")}) VALUES (${row.joinToString(", ") { DbStatement.literal(it, dbms) }});"
    }
    return statements
  }

  /** The rows of one side as text lines for the diff viewer, in key order with the other side's lines at the same places. */
  fun text(diff: DbDataDiff): Pair<String, String> {
    fun line(row: Array<Any?>) = row.joinToString(" | ") { cell(it) }
    fun sortKey(row: Array<Any?>) = diff.key.joinToString("\u0000") { cell(row[it]) }
    val header = diff.columns.joinToString(" | ") { it.first } to diff.columns.joinToString(" | ") { it.second }
    val entries = (diff.changed.map { (l, r) -> Triple(sortKey(l), line(l), line(r)) } +
                   diff.onlyLeft.map { Triple(sortKey(it), line(it), null) } +
                   diff.onlyRight.map { Triple(sortKey(it), null, line(it)) }).sortedBy { it.first }
    val left = StringBuilder(header.first).append('\n')
    val right = StringBuilder(header.second).append('\n')
    for ((_, l, r) in entries) {
      if (l != null) left.append(l).append('\n')
      if (r != null) right.append(r).append('\n')
    }
    return left.toString() to right.toString()
  }

  private fun cell(v: Any?): String = when (v) {
    null -> "NULL"
    is ByteArray -> "0x" + v.joinToString("") { "%02x".format(it) }
    else -> v.toString().replace("\r\n", "⏎").replace('\n', '⏎').replace('\r', '⏎')
  }
}
