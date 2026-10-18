// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.exec

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.project.Project
import dev.phantom.db.core.DbDataSource
import dev.phantom.db.core.Dbms
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.ResultSet
import java.util.concurrent.ConcurrentHashMap

/** A column with what DDL needs beyond [DbColumnInfo]: the default expression and whether the database fills it. */
data class DbColumnDetail(val name: String, val typeName: String, val nullable: Boolean, val default: String?, val autoIncrement: Boolean)

/** The primary key: its constraint name (may be null) and columns in key order. */
data class DbPrimaryKey(val name: String?, val columns: List<String>)

/**
 * A foreign key of [table] (the referencing side): `columns` reference `refColumns` of `refTable`, pairwise.
 * Several columns make one composite key.
 */
data class DbForeignKey(val name: String?, val table: DbTableRef, val columns: List<String>, val refTable: DbTableRef, val refColumns: List<String>)

data class DbIndex(val name: String, val columns: List<String>, val unique: Boolean)

data class DbRoutine(val schema: DbSchemaRef, val name: String, val function: Boolean)

data class DbTrigger(val schema: DbSchemaRef, val name: String, val table: String, val timing: String, val event: String)

/**
 * Keys, indexes, routines and triggers, loaded on the schema-tree connection when a tree folder is first expanded and
 * kept in memory until Refresh, like [DbMetadataCache]. Each kind is a separate call, so expanding Indexes never pays
 * for foreign keys.
 */
@Service(Service.Level.PROJECT)
class DbObjectCache(private val project: Project) {
  private val details = ConcurrentHashMap<DbTableRef, List<DbColumnDetail>>()
  private val primaryKeys = ConcurrentHashMap<DbTableRef, DbPrimaryKey>()
  private val foreignKeys = ConcurrentHashMap<DbTableRef, List<DbForeignKey>>()
  private val referencing = ConcurrentHashMap<DbTableRef, List<DbForeignKey>>()
  private val indexes = ConcurrentHashMap<DbTableRef, List<DbIndex>>()
  private val routines = ConcurrentHashMap<DbSchemaRef, List<DbRoutine>>()
  private val triggers = ConcurrentHashMap<DbSchemaRef, List<DbTrigger>>()

  suspend fun columnDetails(dataSource: DbDataSource, table: DbTableRef): List<DbColumnDetail> = details[table] ?: metadata(dataSource) { md, _ ->
    md.getColumns(table.catalog, table.schema?.let { escape(md, it) }, escape(md, table.name), "%").use { rs ->
      rows(rs) {
        if (it.getString("TABLE_NAME") != table.name) null
        else DbColumnDetail(it.getString("COLUMN_NAME"), typeText(it), it.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                            it.getString("COLUMN_DEF")?.takeIf { d -> d.isNotBlank() }?.let { d -> defaultExpression(dataSource.dbms, d) },
                            runCatching { it.getString("IS_AUTOINCREMENT") == "YES" }.getOrDefault(false))
      }
    }
  }.also { details[table] = it }

  suspend fun primaryKey(dataSource: DbDataSource, table: DbTableRef): DbPrimaryKey = primaryKeys[table] ?: metadata(dataSource) { md, _ ->
    md.getPrimaryKeys(table.catalog, table.schema, table.name).use { rs ->
      val keys = rows(rs) { Triple(it.getString("PK_NAME"), it.getShort("KEY_SEQ").toInt(), it.getString("COLUMN_NAME")) }.sortedBy { it.second }
      DbPrimaryKey(keys.firstOrNull()?.first, keys.map { it.third })
    }
  }.also { primaryKeys[table] = it }

  /** Foreign keys of [table] pointing at other tables. */
  suspend fun foreignKeys(dataSource: DbDataSource, table: DbTableRef): List<DbForeignKey> = foreignKeys[table] ?: metadata(dataSource) { md, _ ->
    md.getImportedKeys(table.catalog, table.schema, table.name).use { keys(rs = it, dataSourceId = dataSource.id) }
  }.also { foreignKeys[table] = it }

  /** Foreign keys of other tables pointing at [table]. */
  suspend fun referencingKeys(dataSource: DbDataSource, table: DbTableRef): List<DbForeignKey> = referencing[table] ?: metadata(dataSource) { md, _ ->
    md.getExportedKeys(table.catalog, table.schema, table.name).use { keys(rs = it, dataSourceId = dataSource.id) }
  }.also { referencing[table] = it }

