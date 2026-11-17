// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.erd

import dev.phantom.db.core.Dbms
import dev.phantom.db.core.exec.DbColumnModel
import dev.phantom.db.core.exec.DbIndexModel
import dev.phantom.db.core.exec.DbNames
import dev.phantom.db.core.exec.DbSchemaDiff
import dev.phantom.db.core.exec.DbSchemaModel
import dev.phantom.db.core.exec.DbTableModel

/** Writes a design as SQL: all of it (CREATE), or what a database is missing (ALTER). [only] narrows either to one table. */
object ErdSql {
  private const val NO_CHANGES = "-- No changes"

  fun create(doc: ErdDocument, only: String? = null): String {
    val scoped = doc.tables.filter { only == null || it.name == only }
    val statements = createTables(doc, scoped) + doc.relations.filter { r -> scoped.any { it.name == r.table } }.map { addForeignKey(doc.dbms, it) }
    return statements.joinToString("\n\n").ifEmpty { NO_CHANGES }
  }

  /** Statements that bring [live] to the design. As in Compare With, anything that would lose data is written commented out. */
  fun alter(doc: ErdDocument, live: ErdDocument, only: String? = null): String {
    val dbms = doc.dbms
    val scoped = doc.tables.filter { only == null || it.name == only }
    val (existing, added) = scoped.partition { live.find(it.name) != null }
    val liveScoped = live.tables.filter { only == null || it.name.equals(only, ignoreCase = true) }
    val out = ArrayList(createTables(doc, added))
    out += DbSchemaDiff.migration(model(dbms, existing), model(dbms, liveScoped.map { withDesignTypes(it, doc) }), dbms)
    for (table in existing) out += changedAttributes(dbms, table, live.find(table.name)!!)
    val wanted = doc.relations.filter { r -> scoped.any { it.name == r.table } }
    val present = live.relations.filter { r -> liveScoped.any { it.name == r.table } }
    wanted.filter { r -> present.none { same(it, r) } }.forEach { out += addForeignKey(dbms, it) }
    present.filter { r -> doc.tables.any { it.name.equals(r.table, ignoreCase = true) } && wanted.none { same(it, r) } }.forEach { out += "-- " + dropForeignKey(dbms, it) }
    return out.joinToString("\n").ifEmpty { NO_CHANGES }
  }

  /** [live] with the design's spelling of each type that means the same, so `BIGINT` and PostgreSQL's `int8` are no change. */
  private fun withDesignTypes(live: ErdTable, doc: ErdDocument): ErdTable {
    val design = doc.find(live.name) ?: return live
    return live.copy(columns = live.columns.map { c ->
      val wanted = design.columns.firstOrNull { it.name.equals(c.name, ignoreCase = true) }
      if (wanted != null && sameType(wanted.type, c.type)) c.copy(type = wanted.type) else c
    })
  }

  private fun sameType(a: String, b: String) = canonical(a) == canonical(b)

  private fun canonical(type: String): String {
    val text = type.trim().lowercase().replace(Regex("\\s+"), " ")
    val name = text.substringBefore('(').trim()
    val arguments = text.substringAfter('(', "").substringBefore(')').replace(" ", "")
    val base = TYPE_ALIASES[name] ?: name
    // an integer's display width (MySQL's INT(11)) is not part of the type
    return if (arguments.isEmpty() || base in INTEGERS) base else "$base($arguments)"
  }

  private val INTEGERS = setOf("bigint", "int", "smallint", "mediumint")
  private val TYPE_ALIASES = mapOf(
    "int8" to "bigint", "bigserial" to "bigint", "int4" to "int", "integer" to "int", "serial" to "int", "int2" to "smallint", "smallserial" to "smallint",
    "bool" to "boolean", "character varying" to "varchar", "character" to "char", "bpchar" to "char", "numeric" to "decimal",
    "timestamp without time zone" to "timestamp", "character large object" to "clob",
  )

  private fun ErdDocument.find(name: String) = tables.firstOrNull { it.name.equals(name, ignoreCase = true) }

  private fun same(a: ErdRelation, b: ErdRelation) = a.table.equals(b.table, ignoreCase = true) && a.refTable.equals(b.refTable, ignoreCase = true) &&
                                                     a.columns.map { it.lowercase() } == b.columns.map { it.lowercase() } &&
                                                     a.refColumns.map { it.lowercase() } == b.refColumns.map { it.lowercase() }

  private fun createTables(doc: ErdDocument, tables: List<ErdTable>): List<String> {
    val (filled, empty) = tables.partition { it.columns.isNotEmpty() }
    return DbSchemaDiff.migration(model(doc.dbms, filled), DbSchemaModel("", emptyMap()), doc.dbms) +
           filled.flatMap { comments(doc.dbms, it) } + empty.map { "-- ${it.name} has no columns" }
  }

  private fun model(dbms: Dbms, tables: List<ErdTable>) = DbSchemaModel("", tables.associate { t ->
    t.name to DbTableModel(t.name, t.columns.map { column(dbms, it) }, t.primaryKey, t.indexes.map { DbIndexModel(it.name, it.unique, it.columns) })
  })

