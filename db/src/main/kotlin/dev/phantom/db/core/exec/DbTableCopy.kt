// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.exec

import com.intellij.openapi.project.Project
import dev.phantom.db.core.DbDataSource
import dev.phantom.db.core.Dbms
import dev.phantom.db.core.PhantomDbBundle
import dev.phantom.db.core.dialect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.SQLException

/** What Copy Table To did: rows written, and whether the table was created first. */
data class DbTableCopyResult(val rows: Long, val created: Boolean, val target: String, val pending: Boolean)

/**
 * Copy Table To (DataGrip's data transfer, for one table): the rows of a table go into a table of the same name in
 * another schema, of the same or another data source. A missing table is created first, with the source's columns
 * and primary key (types translated when the databases differ); an existing one gets the columns both have, by name.
 * The source is read page by page, each page written as one batch, so the table is never held in memory.
 */
object DbTableCopy {
  suspend fun run(project: Project, source: DbDataSource, table: DbTableRef, target: DbDataSource, schema: DbSchemaRef,
                  progress: (Long) -> Unit = {}): DbTableCopyResult {
    if (target.readOnly) throw DbException(PhantomDbBundle.message("db.csv.read.only", target.name))
    val from = source.dialect()
    val to = target.dialect()
    val objects = DbObjectCache.getInstance(project)
    val cache = DbMetadataCache.getInstance(project)
    val columns = objects.columnDetails(source, table).filterNot { it.generated }
    if (columns.isEmpty()) throw DbException(PhantomDbBundle.message("db.copy.table.no.columns", table.name))

    val existing = cache.tables(target, schema).firstOrNull { it.name.equals(table.name, ignoreCase = true) && !it.isView }
    if (existing != null && existing.dataSourceId == table.dataSourceId && existing.catalog == table.catalog && existing.schema == table.schema) {
      throw DbException(PhantomDbBundle.message("db.copy.table.same", table.name))
    }
    val ref = existing ?: DbTableRef(target.id, schema.catalog, schema.schema, targetName(from, to, table.name), "TABLE")
    if (existing == null) {
      val pk = objects.primaryKey(source, table)
      val ddl = DbDdl.createTable(to, ref, columns.map { translate(from, to, it) }, pk.copy(name = null), emptyList(), emptyList())
      val outcome = DbExecutor.execute(project, target, listOf(ddl.trim().removeSuffix(";")), history = false).single()
      if (outcome is DbOutcome.Failed) throw DbException(PhantomDbBundle.message("db.copy.table.create.failed", outcome.message))
      cache.invalidate(target.id)
    }
    // the columns both tables have, by name; the target's spelling goes into the INSERT
    val targetColumns = if (existing == null) columns.map { it.name } else objects.columnDetails(target, existing).filterNot { it.generated }.map { it.name }
    val pairs = columns.mapIndexedNotNull { i, c -> targetColumns.firstOrNull { it.equals(c.name, ignoreCase = true) }?.let { i to it } }
    if (pairs.isEmpty()) throw DbException(PhantomDbBundle.message("db.copy.table.no.common", DbNames.table(to, ref)))
    val insert = DbCsvImport.insert(to, ref, pairs.map { it.second })

    var count = 0L
    var pending = false
    DbTableExport.pages(project, source, table, "", "") { page ->
      // the page's columns are the select's: find the copied ones by name
      val indices = pairs.map { (i, _) -> page.columns.indexOfFirst { it.name.equals(columns[i].name, ignoreCase = true) } }
      if (page.rows.isEmpty()) return@pages
      pending = write(project, target, insert, page.rows.map { row -> indices.map { if (it < 0) null else row[it] } }) || pending
      count += page.rows.size
      progress(count)
    }
    return DbTableCopyResult(count, existing == null, DbNames.table(to, ref), pending)
  }

