// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.exec

import com.intellij.openapi.project.Project
import dev.phantom.db.core.dialect
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

  /** [name] in quotes whatever it is, for a name that was quoted where it was written. */
  fun quoted(dbms: Dbms, name: String): String {
    val q = if (dbms.usesCatalogs) '`' else '"'
    return "$q${name.replace("$q", "$q$q")}$q"
  }

  /**
   * A name the database reports, written so it means exactly that name: [quote]d when it needs quotes or when it
   * is not in the case an unquoted name folds to (upper in H2, lower in PostgreSQL).
   */
  fun identifier(dbms: Dbms, name: String): String {
    val quoted = quote(dbms, name)
    if (quoted != name) return quoted
    val folds = when (dbms) {
      Dbms.H2 -> name != name.uppercase()
      Dbms.POSTGRESQL -> name != name.lowercase()
      else -> false
    }
    return if (folds) "\"${name.replace("\"", "\"\"")}\"" else name
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
    val dbms = dataSource.dialect()
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
      is Target.Routine -> "-- ${dataSource.dialect().displayName} does not report the source of ${target.routine.name}\n"
      is Target.Trigger -> "-- ${dataSource.dialect().displayName} does not report the source of ${target.trigger.name}\n"
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
        ?.let { postgresViewDdl(target.ref, it) }
      is Target.Routine -> all(c, "SELECT pg_get_functiondef(p.oid) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace " +
                                  "WHERE n.nspname = ? AND p.proname = ?", target.routine.schema.name, target.routine.name)
      is Target.Trigger -> all(c, "SELECT pg_get_triggerdef(t.oid, true) FROM pg_trigger t JOIN pg_class r ON r.oid = t.tgrelid " +
                                  "JOIN pg_namespace n ON n.oid = r.relnamespace WHERE n.nspname = ? AND t.tgname = ?",
                               target.trigger.schema.name, target.trigger.name)
    }
    Dbms.GENERIC -> null
    Dbms.H2 -> when (target) {
      is Target.Table -> if (!target.ref.isView) null else single(c, "SELECT VIEW_DEFINITION FROM INFORMATION_SCHEMA.VIEWS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?",
                                                                    target.ref.schema.orEmpty(), target.ref.name)
        ?.let { "CREATE VIEW ${DbNames.table(dbms, target.ref)} AS\n$it" }
      else -> null
    }
  }

  /** PostgreSQL has no CREATE OR REPLACE for a materialized view. */
  internal fun postgresViewDdl(ref: DbTableRef, body: String): String =
    (if (ref.isMaterializedView) "CREATE MATERIALIZED VIEW " else "CREATE OR REPLACE VIEW ") + DbNames.table(Dbms.POSTGRESQL, ref) + " AS\n" + body.trimEnd()

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

/**
 * SQL Generator (Ctrl+Alt+G, as in DataGrip): for a table, its DDL followed by a SELECT, INSERT, UPDATE and DELETE of
 * its columns, keyed by the primary key (all columns when it has none), with `?` for the values.
 */
object DbSqlGenerator {
  fun text(dbms: Dbms, table: DbTableRef, columns: List<DbColumnInfo>, ddl: String?): String {
    val name = DbNames.table(dbms, table)
    val names = columns.map { DbNames.identifier(dbms, it.name) }
    val keys = columns.filter { it.primaryKey }.ifEmpty { columns }.map { DbNames.identifier(dbms, it.name) }
    val where = keys.joinToString(" AND ") { "$it = ?" }
    val list = names.joinToString(",\n       ")
    return buildString {
      if (!ddl.isNullOrBlank()) append(ddl.trimEnd().removeSuffix(";")).append(";\n\n")
      append("SELECT ").append(list.ifEmpty { "*" }).append("\nFROM ").append(name).append(";\n\n")
      if (table.isView || names.isEmpty()) return@buildString
      append("INSERT INTO ").append(name).append(" (").append(names.joinToString()).append(")\nVALUES (")
        .append(names.joinToString { "?" }).append(");\n\n")
      val set = columns.filterNot { it.primaryKey }.map { DbNames.identifier(dbms, it.name) }
      if (set.isNotEmpty()) {
        append("UPDATE ").append(name).append("\nSET ").append(set.joinToString(",\n    ") { "$it = ?" }).append("\nWHERE ").append(where).append(";\n\n")
      }
      append("DELETE FROM ").append(name).append("\nWHERE ").append(where).append(";\n")
    }
  }
}
