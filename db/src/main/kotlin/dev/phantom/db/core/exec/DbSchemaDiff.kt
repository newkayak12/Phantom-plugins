// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.exec

import com.intellij.openapi.project.Project
import dev.phantom.db.core.DbDataSource
import dev.phantom.db.core.Dbms
import dev.phantom.db.core.PhantomDbBundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.DatabaseMetaData

/** A schema's tables as the JDBC metadata describes them, enough to compare two schemas and write the changes. */
data class DbSchemaModel(val name: String, val tables: Map<String, DbTableModel>)

data class DbTableModel(val name: String, val columns: List<DbColumnModel>, val primaryKey: List<String>, val indexes: List<DbIndexModel>)

data class DbColumnModel(val name: String, val type: String, val nullable: Boolean, val default: String?)

data class DbIndexModel(val name: String, val unique: Boolean, val columns: List<String>)

object DbSchemaReader {
  /** Reads [schema] over the metadata connection: one call for all columns, then keys and indexes per table. */
  suspend fun read(project: Project, dataSource: DbDataSource, schema: DbSchemaRef): DbSchemaModel =
    DbConnectionService.getInstance(project).withConnection(dataSource, DbConnectionService.Purpose.METADATA) { connection ->
      withContext(Dispatchers.IO) { read(connection.metaData, schema) }
    }

  fun read(md: DatabaseMetaData, schema: DbSchemaRef): DbSchemaModel {
    val tableNames = md.getTables(schema.catalog, schema.schema, "%", arrayOf("TABLE")).use { rs ->
      generateSequence { if (rs.next()) rs.getString("TABLE_NAME") else null }.toList()
    }.toSet()
    val columns = HashMap<String, MutableList<Pair<Int, DbColumnModel>>>()
    md.getColumns(schema.catalog, schema.schema, "%", "%").use { rs ->
      while (rs.next()) {
        val table = rs.getString("TABLE_NAME")
        if (table !in tableNames) continue
        val type = rs.getString("TYPE_NAME").orEmpty()
        val size = rs.getInt("COLUMN_SIZE").takeUnless { rs.wasNull() }
        val scale = rs.getInt("DECIMAL_DIGITS").takeUnless { rs.wasNull() }
        val typeText = when {
          type.uppercase() in SIZED && size != null -> "$type($size)"
          type.uppercase() in SCALED && size != null -> if (scale != null && scale > 0) "$type($size,$scale)" else "$type($size)"
          else -> type
        }
        val column = DbColumnModel(rs.getString("COLUMN_NAME"), typeText, rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                                   rs.getString("COLUMN_DEF")?.trim())
        columns.getOrPut(table) { ArrayList() }.add(rs.getInt("ORDINAL_POSITION") to column)
      }
    }
    val tables = tableNames.sorted().associateWith { table ->
      val key = md.getPrimaryKeys(schema.catalog, schema.schema, table).use { rs ->
        generateSequence { if (rs.next()) rs.getShort("KEY_SEQ") to rs.getString("COLUMN_NAME") else null }.sortedBy { it.first }.map { it.second }.toList()
      }
      val indexColumns = LinkedHashMap<String, Pair<Boolean, MutableList<Pair<Short, String>>>>()
      runCatching {
        md.getIndexInfo(schema.catalog, schema.schema, table, false, true).use { rs ->
          while (rs.next()) {
            val name = rs.getString("INDEX_NAME") ?: continue
            val column = rs.getString("COLUMN_NAME") ?: continue
            indexColumns.getOrPut(name) { !rs.getBoolean("NON_UNIQUE") to ArrayList() }.second.add(rs.getShort("ORDINAL_POSITION") to column)
          }
        }
      }
      // the index behind the primary key is the key itself, not something to compare or create
      val indexes = indexColumns.map { (name, v) -> DbIndexModel(name, v.first, v.second.sortedBy { it.first }.map { it.second }) }
        .filterNot { it.unique && it.columns == key }
        .sortedBy { it.name.lowercase() }
      DbTableModel(table, columns[table].orEmpty().sortedBy { it.first }.map { it.second }, key, indexes)
    }
    return DbSchemaModel(schema.name, tables)
  }

