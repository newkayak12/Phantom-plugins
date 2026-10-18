// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.exec

import com.intellij.openapi.project.Project
import dev.phantom.db.core.DbDataSource
import dev.phantom.db.core.Dbms
import dev.phantom.db.core.SqlTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection

/** Quoting and qualifying names for SQL, the way the tree's own "SELECT * FROM" does. */
object DbNames {
  fun quote(dbms: Dbms, name: String): String {
    if (name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) && !SqlTokens.isKeyword(name)) return name
    val q = if (dbms.usesCatalogs) '`' else '"'
    return "$q${name.replace("$q", "$q$q")}$q"
  }

  /** `schema.table` (`catalog.table` in MySQL), or just the table when it has no qualifier. */
  fun table(dbms: Dbms, ref: DbTableRef): String = qualified(dbms, if (dbms.usesCatalogs) ref.catalog else ref.schema, ref.name)

  fun qualified(dbms: Dbms, qualifier: String?, name: String): String = qualifier?.let { quote(dbms, it) + "." }.orEmpty() + quote(dbms, name)
}

/**
 * The DDL of a table, view, routine or trigger: what the database itself reports where it can (`SHOW CREATE` in MySQL,
 * `pg_get_viewdef` / `pg_get_functiondef` / `pg_get_triggerdef` in PostgreSQL, the view text in H2), otherwise a
 * CREATE TABLE written from the metadata.
 */
object DbDdl {
  sealed interface Target {
    data class Table(val ref: DbTableRef) : Target
    data class Routine(val routine: DbRoutine) : Target
    data class Trigger(val trigger: DbTrigger) : Target
  }

  suspend fun ddl(project: Project, dataSource: DbDataSource, target: Target): String {
    val dbms = dataSource.dbms
    val reported = DbConnectionService.getInstance(project).withConnection(dataSource, DbConnectionService.Purpose.METADATA) { c ->
      withContext(Dispatchers.IO) { runCatching { reported(c, dbms, target) }.getOrNull() }
    }
    if (reported != null) return reported.trimEnd().let { if (it.endsWith(";")) it else "$it;" } + "\n"
    return when (target) {
      is Target.Table -> {
        val objects = DbObjectCache.getInstance(project)
        createTable(dbms, target.ref, objects.columnDetails(dataSource, target.ref), objects.primaryKey(dataSource, target.ref),
                    objects.foreignKeys(dataSource, target.ref), objects.indexes(dataSource, target.ref))
      }
      is Target.Routine -> "-- ${dataSource.dbms.displayName} does not report the source of ${target.routine.name}\n"
      is Target.Trigger -> "-- ${dataSource.dbms.displayName} does not report the source of ${target.trigger.name}\n"
    }
  }

  private fun reported(c: Connection, dbms: Dbms, target: Target): String? = when (dbms) {
    Dbms.MYSQL, Dbms.MARIADB -> {
      val (sql, column) = when (target) {
        is Target.Table -> (if (target.ref.isView) "SHOW CREATE VIEW " else "SHOW CREATE TABLE ") + DbNames.table(dbms, target.ref) to 2
        is Target.Routine -> (if (target.routine.function) "SHOW CREATE FUNCTION " else "SHOW CREATE PROCEDURE ") +
                             DbNames.qualified(dbms, target.routine.schema.catalog, target.routine.name) to 3
        is Target.Trigger -> "SHOW CREATE TRIGGER " + DbNames.qualified(dbms, target.trigger.schema.catalog, target.trigger.name) to 3
      }
      c.createStatement().use { s -> s.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(column) else null } }
    }
    Dbms.POSTGRESQL -> when (target) {
      is Target.Table -> if (!target.ref.isView) null else single(c, "SELECT pg_get_viewdef(?::regclass, true)", DbNames.table(dbms, target.ref))
        ?.let { "CREATE OR REPLACE VIEW ${DbNames.table(dbms, target.ref)} AS\n${it.trimEnd()}" }
      is Target.Routine -> all(c, "SELECT pg_get_functiondef(p.oid) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace " +
                                  "WHERE n.nspname = ? AND p.proname = ?", target.routine.schema.name, target.routine.name)
      is Target.Trigger -> all(c, "SELECT pg_get_triggerdef(t.oid, true) FROM pg_trigger t JOIN pg_class r ON r.oid = t.tgrelid " +
                                  "JOIN pg_namespace n ON n.oid = r.relnamespace WHERE n.nspname = ? AND t.tgname = ?",
                               target.trigger.schema.name, target.trigger.name)
    }
    Dbms.H2 -> when (target) {
      is Target.Table -> if (!target.ref.isView) null else single(c, "SELECT VIEW_DEFINITION FROM INFORMATION_SCHEMA.VIEWS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?",
                                                                    target.ref.schema.orEmpty(), target.ref.name)
        ?.let { "CREATE VIEW ${DbNames.table(dbms, target.ref)} AS\n$it" }
      else -> null
    }
  }

  private fun single(c: Connection, sql: String, vararg args: String): String? = all(c, sql, *args)

  private fun all(c: Connection, sql: String, vararg args: String): String? = c.prepareStatement(sql).use { ps ->
    args.forEachIndexed { i, a -> ps.setString(i + 1, a) }
    ps.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
  }.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.joinToString(";\n\n")

  /** CREATE TABLE with columns, primary key and foreign keys, then CREATE INDEX for the other indexes. */
  fun createTable(dbms: Dbms, table: DbTableRef, columns: List<DbColumnDetail>, pk: DbPrimaryKey, fks: List<DbForeignKey>, indexes: List<DbIndex>): String {
    val name = DbNames.table(dbms, table)
    fun q(s: String) = DbNames.quote(dbms, s)
    val lines = ArrayList<String>()
    for (c in columns) {
      lines += buildString {
        append(q(c.name)).append(' ').append(c.typeName)
        if (!c.nullable) append(" NOT NULL")
        if (c.default != null && !c.autoIncrement) append(" DEFAULT ").append(c.default)
        if (c.autoIncrement && dbms.usesCatalogs) append(" AUTO_INCREMENT")
      }
    }
    if (pk.columns.isNotEmpty()) lines += (pk.name?.let { "CONSTRAINT ${q(it)} " }.orEmpty()) + "PRIMARY KEY (${pk.columns.joinToString { q(it) }})"
    for (fk in fks) {
      lines += (fk.name?.let { "CONSTRAINT ${q(it)} " }.orEmpty()) +
               "FOREIGN KEY (${fk.columns.joinToString { q(it) }}) REFERENCES ${DbNames.table(dbms, fk.refTable)} (${fk.refColumns.joinToString { q(it) }})"
    }
    return buildString {
      append("CREATE TABLE ").append(name).append(" (\n")
      append(lines.joinToString(",\n") { "    $it" })
      append("\n);\n")
      // the database makes the index behind a foreign key itself (named after the key in MySQL and H2)
      val ownIndexes = indexes.filterNot { i -> !i.unique && fks.any { fk -> fk.columns == i.columns && fk.name != null && i.name.startsWith(fk.name, ignoreCase = true) } }
      for (index in ownIndexes) {
        append("\nCREATE ").append(if (index.unique) "UNIQUE " else "").append("INDEX ").append(q(index.name))
          .append(" ON ").append(name).append(" (").append(index.columns.joinToString { q(it) }).append(");")
      }
      if (ownIndexes.isNotEmpty()) append('\n')
    }
  }
}
