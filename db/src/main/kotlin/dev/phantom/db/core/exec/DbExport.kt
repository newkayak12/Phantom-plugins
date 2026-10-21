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

/** Result rows as text: TSV, CSV (RFC 4180), JSON, SQL INSERT statements, a Markdown table or an HTML table. */
enum class DbExportFormat(val title: String, val extension: String) {
  TSV("TSV", "tsv"), CSV("CSV", "csv"), JSON("JSON", "json"), INSERT("SQL INSERT", "sql"), MARKDOWN("Markdown", "md"), HTML("HTML", "html");

  /** [table]: the INSERT target as SQL, quoted as needed; `table_name` when unknown. */
  fun render(rows: DbOutcome.Rows, table: String?, dbms: Dbms): String {
    val out = StringBuilder()
    val writer = writer(out, rows.columns, table, dbms)
    rows.rows.forEach(writer::row)
    writer.finish()
    return out.toString()
  }

  /** Writes rows one at a time, so a whole table can go to a file without holding it in memory. */
  fun writer(out: Appendable, columns: List<DbColumn>, table: String?, dbms: Dbms): DbExportWriter = DbExportWriter(this, out, columns, table, dbms)

  companion object {
    fun tableSql(dbms: Dbms, table: DbTableName?): String? = table?.let { DbNames.qualified(dbms, it.schema, it.table) }
  }
}

class DbExportWriter internal constructor(private val format: DbExportFormat, private val out: Appendable, private val columns: List<DbColumn>,
                                          table: String?, private val dbms: Dbms) {
  var count: Long = 0
    private set

  private val target = table ?: "table_name"
  private val names by lazy { columns.joinToString(", ") { DbNames.quote(dbms, it.name) } }

  init {
    when (format) {
      DbExportFormat.TSV -> header('\t')
      DbExportFormat.CSV -> header(',')
      DbExportFormat.JSON -> out.append("[")
      DbExportFormat.INSERT -> {}
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
      else -> {}
    }
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

  private fun md(s: String) = s.replace("|", "\\|").replace("\r\n", " ").replace("\n", " ")

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

  suspend fun export(project: Project, dataSource: DbDataSource, table: DbTableRef, where: String, orderBy: String, format: DbExportFormat,
                     out: Appendable, pageSize: Int = PAGE_SIZE, progress: (Long) -> Unit = {}): Long {
    val dbms = dataSource.dialect()
    val paged = dataSource.copy().also { it.maxRows = pageSize }
    var writer: DbExportWriter? = null
    var offset = 0
    while (true) {
      currentCoroutineContext().ensureActive()
      val sql = DbTableQuery.select(dbms, table, where, orderBy, pageSize, offset)
      val page = when (val outcome = DbExecutor.execute(project, paged, listOf(sql), history = false).single()) {
        is DbOutcome.Rows -> outcome
        is DbOutcome.Failed -> throw DbException(outcome.message)
        is DbOutcome.Updated -> throw DbException(sql)
      }
      val w = writer ?: format.writer(out, page.columns, DbNames.table(dbms, table), dbms).also { writer = it }
      page.rows.forEach(w::row)
      progress(w.count)
      if (page.rows.size < pageSize) break
      offset += pageSize
    }
    writer!!.finish()
    return writer!!.count
  }

  /** Asks for a file, then exports in the background with progress; cancelling leaves a partial file. */
  fun exportInBackground(project: Project, dataSource: DbDataSource, table: DbTableRef, where: String, orderBy: String, format: DbExportFormat) {
    val path = DbExportUi.chooseFile(project, format, table.name) ?: return
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, PhantomDbBundle.message("db.export.table.progress", table.name), true) {
      override fun run(indicator: ProgressIndicator) {
        val (text, type) = try {
          val count = runBlockingCancellable {
            Files.newBufferedWriter(path).use { out ->
              export(project, dataSource, table, where, orderBy, format, out) { indicator.text2 = PhantomDbBundle.message("db.export.table.rows", it) }
            }
          }
          PhantomDbBundle.message("db.export.table.done", count, path.fileName) to NotificationType.INFORMATION
        }
        catch (e: CancellationException) {
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
        NotificationGroupManager.getInstance().getNotificationGroup("Phantom Database").createNotification(text, type).notify(project)
      }
    })
  }
}

object DbExportUi {
  /** Copy or save the shown rows in a format the user picks; [table] is the INSERT target, else one is asked for. */
  fun show(project: Project, owner: Component, rows: DbOutcome.Rows, dbms: Dbms, table: String? = DbExportFormat.tableSql(dbms, SingleTableSelect.parse(rows.sql)),
           wholeTable: ((DbExportFormat) -> Unit)? = null) {
    val items = DbExportFormat.entries.flatMap { listOfNotNull(it to 0, it to 1, if (wholeTable != null) it to 2 else null) }
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
            val target = if (format == DbExportFormat.INSERT) table ?: askTable(project) ?: return@doFinalStep else table
            val text = format.render(rows, target, dbms)
            if (how == 0) CopyPasteManager.getInstance().setContents(StringSelection(text))
            else chooseFile(project, format, SingleTableSelect.parse(rows.sql)?.table ?: "result")?.let { Files.writeString(it, text) }
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
