// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.exec

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import dev.phantom.db.core.DbDataSource
import dev.phantom.db.core.Dbms
import dev.phantom.db.core.PhantomDbBundle
import dev.phantom.db.core.dialect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.awt.Component
import java.awt.datatransfer.StringSelection
import java.nio.file.Files
import java.nio.file.Path
import java.time.temporal.TemporalAccessor
import java.util.Base64

/**
 * Result rows as text: TSV, CSV (RFC 4180), JSON, SQL INSERT statements (one per row or one for all), SQL UPDATE
 * statements, the values on one line as SQL literals (for an IN list), a Markdown, HTML or XML table, or a text table.
 * Excel is a file only: its text is the worksheet, which [DbXlsx] puts in the workbook.
 */
enum class DbExportFormat(val title: String, val extension: String) {
  TSV("TSV", "tsv"), CSV("CSV", "csv"), JSON("JSON", "json"), INSERT("SQL INSERT", "sql"), INSERT_MULTIROW("SQL INSERT Multirow", "sql"),
  UPDATE("SQL UPDATE", "sql"), ONE_ROW("One-Row", "txt"), MARKDOWN("Markdown", "md"), HTML("HTML", "html"), XML("XML", "xml"),
  PRETTY("Pretty", "txt"), XLSX("Excel (xlsx)", "xlsx");

  /** Whether the rows can go to the clipboard as this text (Excel is a file). */
  val text: Boolean get() = this != XLSX

  /** Whether the text names the table the rows go to (INSERT, UPDATE). */
  val needsTable: Boolean get() = this == INSERT || this == INSERT_MULTIROW || this == UPDATE

  /** [table]: the INSERT / UPDATE target as SQL, quoted as needed; `table_name` when unknown. [keys]: the columns an UPDATE finds a row by, all when empty. */
  fun render(rows: DbOutcome.Rows, table: String?, dbms: Dbms, keys: List<Int> = emptyList()): String {
    val out = StringBuilder()
    val writer = writer(out, rows.columns, table, dbms, keys)
    rows.rows.forEach(writer::row)
    writer.finish()
    return out.toString()
  }

  /** Writes rows one at a time, so a whole table can go to a file without holding it in memory (but Pretty, which lines up every row). */
  fun writer(out: Appendable, columns: List<DbColumn>, table: String?, dbms: Dbms, keys: List<Int> = emptyList()): DbExportWriter =
    DbExportWriter(this, out, columns, table, dbms, keys)

  companion object {
    /** [table] as it was written in a select: quoted parts quoted again, the others as typed (quoted only if they must be). */
    fun tableSql(dbms: Dbms, table: DbTableName?): String? = table?.let {
      fun part(name: String, quoted: Boolean) = if (quoted) DbNames.quoted(dbms, name) else DbNames.quote(dbms, name)
      listOfNotNull(it.schema?.let { s -> part(s, it.schemaQuoted) }, part(it.table, it.tableQuoted)).joinToString(".")
    }
  }
}

