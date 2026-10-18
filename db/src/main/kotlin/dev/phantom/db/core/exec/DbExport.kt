// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.exec

import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import dev.phantom.db.core.Dbms
import java.awt.Component
import java.awt.datatransfer.StringSelection
import java.nio.file.Files
import java.time.temporal.TemporalAccessor
import java.util.Base64

/** Result rows as text: CSV (RFC 4180), TSV, JSON, SQL INSERT statements or a Markdown table. */
enum class DbExportFormat(val title: String, val extension: String) {
  CSV("CSV", "csv"), TSV("TSV", "tsv"), JSON("JSON", "json"), INSERT("SQL INSERT", "sql"), MARKDOWN("Markdown", "md");

  fun render(rows: DbOutcome.Rows, table: DbTableName?, dbms: Dbms): String = when (this) {
    CSV -> delimited(rows, ',')
    TSV -> delimited(rows, '\t')
    JSON -> json(rows)
    INSERT -> inserts(rows, table, dbms)
    MARKDOWN -> markdown(rows)
  }

  private fun cell(value: Any?): String = when (value) {
    null -> ""
    is ByteArray -> Base64.getEncoder().encodeToString(value)
    else -> value.toString()
  }

  private fun delimited(rows: DbOutcome.Rows, separator: Char): String = buildString {
    fun field(s: String) {
      if (s.any { it == separator || it == '"' || it == '\n' || it == '\r' }) append('"').append(s.replace("\"", "\"\"")).append('"') else append(s)
    }
    rows.columns.forEachIndexed { i, c -> if (i > 0) append(separator); field(c.name) }
    append("\r\n")
    for (row in rows.rows) {
      row.forEachIndexed { i, v -> if (i > 0) append(separator); field(cell(v)) }
      append("\r\n")
    }
  }

  private fun json(rows: DbOutcome.Rows): String = buildString {
    append("[")
    rows.rows.forEachIndexed { r, row ->
      append(if (r == 0) "\n  {" else ",\n  {")
      rows.columns.forEachIndexed { i, c ->
        if (i > 0) append(", ")
        append(quote(c.name)).append(": ").append(jsonValue(row[i]))
      }
      append("}")
    }
    append(if (rows.rows.isEmpty()) "]" else "\n]")
  }

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

  private fun inserts(rows: DbOutcome.Rows, table: DbTableName?, dbms: Dbms): String {
    val q = identifierQuote(dbms)
    fun ident(name: String) = if (name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) name else q + name.replace(q, q + q) + q
    val target = table?.let { listOfNotNull(it.schema, it.table).joinToString(".") { part -> ident(part) } } ?: "table_name"
    val columns = rows.columns.joinToString(", ") { ident(it.name) }
    return rows.rows.joinToString("") { row ->
      "INSERT INTO $target ($columns) VALUES (${row.joinToString(", ") { DbStatement.literal(it, dbms) }});\n"
    }
  }

  private fun identifierQuote(dbms: Dbms): String = if (dbms == Dbms.MYSQL) "`" else "\""

  private fun markdown(rows: DbOutcome.Rows): String = buildString {
    fun esc(s: String) = s.replace("|", "\\|").replace("\n", " ")
    append("| ").append(rows.columns.joinToString(" | ") { esc(it.name) }).append(" |\n")
    append("|").append(rows.columns.joinToString("|") { " --- " }).append("|\n")
    for (row in rows.rows) append("| ").append(row.joinToString(" | ") { esc(if (it == null) "NULL" else cell(it)) }).append(" |\n")
  }
}

object DbExportUi {
  /** Copy or save the shown rows in a format the user picks. */
  fun show(project: Project, owner: Component, rows: DbOutcome.Rows, dbms: Dbms) {
    val table = SingleTableSelect.parse(rows.sql)
    val items = DbExportFormat.entries.flatMap { listOf(it to false, it to true) }
    val step = object : BaseListPopupStep<Pair<DbExportFormat, Boolean>>("Export ${rows.rows.size} Rows", items) {
      override fun getTextFor(value: Pair<DbExportFormat, Boolean>): String =
        if (value.second) "Save as ${value.first.title}…" else "Copy as ${value.first.title}"

      override fun onChosen(selectedValue: Pair<DbExportFormat, Boolean>, finalChoice: Boolean): PopupStep<*>? = doFinalStep {
        val (format, save) = selectedValue
        val text = format.render(rows, table, dbms)
        if (!save) CopyPasteManager.getInstance().setContents(StringSelection(text))
        else saveFile(project, format, table?.table ?: "result", text)
      }
    }
    JBPopupFactory.getInstance().createListPopup(step).showUnderneathOf(owner)
  }

  private fun saveFile(project: Project, format: DbExportFormat, baseName: String, text: String) {
    val descriptor = FileSaverDescriptor("Export Rows", "Save the result as ${format.title}", format.extension)
    val target = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project).save("$baseName.${format.extension}") ?: return
    Files.writeString(target.file.toPath(), text)
  }
}
