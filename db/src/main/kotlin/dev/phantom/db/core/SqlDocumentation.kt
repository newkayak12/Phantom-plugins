// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core

import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import dev.phantom.db.core.exec.DbColumnDetail
import dev.phantom.db.core.exec.DbColumnInfo
import dev.phantom.db.core.exec.DbForeignKey
import dev.phantom.db.core.exec.DbIndex
import dev.phantom.db.core.exec.DbPrimaryKey
import dev.phantom.db.core.exec.DbTableRef

/**
 * Quick Documentation (F1 / Ctrl+Q) on a table or column name in SQL, as in DataGrip: a table's columns with their
 * types, NOT NULL, defaults and keys, then its indexes and foreign keys; a column's type, default and keys. It shows
 * what is loaded (loading columns and indexes as completion does); foreign keys appear once loaded.
 */
class SqlDocumentationProvider : AbstractDocumentationProvider() {
  override fun getQuickNavigateInfo(element: PsiElement?, originalElement: PsiElement?): String? {
    val target = element as? SqlDbTarget ?: return null
    val column = target.column ?: return "${kind(target.table)} ${esc(qualified(target.table))}"
    return "${esc(target.table.name)}.${esc(column.name)} ${esc(column.typeName)}"
  }

  override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? {
    val target = element as? SqlDbTarget ?: return null
    val schema = SqlSchema.getInstance(target.project)
    val ds = target.dataSource
    val table = target.table
    val columns = schema.columns(ds, table).orEmpty()
    val details = schema.columnDetails(ds, table).orEmpty()
    val (pk, indexes) = schema.indexes(ds, table) ?: (DbPrimaryKey(null, columns.filter { it.primaryKey }.map { it.name }) to emptyList())
    val fks = schema.foreignKeys(ds, table)?.first.orEmpty()
    return render(table, target.column, ds.name, columns, details, pk, indexes, fks)
  }

  companion object {
    internal fun render(table: DbTableRef, column: DbColumnInfo?, dataSource: String, columns: List<DbColumnInfo>, details: List<DbColumnDetail>,
                        pk: DbPrimaryKey, indexes: List<DbIndex>, fks: List<DbForeignKey>): String = buildString {
      fun detail(name: String) = details.firstOrNull { it.name == name }
      fun keys(name: String): String = listOfNotNull(
        if (name in pk.columns) "PK" else null,
        fks.filter { name in it.columns }.joinToString { "FK → " + it.refTable.name + "." + it.refColumns[it.columns.indexOf(name)] }.ifEmpty { null },
      ).joinToString(", ")

      append(DocumentationMarkup.DEFINITION_START)
      if (column == null) append(kind(table)).append(" <b>").append(esc(qualified(table))).append("</b>")
      else append("<b>").append(esc(table.name)).append('.').append(esc(column.name)).append("</b> ").append(esc(column.typeName))
      append(DocumentationMarkup.DEFINITION_END)
      append(DocumentationMarkup.CONTENT_START)
      if (column != null) {
        val d = detail(column.name)
        val nullable = d?.nullable ?: column.nullable
        append(if (nullable) "NULL" else "NOT NULL")
        d?.default?.let { append("<br>DEFAULT ").append(esc(it)) }
        if (d?.autoIncrement == true) append("<br>auto increment")
        if (d?.generated == true) append("<br>generated")
        keys(column.name).ifEmpty { null }?.let { append("<br>").append(esc(it)) }
      }
      else if (columns.isEmpty()) append(esc(PhantomDbBundle.message("sql.doc.not.loaded")))
      else {
        append("<table>")
        for (c in columns) {
          val d = detail(c.name)
          append("<tr><td><code>").append(esc(c.name)).append("</code></td><td>").append(esc(c.typeName))
          if (!(d?.nullable ?: c.nullable)) append(" NOT NULL")
          d?.default?.let { append(" DEFAULT ").append(esc(StringUtil.shortenTextWithEllipsis(it, 40, 0))) }
          append("</td><td>").append(esc(keys(c.name))).append("</td></tr>")
        }
        append("</table>")
        if (indexes.isNotEmpty()) {
          append("<p><b>Indexes</b><br>")
          append(indexes.joinToString("<br>") { esc((if (it.unique) "UNIQUE " else "") + it.name + " (" + it.columns.joinToString() + ")") })
        }
        if (fks.isNotEmpty()) {
          append("<p><b>Foreign keys</b><br>")
          append(fks.joinToString("<br>") { esc("(" + it.columns.joinToString() + ") → " + it.refTable.name + " (" + it.refColumns.joinToString() + ")") })
        }
      }
      append(DocumentationMarkup.CONTENT_END)
      append(DocumentationMarkup.SECTIONS_START)
      append(DocumentationMarkup.SECTION_HEADER_START).append("Data source").append(DocumentationMarkup.SECTION_SEPARATOR)
        .append(esc(dataSource)).append(DocumentationMarkup.SECTION_END)
      append(DocumentationMarkup.SECTIONS_END)
    }

    private fun kind(table: DbTableRef) = if (table.isView) "view" else "table"

    private fun qualified(table: DbTableRef) = listOfNotNull(table.schema ?: table.catalog, table.name).joinToString(".")

    private fun esc(s: String) = StringUtil.escapeXmlEntities(s)
  }
}