  private fun column(dbms: Dbms, c: ErdColumn) = DbColumnModel(c.name, c.type, c.nullable && !c.primaryKey, c.default, extra(dbms, c))

  private fun isMysql(dbms: Dbms) = dbms == Dbms.MYSQL || dbms == Dbms.MARIADB

  private fun extra(dbms: Dbms, c: ErdColumn): String? = listOfNotNull(
    when {
      !c.autoIncrement -> null
      isMysql(dbms) -> "AUTO_INCREMENT"
      c.type.endsWith("serial", ignoreCase = true) -> null // PostgreSQL's serial types are their own sequence
      else -> "GENERATED BY DEFAULT AS IDENTITY"
    },
    c.comment.takeIf { isMysql(dbms) && it.isNotEmpty() }?.let { "COMMENT ${literal(dbms, it)}" },
  ).joinToString(" ").ifEmpty { null }

  /** The comments CREATE TABLE did not write. */
  private fun comments(dbms: Dbms, t: ErdTable): List<String> = listOfNotNull(t.comment.takeIf { it.isNotEmpty() }?.let { tableComment(dbms, t.name, it) }) +
    (if (isMysql(dbms)) emptyList() else t.columns.filter { it.comment.isNotEmpty() }.map { columnComment(dbms, t.name, it.name, it.comment) })

  private fun q(dbms: Dbms, name: String) = DbNames.identifier(dbms, name)

  private fun tableComment(dbms: Dbms, table: String, comment: String) =
    if (isMysql(dbms)) "ALTER TABLE ${q(dbms, table)} COMMENT = ${literal(dbms, comment)};"
    else "COMMENT ON TABLE ${q(dbms, table)} IS ${if (comment.isEmpty()) "NULL" else literal(dbms, comment)};"

  private fun columnComment(dbms: Dbms, table: String, column: String, comment: String) =
    "COMMENT ON COLUMN ${q(dbms, table)}.${q(dbms, column)} IS ${if (comment.isEmpty()) "NULL" else literal(dbms, comment)};"

  private fun literal(dbms: Dbms, text: String): String {
    val escaped = if (isMysql(dbms)) text.replace("\\", "\\\\") else text
    return "'" + escaped.replace("'", "''") + "'"
  }

  /** What the schema comparison does not look at: defaults, auto increment and comments of columns both sides have. */
  private fun changedAttributes(dbms: Dbms, table: ErdTable, live: ErdTable): List<String> {
    val out = ArrayList<String>()
    val name = q(dbms, table.name)
    if (table.comment != live.comment) out += tableComment(dbms, table.name, table.comment)
    for (c in table.columns) {
      val old = live.columns.firstOrNull { it.name.equals(c.name, ignoreCase = true) }
      if (old == null) {
        // the comparison's ADD wrote the column; only MySQL's definition carries its comment
        if (!isMysql(dbms) && c.comment.isNotEmpty()) out += columnComment(dbms, table.name, c.name, c.comment)
        continue
      }
      val default = !c.default.orEmpty().equals(old.default.orEmpty(), ignoreCase = true)
      val auto = c.autoIncrement != old.autoIncrement
      val comment = c.comment != old.comment
      if (isMysql(dbms)) {
        // when the type or nullability changed too, the comparison's MODIFY already wrote the whole column
        val modified = !sameType(c.type, old.type) || (c.nullable && !c.primaryKey) != (old.nullable && !old.primaryKey)
        if (!modified && (default || auto || comment)) out += "ALTER TABLE $name MODIFY COLUMN ${DbSchemaDiff.columnSql(column(dbms, c)) { q(dbms, it) }};"
        continue
      }
      val col = q(dbms, c.name)
      if (default) out += "ALTER TABLE $name ALTER COLUMN $col " + (c.default?.let { "SET DEFAULT $it;" } ?: "DROP DEFAULT;")
      if (auto) out += when {
        !c.autoIncrement -> "ALTER TABLE $name ALTER COLUMN $col DROP IDENTITY;"
        dbms == Dbms.POSTGRESQL -> "ALTER TABLE $name ALTER COLUMN $col ADD GENERATED BY DEFAULT AS IDENTITY;"
        else -> "ALTER TABLE $name ALTER COLUMN ${DbSchemaDiff.columnSql(column(dbms, c)) { q(dbms, it) }};"
      }
      if (comment) out += columnComment(dbms, table.name, c.name, c.comment)
    }
    return out
  }

  private fun addForeignKey(dbms: Dbms, r: ErdRelation) =
    "ALTER TABLE ${q(dbms, r.table)} ADD CONSTRAINT ${q(dbms, r.name)} FOREIGN KEY (${r.columns.joinToString { q(dbms, it) }}) " +
    "REFERENCES ${q(dbms, r.refTable)} (${r.refColumns.joinToString { q(dbms, it) }});"

  private fun dropForeignKey(dbms: Dbms, r: ErdRelation) =
    "ALTER TABLE ${q(dbms, r.table)} DROP ${if (isMysql(dbms)) "FOREIGN KEY" else "CONSTRAINT"} ${q(dbms, r.name)};"
}