  private val SIZED = setOf("VARCHAR", "CHAR", "CHARACTER", "CHARACTER VARYING", "VARBINARY", "BINARY", "NVARCHAR", "NCHAR", "BPCHAR")
  private val SCALED = setOf("DECIMAL", "NUMERIC")
}

object DbSchemaDiff {
  /** A stable text form of the schema, one line per column, key and index, for the diff viewer. */
  fun dump(model: DbSchemaModel): String = buildString {
    for (table in model.tables.values) {
      append("TABLE ").append(table.name).append('\n')
      for (c in table.columns) {
        append("  ").append(c.name).append(' ').append(c.type).append(if (c.nullable) " NULL" else " NOT NULL")
        c.default?.let { append(" DEFAULT ").append(it) }
        append('\n')
      }
      if (table.primaryKey.isNotEmpty()) append("  PRIMARY KEY (").append(table.primaryKey.joinToString()).append(")\n")
      for (i in table.indexes) append("  ").append(if (i.unique) "UNIQUE INDEX " else "INDEX ").append(i.name).append(" (").append(i.columns.joinToString()).append(")\n")
      append('\n')
    }
  }

  /**
   * Statements that make [target] look like [source]. Drops are written commented out: they lose data, so they are
   * there to read and enable by hand. Tables are matched by name ignoring case, as are columns.
   */
  fun migration(source: DbSchemaModel, target: DbSchemaModel, dbms: Dbms): List<String> {
    if (!dbms.hasDialect) throw DbException(PhantomDbBundle.message("db.generic.unsupported"))
    val q = quoter(dbms)
    val out = ArrayList<String>()
    val targetTables = target.tables.values.associateBy { it.name.lowercase() }
    val sourceTables = source.tables.values.associateBy { it.name.lowercase() }
    for (table in source.tables.values) {
      val existing = targetTables[table.name.lowercase()]
      if (existing == null) {
        out += createTable(table, dbms, q)
        table.indexes.forEach { out += createIndex(table.name, it, q) }
        continue
      }
      val existingColumns = existing.columns.associateBy { it.name.lowercase() }
      for (column in table.columns) {
        val old = existingColumns[column.name.lowercase()]
        when {
          old == null -> out += "ALTER TABLE ${q(table.name)} ADD ${columnSql(column, q)};"
          !old.type.equals(column.type, ignoreCase = true) || old.nullable != column.nullable -> out += alterColumn(table.name, old, column, dbms, q)
        }
      }
      val sourceColumns = table.columns.map { it.name.lowercase() }.toSet()
      existing.columns.filter { it.name.lowercase() !in sourceColumns }.forEach { out += "-- ALTER TABLE ${q(table.name)} DROP COLUMN ${q(it.name)};" }
      if (!table.primaryKey.map { it.lowercase() }.equals(existing.primaryKey.map { it.lowercase() })) {
        if (existing.primaryKey.isNotEmpty()) out += "-- " + dropPrimaryKey(table.name, dbms, q)
        if (table.primaryKey.isNotEmpty()) out += "ALTER TABLE ${q(table.name)} ADD PRIMARY KEY (${table.primaryKey.joinToString { q(it) }});"
      }
      val existingIndexes = existing.indexes.associateBy { it.name.lowercase() }
      for (index in table.indexes) {
        val old = existingIndexes[index.name.lowercase()]
        if (old == null) out += createIndex(table.name, index, q)
        else if (old.unique != index.unique || old.columns.map { it.lowercase() } != index.columns.map { it.lowercase() }) {
          out += dropIndex(table.name, old.name, dbms, q)
          out += createIndex(table.name, index, q)
        }
      }
      val sourceIndexes = table.indexes.map { it.name.lowercase() }.toSet()
      existing.indexes.filter { it.name.lowercase() !in sourceIndexes }.forEach { out += "-- " + dropIndex(table.name, it.name, dbms, q) }
    }
    target.tables.values.filter { it.name.lowercase() !in sourceTables }.forEach { out += "-- DROP TABLE ${q(it.name)};" }
    return out
  }