  /** One batch in its own transaction, or in the open one of a manual-commit data source (then true: waiting for Commit). */
  private suspend fun write(project: Project, target: DbDataSource, insert: String, rows: List<List<Any?>>): Boolean =
    DbConnectionService.getInstance(project).withConnection(target, DbConnectionService.Purpose.CONSOLE) { c ->
      withContext(Dispatchers.IO) {
        val manual = !c.autoCommit
        val savepoint = if (manual) c.setSavepoint() else null
        if (!manual) c.autoCommit = false
        try {
          c.prepareStatement(insert).use { ps ->
            for (row in rows) {
              row.forEachIndexed { i, v -> if (v == null) ps.setNull(i + 1, java.sql.Types.NULL) else ps.setObject(i + 1, v) }
              ps.addBatch()
            }
            ps.executeBatch()
          }
          if (manual) {
            savepoint?.let { runCatching { c.releaseSavepoint(it) } }
            DbPendingChanges.getInstance(project).afterStatement(target, insert, listOf(DbOutcome.Updated("INSERT", rows.size.toLong(), 0)))
          }
          else c.commit()
          manual
        }
        catch (e: SQLException) {
          if (manual) savepoint?.let { runCatching { c.rollback(it) } } else runCatching { c.rollback() }
          throw DbException(PhantomDbBundle.message("db.copy.table.failed", DbExecutor.message(e)), e)
        }
        finally {
          if (!manual) runCatching { c.autoCommit = true }
        }
      }
    }

  /** The new table's name: as it is, but folded the way the target folds unquoted names when the source folded it its way. */
  internal fun targetName(from: Dbms, to: Dbms, name: String): String = when {
    from == to -> name
    name == name.uppercase() && (to == Dbms.POSTGRESQL) -> name.lowercase()
    name == name.lowercase() && (to == Dbms.H2) -> name.uppercase()
    else -> name
  }

  /** A column for the target's CREATE TABLE: the source's type when the database is the same, else a standard one. */
  internal fun translate(from: Dbms, to: Dbms, column: DbColumnDetail): DbColumnDetail {
    if (from == to) return column
    return column.copy(name = targetName(from, to, column.name), typeName = type(to, column.typeName), default = null, autoIncrement = false)
  }

  /** [typeName] (with its size, as the driver reported it) as a type [to] has. */
  internal fun type(to: Dbms, typeName: String): String {
    val upper = typeName.uppercase().replace("UNSIGNED", "").replace("ZEROFILL", "").trim()
    val base = upper.substringBefore('(').trim()
    val args = upper.substringAfter('(', "").substringBefore(')').trim()
    val sized = { name: String -> if (args.isEmpty()) name else "$name($args)" }
    val pg = to == Dbms.POSTGRESQL
    val my = to.usesCatalogs
    return when (base) {
      "TINYINT", "SMALLINT", "INT2", "SMALLSERIAL" -> if (base == "TINYINT" && args == "1" && !my) "BOOLEAN" else "SMALLINT"
      "MEDIUMINT", "INT", "INTEGER", "INT4", "SERIAL" -> "INTEGER"
      "BIGINT", "INT8", "BIGSERIAL" -> "BIGINT"
      "DECIMAL", "NUMERIC", "NUMBER" -> sized("DECIMAL")
      "FLOAT", "REAL", "FLOAT4" -> "REAL"
      "DOUBLE", "DOUBLE PRECISION", "FLOAT8" -> if (my) "DOUBLE" else "DOUBLE PRECISION"
      "BIT", "BOOL", "BOOLEAN" -> if (my) "TINYINT(1)" else "BOOLEAN"
      "CHAR", "CHARACTER", "BPCHAR", "NCHAR" -> sized("CHAR")
      "VARCHAR", "CHARACTER VARYING", "NVARCHAR", "VARCHAR2" -> if (args.isEmpty()) (if (my) "TEXT" else "VARCHAR") else sized("VARCHAR")
      "TEXT", "TINYTEXT", "MEDIUMTEXT", "LONGTEXT", "CLOB", "CHARACTER LARGE OBJECT" -> if (my || pg) "TEXT" else "CLOB"
      "DATE" -> "DATE"
      "TIME", "TIME WITHOUT TIME ZONE" -> "TIME"
      "DATETIME", "TIMESTAMP", "TIMESTAMP WITHOUT TIME ZONE" -> if (my) sized("DATETIME") else sized("TIMESTAMP")
      "TIMESTAMPTZ", "TIMESTAMP WITH TIME ZONE" -> if (my) "TIMESTAMP" else "TIMESTAMP WITH TIME ZONE"
      "BINARY", "VARBINARY", "BLOB", "TINYBLOB", "MEDIUMBLOB", "LONGBLOB", "BYTEA", "BINARY LARGE OBJECT" -> when {
        pg -> "BYTEA"
        my -> "LONGBLOB"
        else -> "BLOB"
      }
      "JSON", "JSONB" -> if (pg || my) "JSON" else "CLOB"
      "UUID" -> if (pg || to == Dbms.H2) "UUID" else "CHAR(36)"
      else -> typeName
    }
  }
}