class DbExportWriter internal constructor(private val format: DbExportFormat, private val out: Appendable, private val columns: List<DbColumn>,
                                          table: String?, private val dbms: Dbms, keys: List<Int> = emptyList()) {
  var count: Long = 0
    private set

  private val target = table ?: "table_name"
  /** The result's column names as the database reported them, quoted where they would read differently unquoted. */
  private val names by lazy { columns.joinToString(", ") { DbNames.identifier(dbms, it.name) } }
  /** The columns an UPDATE's WHERE matches: the key, else every column. */
  private val keys = keys.filter { it in columns.indices }.ifEmpty { columns.indices.toList() }
  /** Pretty's cells, header first, written when every width is known; a column is right aligned while all its values are numbers. */
  private val pretty = mutableListOf<List<String>>()
  private val numeric = BooleanArray(columns.size) { true }

  init {
    when (format) {
      DbExportFormat.TSV -> header('\t')
      DbExportFormat.CSV -> header(',')
      DbExportFormat.JSON -> out.append("[")
      DbExportFormat.INSERT, DbExportFormat.INSERT_MULTIROW, DbExportFormat.UPDATE, DbExportFormat.ONE_ROW -> {}
      DbExportFormat.XML -> out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<data>\n")
      DbExportFormat.PRETTY -> pretty.add(columns.map { it.name })
      DbExportFormat.XLSX -> {
        out.append("<sheetData>")
        DbXlsx.row(out, 1, columns.map { it.name }.toTypedArray())
      }
      DbExportFormat.MARKDOWN -> {
        out.append("| ").append(columns.joinToString(" | ") { md(it.name) }).append(" |\n")
        out.append("|").append(columns.joinToString("|") { " --- " }).append("|\n")
      }
      DbExportFormat.HTML -> {
        out.append("<table>\n  <tr>")
        columns.forEach { out.append("<th>").append(html(it.name)).append("</th>") }
        out.append("</tr>\n")
      }
    }
  }

  fun row(values: Array<Any?>) {
    when (format) {
      DbExportFormat.TSV -> delimited(values.map(::cell), '\t')
      DbExportFormat.CSV -> delimited(values.map(::cell), ',')
      DbExportFormat.JSON -> {
        out.append(if (count == 0L) "\n  {" else ",\n  {")
        columns.forEachIndexed { i, c ->
          if (i > 0) out.append(", ")
          out.append(quote(c.name)).append(": ").append(jsonValue(values[i]))
        }
        out.append("}")
      }
      DbExportFormat.INSERT -> out.append("INSERT INTO ").append(target).append(" (").append(names).append(") VALUES (")
        .append(values.joinToString(", ") { DbStatement.literal(it, dbms) }).append(");\n")
      DbExportFormat.INSERT_MULTIROW -> {
        if (count == 0L) out.append("INSERT INTO ").append(target).append(" (").append(names).append(")\nVALUES ") else out.append(",\n       ")
        out.append("(").append(values.joinToString(", ") { DbStatement.literal(it, dbms) }).append(")")
      }
      DbExportFormat.UPDATE -> {
        val set = columns.indices.filter { it !in keys }.ifEmpty { columns.indices.toList() }
        out.append("UPDATE ").append(target).append(" SET ")
          .append(set.joinToString(", ") { "${DbNames.identifier(dbms, columns[it].name)} = ${DbStatement.literal(values[it], dbms)}" })
          .append(" WHERE ")
          .append(keys.joinToString(" AND ") {
            DbNames.identifier(dbms, columns[it].name) + if (values[it] == null) " IS NULL" else " = ${DbStatement.literal(values[it], dbms)}"
          }).append(";\n")
      }
      DbExportFormat.ONE_ROW -> values.forEachIndexed { i, v ->
        if (count > 0 || i > 0) out.append(", ")
        out.append(DbStatement.literal(v, dbms))
      }
      DbExportFormat.XML -> {
        out.append("  <row>\n")
        columns.forEachIndexed { i, c ->
          out.append("    <value column=\"").append(xml(c.name)).append('"')
          if (values[i] == null) out.append(" null=\"true\"/>\n") else out.append(">").append(xml(cell(values[i]))).append("</value>\n")
        }
        out.append("  </row>\n")
      }
      DbExportFormat.PRETTY -> {
        values.forEachIndexed { i, v -> if (v != null && v !is Number) numeric[i] = false }
        pretty.add(values.map { if (it == null) "NULL" else cell(it).replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ') })
      }
      DbExportFormat.XLSX -> DbXlsx.row(out, count + 2, values)
      DbExportFormat.MARKDOWN -> out.append("| ").append(values.joinToString(" | ") { md(if (it == null) "NULL" else cell(it)) }).append(" |\n")
      DbExportFormat.HTML -> {
        out.append("  <tr>")
        values.forEach { out.append("<td>").append(html(cell(it)).replace("\n", "<br>")).append("</td>") }
        out.append("</tr>\n")
      }
    }
    count++
  }

  fun finish() {
    when (format) {
      DbExportFormat.JSON -> out.append(if (count == 0L) "]" else "\n]")
      DbExportFormat.HTML -> out.append("</table>\n")
      DbExportFormat.INSERT_MULTIROW -> if (count > 0) out.append(";\n")
      DbExportFormat.XML -> out.append("</data>\n")
      DbExportFormat.PRETTY -> prettyTable()
      DbExportFormat.XLSX -> out.append("</sheetData>")
      else -> {}
    }
  }

  /** A text table: `+---+` rules, a header row, then the rows. */
  private fun prettyTable() {
    val widths = columns.indices.map { c -> pretty.maxOf { it[c].length } }
    val rule = widths.joinToString("+", "+", "+\n") { "-".repeat(it + 2) }
    out.append(rule)
    pretty.forEachIndexed { r, cells ->
      out.append(cells.indices.joinToString("|", "|", "|\n") { c ->
        " " + (if (r > 0 && numeric[c]) cells[c].padStart(widths[c]) else cells[c].padEnd(widths[c])) + " "
      })
      if (r == 0) out.append(rule)
    }
    out.append(rule)
  }

  private fun header(separator: Char) = delimited(columns.map { it.name }, separator)

  private fun delimited(fields: List<String>, separator: Char) {
    fields.forEachIndexed { i, s ->
      if (i > 0) out.append(separator)
      if (s.any { it == separator || it == '"' || it == '\n' || it == '\r' }) out.append('"').append(s.replace("\"", "\"\"")).append('"') else out.append(s)
    }
    out.append("\r\n")
  }

  private fun cell(value: Any?): String = when (value) {
    null -> ""
    is ByteArray -> Base64.getEncoder().encodeToString(value)
    else -> value.toString()
  }

  private fun md(s: String) = s.replace("\\", "\\\\").replace("|", "\\|").replace("\r\n", " ").replace("\n", " ").replace("\r", " ")

  /** XML text or attribute content; characters XML 1.0 cannot hold are dropped. */
  private fun xml(s: String) = html(s).filter { it == '\t' || it == '\n' || it == '\r' || it >= ' ' }

  private fun html(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

  private fun jsonValue(v: Any?): String = when (v) {
    null -> "null"
    is Boolean -> v.toString()
    is Number -> if (v is Double && (v.isNaN() || v.isInfinite()) || v is Float && (v.isNaN() || v.isInfinite())) quote(v.toString()) else v.toString()
    is ByteArray -> quote(Base64.getEncoder().encodeToString(v))
    is TemporalAccessor, is java.util.Date -> quote(v.toString())
    else -> quote(v.toString())
  }

  private fun quote(s: String): String = buildString {
    append('"')
    for (c in s) when (c) {
      '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
      else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
    }
    append('"')
  }
}

/**
 * A whole table (with the data view's filter and order) written to a file page by page, each page a fresh query, so
 * the table is never held in memory and the connection is free between pages.
 */
object DbTableExport {
  const val PAGE_SIZE: Int = 1000

  /** Column types that cannot be compared in ORDER BY in some databases; they are not used to make the order total. */
  private val UNORDERABLE = Regex("(?i)lob|text|bytea|json|xml|binary|image|geometry|array")

  /**
   * The rows of [table] (filtered by [where], in [orderBy]) written to [out] in [format], a page of [pageSize] rows per
   * query. The order is made total with the primary key (or the comparable columns), so pages neither repeat nor skip rows.
   */
  suspend fun export(project: Project, dataSource: DbDataSource, table: DbTableRef, where: String, orderBy: String, format: DbExportFormat,
                     out: Appendable, pageSize: Int = PAGE_SIZE, progress: (Long) -> Unit = {}): Long {
    val dbms = dataSource.dialect()
    var writer: DbExportWriter? = null
    pages(project, dataSource, table, where, orderBy, pageSize) { page ->
      val w = writer ?: format.writer(out, page.columns, DbNames.table(dbms, table), dbms).also { writer = it }
      page.rows.forEach(w::row)
      progress(w.count)
    }
    writer!!.finish()
    return writer!!.count
  }

  /** The rows of [table] (filtered by [where], in [orderBy]) a page of [pageSize] rows per query, each handed to [onPage]. */
  suspend fun pages(project: Project, dataSource: DbDataSource, table: DbTableRef, where: String, orderBy: String, pageSize: Int = PAGE_SIZE,
                    onPage: suspend (DbOutcome.Rows) -> Unit) {
    DbTableQuery.problem(where, orderBy)?.let { throw DbException(it) }
    val dbms = dataSource.dialect()
    val paging = DbPaging.of(dataSource)
    val order = totalOrder(project, dataSource, table, orderBy)
    val paged = dataSource.copy().also { it.maxRows = pageSize }
    var offset = 0
    var previousFirst: Array<Any?>? = null
    while (true) {
      currentCoroutineContext().ensureActive()
      val sql = DbTableQuery.select(dbms, table, where, order, pageSize, offset, paging)
      val page = when (val outcome = DbExecutor.execute(project, paged, listOf(sql), history = false).single()) {
        is DbOutcome.Rows -> outcome
        is DbOutcome.Failed -> throw DbException(outcome.message)
        is DbOutcome.Updated -> throw DbException(PhantomDbBundle.message("db.export.table.not.rows", table.name))
      }
      // a database that ignored the offset would return the first page forever
      val first = page.rows.firstOrNull()
      if (first != null && previousFirst != null && first.contentDeepEquals(previousFirst)) {
        throw DbException(PhantomDbBundle.message("db.export.table.no.paging", table.name))
      }
      previousFirst = first
      onPage(page)
      if (page.rows.size < pageSize) break
      offset += pageSize
    }
  }

  /** [orderBy] followed by the primary key columns, or all comparable columns when there is no primary key. */
  internal suspend fun totalOrder(project: Project, dataSource: DbDataSource, table: DbTableRef, orderBy: String): String {
    val columns = runCatching { DbMetadataCache.getInstance(project).columns(dataSource, table) }.getOrDefault(emptyList())
    val keys = columns.filter { it.primaryKey }.ifEmpty { columns.filterNot { UNORDERABLE.containsMatchIn(it.typeName) } }
    val tieBreak = keys.joinToString(", ") { DbNames.identifier(dataSource.dialect(), it.name) }
    val own = DbTableQuery.clause(orderBy, "ORDER BY")
    return when {
      tieBreak.isEmpty() -> own.orEmpty()
      own == null -> tieBreak
      else -> "$own\n, $tieBreak"
    }
  }

  /** Asks for a file, then exports in the background with progress; cancelling leaves a partial file. */
  fun exportInBackground(project: Project, dataSource: DbDataSource, table: DbTableRef, where: String, orderBy: String, format: DbExportFormat) {
    val refused = DbTableQuery.problem(where, orderBy)
      ?: (SqlSafety.check(dataSource, listOf(DbTableQuery.select(dataSource.dialect(), table, where, orderBy, PAGE_SIZE, 0, DbPaging.of(dataSource))))
            as? SqlSafety.Verdict.Blocked)?.message
    if (refused != null) return notify(project, refused, NotificationType.ERROR)
    val path = DbExportUi.chooseFile(project, format, table.name) ?: return
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, PhantomDbBundle.message("db.export.table.progress", table.name), true) {
      override fun run(indicator: ProgressIndicator) {
        val (text, type) = try {
          val count = runBlockingCancellable {
            DbXlsx.file(path, format) { out ->
              export(project, dataSource, table, where, orderBy, format, out) { indicator.text2 = PhantomDbBundle.message("db.export.table.rows", it) }
            }
          }
          PhantomDbBundle.message("db.export.table.done", count, path.fileName) to NotificationType.INFORMATION
        }
        catch (e: CancellationException) {
          throw e
        }
        catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
          throw e
        }
        catch (e: DbException) {
          e.message.orEmpty() to NotificationType.ERROR
        }
        catch (e: java.sql.SQLException) {
          DbExecutor.message(e) to NotificationType.ERROR
        }
        catch (e: java.io.IOException) {
          (e.message ?: e.javaClass.simpleName) to NotificationType.ERROR
        }
        catch (e: Exception) {
          // a bug on our side: report it, and still tell the user the export stopped
          com.intellij.openapi.diagnostic.logger<DbTableExport>().error(e)
          PhantomDbBundle.message("db.export.table.failed", table.name, e.message ?: e.javaClass.simpleName) to NotificationType.ERROR
        }
        notify(project, text, type)
      }
    })
  }

  private fun notify(project: Project, text: String, type: NotificationType) =
    NotificationGroupManager.getInstance().getNotificationGroup("Phantom Database").createNotification(text, type).notify(project)
}

