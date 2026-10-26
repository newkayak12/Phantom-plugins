// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.exec

import com.intellij.openapi.project.Project
import dev.phantom.db.core.dialect
import dev.phantom.db.core.DbDataSource
import dev.phantom.db.core.Dbms
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.DatabaseMetaData
import java.sql.SQLException
import java.sql.Types

/** `schema.table` as written after FROM; [quoted] parts keep their case, the others follow the database's rules. */
data class DbTableName(val schema: String?, val table: String, val schemaQuoted: Boolean = false, val tableQuoted: Boolean = false)

/**
 * Finds the one table a `SELECT` reads from, when it reads from exactly one: no joins, no comma list, no subquery,
 * set operation, grouping or DISTINCT. Only then do the result rows map one to one onto table rows.
 */
object SingleTableSelect {
  private val IDENT = """(?:"[^"]+"|`[^`]+`|\[[^\]]+]|[A-Za-z_][\w$]*)"""
  private val FROM = Regex("""(?is)^\s*select\s+(.+?)\s+from\s+($IDENT(?:\s*\.\s*$IDENT)?)(\s+(?:as\s+)?(?!(?:where|order|limit|offset|fetch|for)\b)[A-Za-z_]\w*)?\s*(.*)$""")
  private val BLOCKERS = Regex("""(?i)\b(join|union|intersect|except|group\s+by|having|distinct|window|lateral)\b""")
  private val TAIL = Regex("""(?is)^(where\b.*|order\s+by\b.*|limit\b.*|offset\b.*|fetch\b.*|for\s+update\b.*)?;?\s*$""")

  fun parse(sql: String): DbTableName? {
    val text = stripComments(sql).trim().removeSuffix(";").trim()
    val match = FROM.matchEntire(text) ?: return null
    val (columns, name, _, tail) = match.destructured
    if (BLOCKERS.containsMatchIn(text) || '(' in name || columns.contains("(select", ignoreCase = true)) return null
    if (tail.isNotEmpty() && (!TAIL.matches(tail) || tail.contains("(select", ignoreCase = true))) return null
    val parts = splitQualified(name)
    return when (parts.size) {
      1 -> DbTableName(null, unquote(parts[0]), tableQuoted = isQuoted(parts[0]))
      2 -> DbTableName(unquote(parts[0]), unquote(parts[1]), isQuoted(parts[0]), isQuoted(parts[1]))
      else -> null
    }
  }

  /**
   * [sql] (a single-table select) with [where] added to its own WHERE (both kept, ANDed) and [orderBy], when not
   * blank, in place of its ORDER BY; LIMIT and the rest stay. Null when [sql] is not a single-table select.
   */
  fun refilter(sql: String, where: String, orderBy: String): String? {
    if (parse(sql) == null) return null
    val text = stripComments(sql).trim().removeSuffix(";").trim()
    val tail = FROM.matchEntire(text)!!.groups[4]
    val head = text.substring(0, tail?.range?.first ?: text.length).trim()
    val rest = tail?.value.orEmpty()
    val orderAt = topLevel(rest, Regex("""(?i)\border\s+by\b"""))
    val restAt = topLevel(rest, Regex("""(?i)\b(limit|offset|fetch|for\s+update)\b""")) ?: rest.length
    val ownWhere = DbTableQuery.clause(rest.substring(0, minOf(orderAt ?: rest.length, restAt)), "WHERE")
    val ownOrder = orderAt?.let { DbTableQuery.clause(rest.substring(it, maxOf(it, restAt)), "ORDER BY") }
    val newWhere = DbTableQuery.clause(where, "WHERE")
    // each typed part on lines of its own, so a `--` comment in it ends there
    val condition = listOfNotNull(ownWhere, newWhere).joinToString(" AND ") { "(\n$it\n)" }.ifEmpty { null }
    val order = DbTableQuery.clause(orderBy, "ORDER BY") ?: ownOrder
    return buildString {
      append(head)
      condition?.let { append(" WHERE ").append(it) }
      order?.let { append(" ORDER BY ").append(it).append('\n') }
      rest.substring(restAt).trim().takeIf { it.isNotEmpty() }?.let { append(' ').append(it) }
    }.trimEnd()
  }

