// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.exec

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import dev.phantom.db.core.DbDataSource
import dev.phantom.db.core.Dbms
import dev.phantom.db.core.PhantomDbBundle
import dev.phantom.db.core.dialect
import java.io.Reader
import java.math.BigDecimal
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.sql.Savepoint
import java.sql.Types

/** How a CSV file is written. [header]: its first record names the columns. */
data class DbCsvFormat(val delimiter: Char = ',', val quote: Char = '"', val header: Boolean = true)

/**
 * Reads CSV records one at a time (RFC 4180): quoted fields may hold the delimiter, line breaks and doubled quotes. An
 * empty unquoted field is `null` (SQL NULL); a quoted empty one is the empty string.
 */
class DbCsvReader(private val reader: Reader, private val delimiter: Char, private val quote: Char) {
  private var peeked = -2
  /** The 1-based line the last record returned started on. */
  var line: Int = 0
    private set
  private var nextLine = 1

  private fun read(): Int = if (peeked != -2) peeked.also { peeked = -2 } else reader.read()

  private fun peek(): Int = if (peeked != -2) peeked else reader.read().also { peeked = it }

  /** The next record, `null` at the end. A blank line is skipped. */
  fun next(): List<String?>? {
    while (true) {
      line = nextLine
      var c = read()
      if (c == -1) return null
      if (c == '\n'.code || c == '\r'.code) {
        if (c == '\r'.code && peek() == '\n'.code) read()
        nextLine++
        continue
      }
      val fields = ArrayList<String?>()
      val field = StringBuilder()
      var quoted = false
      var wasQuoted = false
      while (true) {
        if (quoted) {
          when {
            c == -1 -> throw DbException(PhantomDbBundle.message("db.csv.unterminated", line))
            c == quote.code && peek() == quote.code -> {
              read()
              field.append(quote)
            }
            c == quote.code -> quoted = false
            else -> {
              if (c == '\n'.code) nextLine++
              field.append(c.toChar())
            }
          }
        }
        else when {
          c == -1 || c == '\n'.code || c == '\r'.code -> {
            fields += if (field.isEmpty() && !wasQuoted) null else field.toString()
            if (c == '\r'.code && peek() == '\n'.code) read()
            if (c != -1) nextLine++
            return fields
          }
          c == delimiter.code -> {
            fields += if (field.isEmpty() && !wasQuoted) null else field.toString()
            field.setLength(0)
            wasQuoted = false
          }
          c == quote.code && field.isEmpty() && !wasQuoted -> {
            quoted = true
            wasQuoted = true
          }
          else -> field.append(c.toChar())
        }
        c = read()
      }
    }
  }

  companion object {
    fun readAll(text: String, format: DbCsvFormat): List<List<String?>> {
      val r = DbCsvReader(text.reader(), format.delimiter, format.quote)
      return generateSequence { r.next() }.toList()
    }
  }
}

object DbCsv {
  private val DELIMITERS = listOf(',', ';', '\t', '|')

  /**
   * Guesses the format from the start of a file: the delimiter that splits the first records into the same number (> 1) of
   * fields, the quote in use, and whether the first record is a header (names, no numbers, distinct, and either naming a
   * column of [columns] or sitting over a record with numbers).
   */
  fun detect(sample: String, columns: List<String> = emptyList()): DbCsvFormat {
    val quote = if (!sample.contains('"') && Regex("(^|[,;\\t|])'").containsMatchIn(sample)) '\'' else '"'
    val delimiter = DELIMITERS.maxByOrNull { d -> score(sample, d, quote) } ?: ','
    val records = runCatching { DbCsvReader(sample.reader(), delimiter, quote).let { r -> generateSequence { r.next() }.take(3).toList() } }.getOrDefault(emptyList())
    val first = records.firstOrNull().orEmpty()
    val names = first.filterNotNull().map { it.trim() }
    val looksLikeNames = names.size == first.size && names.all { it.isNotEmpty() && !isNumber(it) } && names.distinctBy { it.lowercase() }.size == names.size
    val second = records.getOrNull(1).orEmpty()
    val header = looksLikeNames && (names.any { n -> columns.any { normalize(it) == normalize(n) } } || second.any { it != null && isNumber(it.trim()) } || records.size == 1)
    return DbCsvFormat(delimiter, quote, header)
  }