object DbExportUi {
  /** Copy or save the shown rows in a format the user picks; [table] is the INSERT target, else one is asked for. */
  fun show(project: Project, owner: Component, rows: DbOutcome.Rows, dbms: Dbms, table: String? = DbExportFormat.tableSql(dbms, SingleTableSelect.parse(rows.sql)),
           wholeTable: ((DbExportFormat) -> Unit)? = null) {
    val items = DbExportFormat.entries.flatMap { listOfNotNull(if (it.text) it to 0 else null, it to 1, if (wholeTable != null) it to 2 else null) }
    val step = object : BaseListPopupStep<Pair<DbExportFormat, Int>>(PhantomDbBundle.message("db.export.title", rows.rows.size), items) {
      override fun getTextFor(value: Pair<DbExportFormat, Int>): String = when (value.second) {
        0 -> PhantomDbBundle.message("db.export.copy", value.first.title)
        1 -> PhantomDbBundle.message("db.export.save", value.first.title)
        else -> PhantomDbBundle.message("db.export.table", value.first.title)
      }

      override fun onChosen(selectedValue: Pair<DbExportFormat, Int>, finalChoice: Boolean): PopupStep<*>? = doFinalStep {
        val (format, how) = selectedValue
        when (how) {
          2 -> wholeTable!!(format)
          else -> {
            val target = if (format.needsTable) table ?: askTable(project) ?: return@doFinalStep else table
            val text = format.render(rows, target, dbms)
            if (how == 0) CopyPasteManager.getInstance().setContents(StringSelection(text))
            else chooseFile(project, format, SingleTableSelect.parse(rows.sql)?.table ?: "result")?.let { DbXlsx.file(it, format) { out -> out.append(text) } }
          }
        }
      }
    }
    JBPopupFactory.getInstance().createListPopup(step).showUnderneathOf(owner)
  }

  /** The table INSERTs go to when the rows do not tell: as typed, or null when cancelled. */
  fun askTable(project: Project): String? =
    com.intellij.openapi.ui.Messages.showInputDialog(project, PhantomDbBundle.message("db.export.insert.table"), PhantomDbBundle.message("db.export.insert.title"),
                                                     null, "table_name", null)?.trim()?.ifEmpty { null }

  fun chooseFile(project: Project, format: DbExportFormat, baseName: String): Path? {
    val descriptor = FileSaverDescriptor(PhantomDbBundle.message("db.export.file.title"), PhantomDbBundle.message("db.export.file.description", format.title), format.extension)
    return FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project).save("$baseName.${format.extension}")?.file?.toPath()
  }
}