  /** Where [keyword] first occurs in [text] outside quotes and parentheses. */
  private fun topLevel(text: String, keyword: Regex): Int? {
    var depth = 0
    var quote: Char? = null
    var i = 0
    while (i < text.length) {
      val c = text[i]
      when {
        quote != null -> if (c == quote) quote = null
        c == '\'' || c == '"' || c == '`' -> quote = c
        c == '(' -> depth++
        c == ')' -> depth--
        depth == 0 && (i == 0 || !text[i - 1].isLetterOrDigit() && text[i - 1] != '_') ->
          keyword.matchAt(text, i)?.let { return i }
      }
      i++
    }
    return null
  }

  private fun splitQualified(name: String): List<String> {
    val parts = ArrayList<String>()
    val current = StringBuilder()
    var quote: Char? = null
    for (c in name) {
      when {
        quote != null -> { current.append(c); if (c == quote || (quote == '[' && c == ']')) quote = null }
        c == '"' || c == '`' || c == '[' -> { quote = c; current.append(c) }
        c == '.' -> { parts.add(current.toString().trim()); current.clear() }
        else -> current.append(c)
      }
    }
    parts.add(current.toString().trim())
    return parts
  }

  private fun isQuoted(part: String): Boolean = part.firstOrNull() in setOf('"', '`', '[')

  private fun unquote(part: String): String = if (isQuoted(part)) part.substring(1, part.length - 1) else part

  internal fun stripComments(sql: String): String =
    sql.replace(Regex("""(?s)/\*.*?\*/"""), " ").lines().joinToString("\n") { it.replace(Regex("""--.*$"""), "") }
}

/**
 * Where edits of a result go: its table, which result columns are table columns (by index), and the primary key
 * columns, all of which are in the result. A result without its whole primary key is not editable.
 */
class DbEditTarget(
  val dbms: Dbms,
  /** Quoted and qualified as needed, ready to put in SQL. */
  val tableSql: String,
  val displayName: String,
  /** Quoted column name per result column, or null for an expression or alias that is not a table column. */
  val columnSql: List<String?>,
  val sqlTypes: List<Int>,
  val keyColumns: List<Int>,
) {
  fun isEditable(column: Int): Boolean = columnSql.getOrNull(column) != null

  /** `UPDATE t SET c = ? WHERE k = ?`, with [newValue] then the key values of [row]. */
  fun update(row: Array<Any?>, column: Int, newValue: Any?): DbStatement {
    val params = listOf(param(newValue, column)) + keyParams(row)
    return DbStatement("UPDATE $tableSql SET ${columnSql[column]} = ? WHERE ${keyCondition()}", params, dbms)
  }

  fun updateToDefault(row: Array<Any?>, column: Int): DbStatement =
    DbStatement("UPDATE $tableSql SET ${columnSql[column]} = DEFAULT WHERE ${keyCondition()}", keyParams(row), dbms)

  /** The column's value in the row with [row]'s key, as the database has it now. */
  fun selectValue(row: Array<Any?>, column: Int): DbStatement =
    DbStatement("SELECT ${columnSql[column]} FROM $tableSql WHERE ${keyCondition()}", keyParams(row), dbms)

  /**
   * The key of a row just inserted with [values] (grid values by column): the ones set, else the [generated] keys, by
   * column name or, for a one-column key and one generated value, that value. `null` when part of it is not known.
   */
  fun insertedKey(values: Map<Int, Any?>, generated: Map<String, Any?>): Array<Any?>? {
    val row = arrayOfNulls<Any?>(columnSql.size)
    for (k in keyColumns) {
      row[k] = when {
        values[k] != null -> values[k]
        else -> (generated.entries.firstOrNull { it.key.equals(columnSql[k]!!.trim('`', '"', '[', ']'), ignoreCase = true) }?.value
                  ?: generated.values.singleOrNull()?.takeIf { keyColumns.size == 1 })?.let(DbGridTime::toGrid)
                ?: return null
      }
    }
    return row
  }

  /** The table columns of the result in the row with [row]'s key, in [editableColumns] order: to show a new row as stored. */
  fun selectRow(row: Array<Any?>): DbStatement =
    DbStatement("SELECT ${editableColumns.joinToString { columnSql[it]!! }} FROM $tableSql WHERE ${keyCondition()}", keyParams(row), dbms)

  val editableColumns: List<Int> get() = columnSql.indices.filter { isEditable(it) }

  fun delete(row: Array<Any?>): DbStatement = DbStatement("DELETE FROM $tableSql WHERE ${keyCondition()}", keyParams(row), dbms)

  /** `INSERT INTO t (c, …) VALUES (?, …)` for the [values] set in a new grid row; the other columns take their defaults. */
  fun insert(values: Map<Int, Any?>): DbStatement {
    val columns = values.keys.filter { isEditable(it) }.sorted()
    if (columns.isEmpty()) {
      val sql = if (dbms.usesCatalogs) "INSERT INTO $tableSql () VALUES ()" else "INSERT INTO $tableSql DEFAULT VALUES"
      return DbStatement(sql, emptyList(), dbms, returnsKeys = true)
    }
    return DbStatement("INSERT INTO $tableSql (${columns.joinToString { columnSql[it]!! }}) VALUES (${columns.joinToString { "?" }})",
                       columns.map { param(values[it], it) }, dbms, returnsKeys = true)
  }

  /** An INSERT with [values] (or NULLs) for the table columns of the result, to finish in a console. */
  fun insertTemplate(values: Array<Any?>?): String {
    val columns = columnSql.indices.filter { isEditable(it) }
    val literals = columns.map { i -> if (values == null) "NULL" else DbStatement.literal(DbUnsetCells.stored(values[i]), dbms) }
    return "INSERT INTO $tableSql (${columns.joinToString { columnSql[it]!! }}) VALUES (${literals.joinToString()});"
  }

  private fun keyCondition(): String = keyColumns.joinToString(" AND ") { "${columnSql[it]} = ?" }

  private fun keyParams(row: Array<Any?>): List<DbParam> = keyColumns.map { param(row[it], it) }

  /** Values come from the grid, dates and times in its form. */
  private fun param(value: Any?, column: Int) = DbParam(DbGridTime.fromGrid(value), sqlTypes[column])
}

