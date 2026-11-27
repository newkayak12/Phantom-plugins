// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.exec

import dev.phantom.db.core.Dbms
import dev.phantom.db.core.PhantomDbBundle

/**
 * The statements that turn a table's columns and indexes from one definition into another, as edited in Modify Table.
 * A column or index keeps the name it had when loaded in [ColumnDef.original] / [IndexDef.original], so a changed name
 * is a rename and not a drop and an add. Defaults are SQL expressions as typed (`0`, `'new'`, `CURRENT_TIMESTAMP`).
 * MySQL restates a changed column whole, so its comment and AUTO_INCREMENT go into that statement or they are lost.
 */
object DbAlter {
  data class ColumnDef(val original: String?, val name: String, val type: String, val nullable: Boolean, val default: String?,
                       val comment: String? = null, val autoIncrement: Boolean = false)

  data class IndexDef(val original: String?, val name: String, val columns: List<String>, val unique: Boolean)

  fun statements(dbms: Dbms, table: DbTableRef, before: List<ColumnDef>, after: List<ColumnDef>,
                 indexesBefore: List<IndexDef> = emptyList(), indexesAfter: List<IndexDef> = emptyList()): List<String> {
    val generic = !dbms.hasDialect
    // without a dialect only the statements every database spells the same way are written; the rest is refused, not guessed
    fun unsupported(what: String): Nothing = throw DbException(PhantomDbBundle.message("db.modify.generic.unsupported", what))
    val t = DbNames.table(dbms, table)
    fun q(s: String) = DbNames.quote(dbms, s)
    val result = ArrayList<String>()

    // indexes go first when dropped (a column in one cannot be dropped in some databases) and last when created
    val keptIndexes = indexesAfter.mapNotNull { it.original }.toSet()
    val droppedIndexes = indexesBefore.filter { it.original != null && it.original !in keptIndexes } +
                         indexesAfter.filter { a -> a.original != null && indexesBefore.firstOrNull { it.original == a.original }?.let { it != a } == true }
                           .mapNotNull { a -> indexesBefore.first { it.original == a.original } }
    for (index in droppedIndexes) result += if (generic) unsupported(index.original!!) else dropIndex(dbms, table, index.original!!)

    val kept = after.mapNotNull { it.original }.toSet()
    for (column in before) if (column.original != null && column.original !in kept) result += "ALTER TABLE $t DROP COLUMN ${q(column.original)}"

    for (column in after) {
      val old = before.firstOrNull { it.original != null && it.original == column.original }
      if (old == null) {
        // ADD without COLUMN: Oracle and SQL Server reject the optional keyword, the others accept its absence
        result += "ALTER TABLE $t ADD ${if (generic) "" else "COLUMN "}${definition(dbms, column)}"
        if (!dbms.usesCatalogs && !column.comment.isNullOrBlank()) result += if (generic) unsupported(column.name) else commentOn(dbms, t, column)
        continue
      }
      if (old.name != column.name) {
        if (generic) unsupported(old.name)
        result += if (dbms == Dbms.H2) "ALTER TABLE $t ALTER COLUMN ${q(old.name)} RENAME TO ${q(column.name)}"
                  else "ALTER TABLE $t RENAME COLUMN ${q(old.name)} TO ${q(column.name)}"
      }
      val typeChanged = !old.type.equals(column.type, ignoreCase = true)
      val nullChanged = old.nullable != column.nullable
      val defaultChanged = old.default.orEmpty().trim() != column.default.orEmpty().trim()
      val commentChanged = old.comment.orEmpty() != column.comment.orEmpty()
      if (!typeChanged && !nullChanged && !defaultChanged && !commentChanged) continue
      val c = q(column.name)
      when (dbms) {
        // MySQL restates the whole column; anything left out (default, NOT NULL) would be lost
        Dbms.MYSQL, Dbms.MARIADB -> result += "ALTER TABLE $t MODIFY COLUMN ${definition(dbms, column)}"
        Dbms.GENERIC -> unsupported(column.name)
        Dbms.POSTGRESQL, Dbms.H2 -> {
          if (typeChanged) {
            result += "ALTER TABLE $t ALTER COLUMN $c " + (if (dbms == Dbms.POSTGRESQL) "TYPE " else "SET DATA TYPE ") + column.type
          }
          if (nullChanged) result += "ALTER TABLE $t ALTER COLUMN $c " + if (column.nullable) "DROP NOT NULL" else "SET NOT NULL"
          if (defaultChanged) {
            result += "ALTER TABLE $t ALTER COLUMN $c " + (column.default?.takeIf { it.isNotBlank() }?.let { "SET DEFAULT $it" } ?: "DROP DEFAULT")
          }
          if (commentChanged) result += commentOn(dbms, t, column)
        }
      }
    }

    val existing = indexesBefore.associateBy { it.original }
    for (index in indexesAfter) {
      if (index.original != null && existing[index.original] == index) continue
      result += "CREATE ${if (index.unique) "UNIQUE " else ""}INDEX ${q(index.name)} ON $t (${index.columns.joinToString { q(it) }})"
    }
    return result
  }

  private fun definition(dbms: Dbms, column: ColumnDef): String = buildString {
    append(DbNames.quote(dbms, column.name)).append(' ').append(column.type.trim())
    column.default?.takeIf { it.isNotBlank() }?.let { append(" DEFAULT ").append(it.trim()) }
    append(if (column.nullable) (if (dbms.usesCatalogs) " NULL" else "") else " NOT NULL")
    if (dbms.usesCatalogs) {
      if (column.autoIncrement) append(" AUTO_INCREMENT")
      column.comment?.takeIf { it.isNotBlank() }?.let { append(" COMMENT ").append(literal(dbms, it)) }
    }
  }

  private fun commentOn(dbms: Dbms, t: String, column: ColumnDef): String =
    "COMMENT ON COLUMN $t.${DbNames.quote(dbms, column.name)} IS " + (column.comment?.takeIf { it.isNotBlank() }?.let { literal(dbms, it) } ?: "NULL")

  private fun literal(dbms: Dbms, text: String): String =
    "'" + (if (dbms.usesCatalogs) text.replace("\\", "\\\\") else text).replace("'", "''") + "'"

  private fun dropIndex(dbms: Dbms, table: DbTableRef, name: String): String = when (dbms) {
    Dbms.MYSQL, Dbms.MARIADB -> "DROP INDEX ${DbNames.quote(dbms, name)} ON ${DbNames.table(dbms, table)}"
    // an index lives in the table's schema
    Dbms.POSTGRESQL, Dbms.H2, Dbms.GENERIC -> "DROP INDEX ${DbNames.qualified(dbms, table.schema, name)}"
  }
}