  private fun createTable(table: DbTableModel, dbms: Dbms, q: (String) -> String): String {
    val lines = table.columns.map { "  " + columnSql(it, q) }.toMutableList()
    if (table.primaryKey.isNotEmpty()) lines += "  PRIMARY KEY (${table.primaryKey.joinToString { q(it) }})"
    return "CREATE TABLE ${q(table.name)} (\n${lines.joinToString(",\n")}\n);"
  }

  private fun columnSql(c: DbColumnModel, q: (String) -> String): String =
    q(c.name) + " " + c.type + (if (c.nullable) "" else " NOT NULL") + (c.default?.let { " DEFAULT $it" } ?: "")

  private fun alterColumn(table: String, old: DbColumnModel, c: DbColumnModel, dbms: Dbms, q: (String) -> String): String = when (dbms) {
    Dbms.MYSQL, Dbms.MARIADB -> "ALTER TABLE ${q(table)} MODIFY COLUMN ${columnSql(c, q)};"
    Dbms.POSTGRESQL -> buildList {
      if (!old.type.equals(c.type, ignoreCase = true)) add("ALTER TABLE ${q(table)} ALTER COLUMN ${q(c.name)} TYPE ${c.type};")
      if (old.nullable != c.nullable) add("ALTER TABLE ${q(table)} ALTER COLUMN ${q(c.name)} ${if (c.nullable) "DROP" else "SET"} NOT NULL;")
    }.joinToString("\n")
    Dbms.H2, Dbms.GENERIC -> buildList {
      if (!old.type.equals(c.type, ignoreCase = true)) add("ALTER TABLE ${q(table)} ALTER COLUMN ${q(c.name)} SET DATA TYPE ${c.type};")
      if (old.nullable != c.nullable) add("ALTER TABLE ${q(table)} ALTER COLUMN ${q(c.name)} SET ${if (c.nullable) "NULL" else "NOT NULL"};")
    }.joinToString("\n")
  }

  private fun dropPrimaryKey(table: String, dbms: Dbms, q: (String) -> String): String = when (dbms) {
    Dbms.POSTGRESQL -> "ALTER TABLE ${q(table)} DROP CONSTRAINT ${q(table + "_pkey")};"
    else -> "ALTER TABLE ${q(table)} DROP PRIMARY KEY;"
  }

  private fun createIndex(table: String, index: DbIndexModel, q: (String) -> String): String =
    "CREATE ${if (index.unique) "UNIQUE " else ""}INDEX ${q(index.name)} ON ${q(table)} (${index.columns.joinToString { q(it) }});"

  private fun dropIndex(table: String, index: String, dbms: Dbms, q: (String) -> String): String = when (dbms) {
    Dbms.MYSQL, Dbms.MARIADB -> "DROP INDEX ${q(index)} ON ${q(table)};"
    else -> "DROP INDEX ${q(index)};"
  }

  /** Quotes only names that need it, so the SQL reads like hand-written SQL. */
  private fun quoter(dbms: Dbms): (String) -> String {
    val quote = if (dbms.usesCatalogs) "`" else "\""
    val plain = Regex("[A-Za-z_][A-Za-z0-9_]*")
    return { name ->
      val foldsTo = when (dbms) { Dbms.POSTGRESQL -> name.lowercase(); Dbms.H2 -> name.uppercase(); else -> name }
      if (name.matches(plain) && name == foldsTo && !dev.phantom.db.core.SqlTokens.isKeyword(name)) name
      else quote + name.replace(quote, quote + quote) + quote
    }
  }
}