data class DbParam(val value: Any?, val sqlType: Int)

/** A prepared statement and its parameters; [display] is the SQL with the values written in, for people to read. */
class DbStatement(val sql: String, val params: List<DbParam>, private val dbms: Dbms,
                  /** An INSERT whose generated keys are wanted ([DbOutcome.Updated.generatedKeys]). */
                  val returnsKeys: Boolean = false) {
  val display: String
    get() {
      val values = params.iterator()
      return sql.replace(Regex("""\?""")) { if (values.hasNext()) literal(values.next().value, dbms) else "?" }
    }

  companion object {
    fun literal(value: Any?, dbms: Dbms): String = when (value) {
      null -> "NULL"
      is Boolean -> if (value) "TRUE" else "FALSE"
      is Double -> if (value.isNaN() || value.isInfinite()) "'$value'" else value.toString()
      is Float -> if (value.isNaN() || value.isInfinite()) "'$value'" else value.toString()
      is Number -> value.toString()
      is ByteArray -> {
        val hex = value.joinToString("") { "%02x".format(it) }
        if (dbms == Dbms.POSTGRESQL) "'\\x$hex'" else "X'$hex'"
      }
      // MySQL and MariaDB read backslash escapes in strings (unless NO_BACKSLASH_ESCAPES)
      else -> "'" + value.toString().let { if (dbms.usesCatalogs) it.replace("\\", "\\\\") else it }.replace("'", "''") + "'"
    }
  }
}