  /** Indexes other than the one backing the primary key, which the Keys folder already shows. */
  suspend fun indexes(dataSource: DbDataSource, table: DbTableRef): List<DbIndex> = indexes[table] ?: run {
    val pk = primaryKey(dataSource, table)
    metadata(dataSource) { md, _ ->
      // approximate = true: no statistics refresh, which on MySQL can scan the table
      md.getIndexInfo(table.catalog, table.schema, table.name, false, true).use { rs ->
        rows(rs) {
          val name = it.getString("INDEX_NAME") ?: return@rows null
          if (it.getShort("TYPE") == DatabaseMetaData.tableIndexStatistic) return@rows null
          Triple(name, it.getShort("ORDINAL_POSITION").toInt() to it.getString("COLUMN_NAME"), !it.getBoolean("NON_UNIQUE"))
        }
      }.groupBy { it.first }.map { (name, parts) ->
        DbIndex(name, parts.sortedBy { it.second.first }.mapNotNull { it.second.second }, parts.first().third)
      }.filterNot { isPrimaryKeyIndex(it, pk) }.sortedBy { it.name.lowercase() }
    }
  }.also { indexes[table] = it }

  suspend fun routines(dataSource: DbDataSource, schema: DbSchemaRef): List<DbRoutine> = routines[schema] ?: metadata(dataSource) { md, _ ->
    val procedures = runCatching {
      md.getProcedures(schema.catalog, schema.schema?.let { escape(md, it) }, "%").use { rs ->
        rows(rs) { DbRoutine(schema, it.getString("PROCEDURE_NAME"), it.getShort("PROCEDURE_TYPE") == DatabaseMetaData.procedureReturnsResult.toShort()) }
      }
    }.getOrDefault(emptyList())
    val functions = runCatching {
      md.getFunctions(schema.catalog, schema.schema?.let { escape(md, it) }, "%").use { rs -> rows(rs) { DbRoutine(schema, it.getString("FUNCTION_NAME"), true) } }
    }.getOrDefault(emptyList())
    // drivers list a function under both calls; the function entry wins
    (functions + procedures.filter { p -> functions.none { it.name == p.name } })
      .filter { r -> !r.name.startsWith("pg_") && r.name.isNotEmpty() }
      .distinctBy { it.name to it.function }
      .sortedBy { it.name.lowercase() }
  }.also { routines[schema] = it }

  suspend fun triggers(dataSource: DbDataSource, schema: DbSchemaRef): List<DbTrigger> = triggers[schema] ?: metadata(dataSource) { _, c ->
    val sql = when (dataSource.dbms) {
      Dbms.MYSQL, Dbms.MARIADB ->
        "SELECT TRIGGER_NAME, EVENT_OBJECT_TABLE, ACTION_TIMING, EVENT_MANIPULATION FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA = ? ORDER BY TRIGGER_NAME"
      Dbms.POSTGRESQL ->
        "SELECT trigger_name, event_object_table, action_timing, string_agg(event_manipulation, ' OR ') FROM information_schema.triggers " +
        "WHERE trigger_schema = ? GROUP BY trigger_name, event_object_table, action_timing ORDER BY trigger_name"
      Dbms.H2 ->
        "SELECT TRIGGER_NAME, EVENT_OBJECT_TABLE, ACTION_TIMING, EVENT_MANIPULATION FROM INFORMATION_SCHEMA.TRIGGERS WHERE TRIGGER_SCHEMA = ? ORDER BY TRIGGER_NAME"
    }
    runCatching {
      c.prepareStatement(sql).use { ps ->
        ps.setString(1, schema.name)
        ps.executeQuery().use { rs -> rows(rs) { DbTrigger(schema, it.getString(1), it.getString(2), it.getString(3).orEmpty(), it.getString(4).orEmpty()) } }
      }
    }.getOrDefault(emptyList())
  }.also { triggers[schema] = it }

  fun cachedForeignKeys(table: DbTableRef): List<DbForeignKey>? = foreignKeys[table]

  fun invalidate(dataSourceId: String) {
    for (map in listOf(details, primaryKeys, foreignKeys, referencing, indexes)) map.keys.removeIf { it.dataSourceId == dataSourceId }
    for (map in listOf(routines, triggers)) map.keys.removeIf { it.dataSourceId == dataSourceId }
  }