  private fun score(sample: String, delimiter: Char, quote: Char): Int {
    val records = runCatching { DbCsvReader(sample.reader(), delimiter, quote).let { r -> generateSequence { r.next() }.take(10).toList() } }.getOrDefault(emptyList())
    // a last record cut off by the end of the sample says nothing
    val complete = sample.endsWith('\n') || sample.endsWith('\r')
    val whole = if (records.size > 1 && !complete) records.dropLast(1) else records
    val sizes = whole.map { it.size }.distinct()
    val size = whole.firstOrNull()?.size ?: return 0
    if (size < 2) return 0
    return if (sizes.size == 1) 1000 + size else size
  }

  private fun isNumber(s: String): Boolean = s.toBigDecimalOrNull() != null

  private fun normalize(name: String): String = name.lowercase().filter { it.isLetterOrDigit() }

  /**
   * For each table column, the CSV column it is filled from, or `null`. With a header by name (case, `_` and spaces do not
   * matter), else by position.
   */
  fun mapping(headers: List<String>?, csvColumns: Int, tableColumns: List<String>): List<Int?> =
    if (headers != null) tableColumns.map { c -> headers.indexOfFirst { normalize(it) == normalize(c) }.takeIf { it >= 0 } }
    else tableColumns.indices.map { it.takeIf { i -> i < csvColumns } }
}

/** What an import did. */
data class DbCsvResult(val rows: Long, val pending: Boolean)

/**
 * Inserts CSV records into a table: one prepared INSERT, sent in batches of [BATCH], in one transaction; a bad record
 * rolls everything back and names its line. In a manual-commit data source the rows join the open transaction on the
 * console connection (a failure rolls back to before the import only) and wait in Database Changes like any statement;
 * otherwise the import commits itself at the end.
 */
object DbCsvImport {
  const val BATCH: Int = 500

  /** [mapping]: for each of [columns] (the table's columns, in order), its CSV column; unmapped columns are left out. */
  suspend fun run(project: Project, dataSource: DbDataSource, table: DbTableRef, columns: List<String>, mapping: List<Int?>,
                  records: DbCsvReader, header: Boolean, indicator: ProgressIndicator?, label: String): DbCsvResult {
    if (dataSource.readOnly) throw DbException(PhantomDbBundle.message("db.csv.read.only", dataSource.name))
    val used = columns.indices.filter { mapping.getOrNull(it) != null }
    if (used.isEmpty()) throw DbException(PhantomDbBundle.message("db.csv.no.columns"))
    val dialect = dataSource.dialect()
    val sql = insert(dialect, table, used.map { columns[it] })
    return DbConnectionService.getInstance(project).withConnection(dataSource, DbConnectionService.Purpose.CONSOLE) { c ->
      val manual = !c.autoCommit
      // first the savepoint: whatever fails after it (even reading the column types) leaves the open transaction as it was
      val savepoint: Savepoint? = if (manual) c.setSavepoint() else null
      val types = types(c, dialect, table, used.map { columns[it] }) ?: run {
        // a failed statement aborts a PostgreSQL transaction until rolled back
        if (savepoint != null) runCatching { c.rollback(savepoint) }
        IntArray(used.size) { Types.VARCHAR }
      }
      if (!manual) c.autoCommit = false
      var count = 0L
      try {
        c.prepareStatement(sql).use { ps ->
          if (header) records.next()
          var inBatch = 0
          val lines = ArrayList<Int>(BATCH)
          while (true) {
            indicator?.checkCanceled()
            val record = records.next() ?: break
            used.forEachIndexed { i, column ->
              val value = record.getOrNull(mapping[column]!!)
              bind(ps, i + 1, value, types[i], records.line)
            }
            ps.addBatch()
            lines += records.line
            inBatch++
            count++
            if (inBatch == BATCH) {
              flush(ps, lines)
              inBatch = 0
              indicator?.text2 = PhantomDbBundle.message("db.csv.progress", count)
            }
          }
          if (inBatch > 0) flush(ps, lines)
        }
        if (manual) {
          savepoint?.let { runCatching { c.releaseSavepoint(it) } }
          DbPendingChanges.getInstance(project).afterStatement(dataSource, "INSERT INTO ${DbNames.table(dialect, table)} … $label",
                                                                listOf(DbOutcome.Updated("INSERT", count, 0)))
        }
        else c.commit()
        DbCsvResult(count, manual)
      }
      catch (e: Throwable) {
        if (manual) savepoint?.let { runCatching { c.rollback(it) } } else runCatching { c.rollback() }
        throw e
      }
      finally {
        if (!manual) runCatching { c.autoCommit = true }
      }
    }
  }