object DbEditTargets {
  /**
   * The edit target of a result, or null when its rows cannot be traced back to table rows: not a single-table select,
   * a read-only or dialect-less data source, a view or table without a primary key, or a key column missing from the result.
   * [schema]: the one the select ran in; an unqualified table is looked up there and edited with that qualifier.
   */
  suspend fun resolve(project: Project, dataSource: DbDataSource, rows: DbOutcome.Rows, schema: DbSchemaChoice? = null): DbEditTarget? {
    if (dataSource.readOnly || !dataSource.dialect().hasDialect) return null
    val parsed = SingleTableSelect.parse(rows.sql) ?: return null
    // the chosen name is the database's own spelling: no case folding
    val name = if (parsed.schema == null && schema != null && !schema.isDefault) parsed.copy(schema = schema.name, schemaQuoted = true) else parsed
    return try {
      DbConnectionService.getInstance(project).withConnection(dataSource, DbConnectionService.Purpose.METADATA) { connection ->
        withContext(Dispatchers.IO) {
          val md = connection.metaData
          val schema = name.schema?.let { normalize(md, it, name.schemaQuoted) }
          val table = normalize(md, name.table, name.tableQuoted)
          val (catalog, schemaPattern) = when {
            dataSource.dialect().usesCatalogs -> (schema ?: connection.catalog) to null
            else -> null to (schema ?: runCatching { connection.schema }.getOrNull())
          }
          val tableType = md.getTables(catalog, schemaPattern, table, null).use { rs ->
            generateSequence { if (rs.next()) rs.getString("TABLE_NAME") to rs.getString("TABLE_TYPE") else null }
              .firstOrNull { it.first == table }?.second
          } ?: return@withContext null
          if (!tableType.contains("TABLE", ignoreCase = true)) return@withContext null
          val keys = md.getPrimaryKeys(catalog, schemaPattern, table).use { rs ->
            generateSequence { if (rs.next()) rs.getString("COLUMN_NAME") else null }.toList()
          }
          val tableColumns = md.getColumns(catalog, schemaPattern, table, "%").use { rs ->
            generateSequence { if (rs.next()) rs.getString("TABLE_NAME") to rs.getString("COLUMN_NAME") else null }
              .filter { it.first == table }.map { it.second }.toList()
          }
          build(dataSource.dialect(), md.identifierQuoteString?.trim().orEmpty(), name, schema, table, keys, tableColumns, rows.columns)
        }
      }
    }
    catch (e: SQLException) {
      null
    }
  }

  /** Pure part of [resolve], for tests. */
  internal fun build(dbms: Dbms, quote: String, name: DbTableName, schema: String?, table: String, keys: List<String>,
                     tableColumns: List<String>, resultColumns: List<DbColumn>): DbEditTarget? {
    if (!dbms.hasDialect || keys.isEmpty()) return null
    fun q(identifier: String) = if (quote.isEmpty()) identifier else quote + identifier.replace(quote, quote + quote) + quote
    val byLowerName = tableColumns.groupBy { it.lowercase() }
    // a result column is a table column when its label names exactly one table column, ignoring case
    val columnNames = resultColumns.map { c -> tableColumns.firstOrNull { it == c.name } ?: byLowerName[c.name.lowercase()]?.singleOrNull() }
    if (columnNames.filterNotNull().size != columnNames.filterNotNull().toSet().size) return null
    val keyIndexes = keys.map { k -> columnNames.indexOf(k).takeIf { it >= 0 } ?: return null }
    val tableSql = (if (name.schema != null && schema != null) q(schema) + "." else "") + q(table)
    return DbEditTarget(dbms, tableSql, if (name.schema != null) "${name.schema}.${name.table}" else name.table,
                        columnNames.map { it?.let(::q) }, resultColumns.map { it.sqlType }, keyIndexes)
  }

  /** Unquoted identifiers are stored in the database's case: lower in PostgreSQL, upper in H2. */
  private fun normalize(md: DatabaseMetaData, identifier: String, quoted: Boolean): String = when {
    quoted -> identifier
    md.storesLowerCaseIdentifiers() -> identifier.lowercase()
    md.storesUpperCaseIdentifiers() -> identifier.uppercase()
    else -> identifier
  }

  /** The JDBC type to bind a value of [sqlType] with; types drivers cannot bind by number go as text. */
  internal fun bindType(sqlType: Int): Int = when (sqlType) {
    Types.OTHER, Types.JAVA_OBJECT, Types.DISTINCT, Types.STRUCT, Types.ARRAY -> Types.OTHER
    else -> sqlType
  }
}