  private fun keys(rs: ResultSet, dataSourceId: String): List<DbForeignKey> {
    class Part(val name: String?, val seq: Int, val fk: DbTableRef, val fkColumn: String, val pk: DbTableRef, val pkColumn: String)
    val parts = rows(rs) {
      Part(it.getString("FK_NAME"), it.getShort("KEY_SEQ").toInt(),
           DbTableRef(dataSourceId, it.getString("FKTABLE_CAT"), it.getString("FKTABLE_SCHEM"), it.getString("FKTABLE_NAME"), "TABLE"),
           it.getString("FKCOLUMN_NAME"),
           DbTableRef(dataSourceId, it.getString("PKTABLE_CAT"), it.getString("PKTABLE_SCHEM"), it.getString("PKTABLE_NAME"), "TABLE"),
           it.getString("PKCOLUMN_NAME"))
    }
    // unnamed keys (some drivers) are told apart by the table pair and restart of KEY_SEQ
    val groups = LinkedHashMap<String, MutableList<Part>>()
    var unnamed = 0
    for (p in parts) {
      val key = p.name ?: run { if (p.seq == 1) unnamed++; "#$unnamed ${p.fk.name}->${p.pk.name}" }
      groups.getOrPut("$key|${p.fk.name}|${p.pk.name}") { ArrayList() }.add(p)
    }
    return groups.values.map { g ->
      val sorted = g.sortedBy { it.seq }
      DbForeignKey(sorted[0].name, sorted[0].fk, sorted.map { it.fkColumn }, sorted[0].pk, sorted.map { it.pkColumn })
    }
  }

  private fun <T> rows(rs: ResultSet, read: (ResultSet) -> T?): List<T> {
    val result = ArrayList<T>()
    while (rs.next()) read(rs)?.let(result::add)
    return result
  }

  private fun typeText(rs: ResultSet): String {
    val type = rs.getString("TYPE_NAME").orEmpty()
    val size = rs.getInt("COLUMN_SIZE").takeUnless { rs.wasNull() }
    val scale = rs.getInt("DECIMAL_DIGITS").takeUnless { rs.wasNull() }
    return when {
      size == null -> type
      type.uppercase() in SIZED_TYPES -> "$type($size)"
      type.uppercase() in SCALED_TYPES -> if (scale != null && scale > 0) "$type($size,$scale)" else "$type($size)"
      else -> type
    }
  }

  private suspend fun <T> metadata(dataSource: DbDataSource, block: (DatabaseMetaData, Connection) -> T): T =
    DbConnectionService.getInstance(project).withConnection(dataSource, DbConnectionService.Purpose.METADATA) { c ->
      withContext(Dispatchers.IO) { block(c.metaData, c) }
    }

  private fun escape(md: DatabaseMetaData, name: String): String {
    val esc = runCatching { md.searchStringEscape }.getOrNull().orEmpty()
    if (esc.isEmpty()) return name
    return name.replace(esc, esc + esc).replace("_", esc + "_").replace("%", esc + "%")
  }

  companion object {
    private val SIZED_TYPES = setOf("VARCHAR", "CHAR", "CHARACTER", "CHARACTER VARYING", "VARBINARY", "BINARY", "NVARCHAR", "NCHAR", "BPCHAR")
    private val SCALED_TYPES = setOf("DECIMAL", "NUMERIC")

    fun getInstance(project: Project): DbObjectCache = project.service()
    fun getInstanceIfCreated(project: Project): DbObjectCache? = project.serviceIfCreated()

    /**
     * MySQL reports a literal default without its quotes (`abc` for `DEFAULT 'abc'`); put them back so the value can
     * go into SQL as it is. Numbers, NULL, `CURRENT_TIMESTAMP` and `(expressions)` stay as they are.
     */
    internal fun defaultExpression(dbms: Dbms, value: String): String {
      if (dbms != Dbms.MYSQL) return value
      val v = value.trim()
      val bare = v.matches(Regex("-?\\d+(\\.\\d+)?")) || v.equals("NULL", ignoreCase = true) || v.startsWith("(") || v.startsWith("'") ||
                 v.matches(Regex("(?i)(current_timestamp|now|localtime|localtimestamp|current_date|current_time)(\\(\\d*\\))?")) ||
                 v.startsWith("b'") || v.startsWith("0x")
      return if (bare) v else "'" + v.replace("'", "''") + "'"
    }

    /** The unique index a database creates for the primary key (`PRIMARY` in MySQL, `<table>_pkey` in PostgreSQL). */
    internal fun isPrimaryKeyIndex(index: DbIndex, pk: DbPrimaryKey): Boolean =
      index.unique && pk.columns.isNotEmpty() && index.columns == pk.columns &&
      (index.name.equals("PRIMARY", ignoreCase = true) || index.name == pk.name || index.name.startsWith("PRIMARY_KEY", ignoreCase = true) ||
       index.name.endsWith("_pkey"))
  }
}