  private fun flush(ps: PreparedStatement, lines: MutableList<Int>) {
    try {
      ps.executeBatch()
    }
    catch (e: SQLException) {
      // a BatchUpdateException tells which one failed (drivers that go on) or how many went in before it (those that stop)
      val counts = (e as? java.sql.BatchUpdateException)?.updateCounts
      val failed = counts?.let { c -> c.indexOfFirst { it == java.sql.Statement.EXECUTE_FAILED }.takeIf { it >= 0 } ?: c.size.takeIf { it < lines.size } }
      val line = failed?.let { lines.getOrNull(it) }
      val message = DbExecutor.message(e)
      throw DbException(if (line != null) PhantomDbBundle.message("db.csv.failed.line", line, message)
                        else PhantomDbBundle.message("db.csv.failed.batch", lines.first(), lines.last(), message), e)
    }
    lines.clear()
  }

  internal fun insert(dialect: Dbms, table: DbTableRef, columns: List<String>): String {
    val qualifier = if (dialect.usesCatalogs) table.catalog else table.schema
    val name = qualifier?.let { DbNames.identifier(dialect, it) + "." }.orEmpty() + DbNames.identifier(dialect, table.name)
    return "INSERT INTO $name (${columns.joinToString { DbNames.identifier(dialect, it) }}) VALUES (${columns.joinToString { "?" }})"
  }

  /** JDBC types of the columns, so text becomes what the column takes (PostgreSQL does not cast text parameters). */
  private fun types(c: Connection, dialect: Dbms, table: DbTableRef, columns: List<String>): IntArray? {
    val qualifier = if (dialect.usesCatalogs) table.catalog else table.schema
    val name = qualifier?.let { DbNames.identifier(dialect, it) + "." }.orEmpty() + DbNames.identifier(dialect, table.name)
    return runCatching {
      c.createStatement().use { s ->
        s.executeQuery("SELECT ${columns.joinToString { DbNames.identifier(dialect, it) }} FROM $name WHERE 1 = 0").use { rs ->
          IntArray(columns.size) { rs.metaData.getColumnType(it + 1) }
        }
      }
    }.getOrNull()
  }

  internal fun bind(ps: PreparedStatement, index: Int, text: String?, type: Int, line: Int) {
    if (text == null) return ps.setNull(index, type)
    val value: Any = try {
      convert(text, type)
    }
    catch (e: Exception) {
      throw DbException(PhantomDbBundle.message("db.csv.bad.value", line, text, e.message ?: e.javaClass.simpleName))
    }
    ps.setObject(index, value)
  }

  internal fun convert(text: String, type: Int): Any {
    val t = text.trim()
    return when (type) {
      Types.TINYINT, Types.SMALLINT, Types.INTEGER -> t.toInt()
      Types.BIGINT -> t.toLong()
      Types.DECIMAL, Types.NUMERIC -> BigDecimal(t)
      Types.REAL -> t.toFloat()
      // JDBC FLOAT is a double
      Types.FLOAT, Types.DOUBLE -> t.toDouble()
      Types.BIT, Types.BOOLEAN -> when (t.lowercase()) {
        "true", "t", "1", "yes", "y" -> true
        "false", "f", "0", "no", "n" -> false
        else -> throw IllegalArgumentException("not a boolean")
      }
      Types.DATE -> java.sql.Date.valueOf(t)
      Types.TIME -> java.sql.Time.valueOf(t)
      Types.TIMESTAMP -> java.sql.Timestamp.valueOf(t.replace('T', ' '))
      else -> text
    }
  }
}
