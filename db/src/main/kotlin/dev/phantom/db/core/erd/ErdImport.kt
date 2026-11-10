// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.erd

import com.intellij.openapi.project.Project
import dev.phantom.db.core.DbDataSource
import dev.phantom.db.core.Dbms
import dev.phantom.db.core.dialect
import dev.phantom.db.core.exec.DbColumnInfo
import dev.phantom.db.core.exec.DbConnectionService
import dev.phantom.db.core.exec.DbForeignKey
import dev.phantom.db.core.exec.DbSchemaReader
import dev.phantom.db.core.exec.DbSchemaRef
import dev.phantom.db.core.exec.DbTableRef
import dev.phantom.db.core.ui.DbDiagramLayout
import dev.phantom.db.core.ui.DbDiagramModel
import dev.phantom.db.core.ui.DbDiagramTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Dimension
import java.sql.DatabaseMetaData

/** A database's schema as a design, and laying it into one. */
object ErdImport {
  suspend fun read(project: Project, dataSource: DbDataSource, schema: DbSchemaRef): ErdDocument =
    DbConnectionService.getInstance(project).withConnection(dataSource, DbConnectionService.Purpose.METADATA) { connection ->
      withContext(Dispatchers.IO) { read(connection.metaData, schema, dataSource.dialect()).copy(dataSource = dataSource.name) }
    }

  fun read(md: DatabaseMetaData, schema: DbSchemaRef, dbms: Dbms): ErdDocument {
    val model = DbSchemaReader.read(md, schema)
    val tableComments = md.getTables(schema.catalog, schema.schema, "%", arrayOf("TABLE")).use { rs ->
      generateSequence { if (rs.next()) rs.getString("TABLE_NAME") to rs.getString("REMARKS").orEmpty() else null }.toMap()
    }
    val columnExtras = HashMap<Pair<String, String>, Pair<String, Boolean>>()
    md.getColumns(schema.catalog, schema.schema, "%", "%").use { rs ->
      while (rs.next()) {
        columnExtras[rs.getString("TABLE_NAME") to rs.getString("COLUMN_NAME")] = rs.getString("REMARKS").orEmpty() to (rs.getString("IS_AUTOINCREMENT") == "YES")
      }
    }
    val tables = model.tables.values.map { t ->
      ErdTable(
        t.name, comment = tableComments[t.name].orEmpty(),
        columns = t.columns.map { c ->
          val (comment, auto) = columnExtras[t.name to c.name] ?: ("" to false)
          ErdColumn(c.name, c.type, primaryKey = c.name in t.primaryKey, nullable = c.nullable, autoIncrement = auto,
                    default = if (auto) null else default(dbms, c.default), comment = comment)
        },
        indexes = t.indexes.map { ErdIndex(it.name, it.unique, it.columns) },
      )
    }
    val relations = tables.flatMap { t -> foreignKeys(md, schema, t.name).filter { r -> tables.any { it.name == r.refTable } } }
    val doc = ErdDocument(if (dbms.hasDialect) dbms else Dbms.MYSQL, schema = schema.name, tables = tables, relations = relations)
    return doc.copy(tables = placed(doc))
  }

  /** MySQL's driver reports a string default without its quotes; a default is kept as the SQL that writes it. */
  private fun default(dbms: Dbms, raw: String?): String? {
    if (raw == null || raw.equals("NULL", ignoreCase = true)) return null
    if (dbms != Dbms.MYSQL) return raw
    val expression = raw.toDoubleOrNull() != null || raw.contains('(') || raw.startsWith("CURRENT_", ignoreCase = true) || raw.startsWith("'")
    return if (expression) raw else "'" + raw.replace("'", "''") + "'"
  }

  private fun foreignKeys(md: DatabaseMetaData, schema: DbSchemaRef, table: String): List<ErdRelation> = runCatching {
    md.getImportedKeys(schema.catalog, schema.schema, table).use { rs ->
      val keys = ArrayList<ErdRelation>()
      while (rs.next()) {
        val column = rs.getString("FKCOLUMN_NAME")
        val ref = rs.getString("PKCOLUMN_NAME")
        if (rs.getShort("KEY_SEQ").toInt() == 1 || keys.isEmpty()) {
          keys += ErdRelation(rs.getString("FK_NAME") ?: "fk_${table}_${keys.size + 1}", table, listOf(column), rs.getString("PKTABLE_NAME"), listOf(ref))
        }
        else keys[keys.lastIndex] = keys.last().let { it.copy(columns = it.columns + column, refColumns = it.refColumns + ref) }
      }
      keys
    }
  }.getOrDefault(emptyList())

  /** The tables placed by the database diagram's layered layout. */
  private fun placed(doc: ErdDocument): List<ErdTable> {
    fun ref(name: String) = DbTableRef("", null, null, name, "TABLE")
    val model = DbDiagramModel(
      "", doc.tables.map { t -> DbDiagramTable(ref(t.name), t.columns.map { DbColumnInfo(it.name, it.type, it.nullable, it.primaryKey) }, emptySet()) },
      doc.relations.map { DbForeignKey(it.name, ref(it.table), it.columns, ref(it.refTable), it.refColumns) }, false,
    )
    val boxes = DbDiagramLayout.place(model) { Dimension(ESTIMATED_WIDTH, ESTIMATED_HEADER + it.columns.size * ESTIMATED_ROW) }
    return doc.tables.map { t -> boxes[ref(t.name)]?.let { t.copy(x = it.x, y = it.y) } ?: t }
  }

  /**
   * [imported] laid into [doc]: a table the design already has takes the database's definition but keeps its place and
   * logical names, new ones go below the design, and tables only the design has stay.
   */
  fun merge(doc: ErdDocument, imported: ErdDocument): ErdDocument {
    val bottom = doc.tables.maxOfOrNull { it.y + ESTIMATED_HEADER + it.columns.size * ESTIMATED_ROW + DbDiagramLayout.GAP_Y } ?: 0
    val merged = imported.tables.map { t ->
      val old = doc.tables.firstOrNull { it.name.equals(t.name, ignoreCase = true) } ?: return@map t.copy(y = t.y + bottom)
      t.copy(x = old.x, y = old.y, logicalName = old.logicalName, columns = t.columns.map { c ->
        c.copy(logicalName = old.columns.firstOrNull { it.name.equals(c.name, ignoreCase = true) }?.logicalName.orEmpty())
      })
    }
    val kept = doc.tables.filter { t -> imported.tables.none { it.name.equals(t.name, ignoreCase = true) } }
    val keptNames = kept.map { it.name }.toSet()
    return imported.copy(tables = merged + kept, relations = imported.relations + doc.relations.filter { it.table in keptNames })
  }

  private const val ESTIMATED_WIDTH = 240
  private const val ESTIMATED_HEADER = 40
  private const val ESTIMATED_ROW = 20
}
