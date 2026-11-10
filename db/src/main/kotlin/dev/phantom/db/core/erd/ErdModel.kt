// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.erd

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.phantom.db.core.Dbms

data class ErdColumn(
  val name: String,
  val type: String,
  val logicalName: String = "",
  val primaryKey: Boolean = false,
  val nullable: Boolean = true,
  val autoIncrement: Boolean = false,
  /** A SQL expression, written as it goes after DEFAULT: `0`, `'new'`, `CURRENT_TIMESTAMP`. */
  val default: String? = null,
  val comment: String = "",
)

data class ErdIndex(val name: String, val unique: Boolean, val columns: List<String>)

data class ErdTable(
  val name: String,
  val logicalName: String = "",
  val comment: String = "",
  val x: Int = 0,
  val y: Int = 0,
  val columns: List<ErdColumn> = emptyList(),
  val indexes: List<ErdIndex> = emptyList(),
) {
  val primaryKey: List<String> get() = columns.filter { it.primaryKey }.map { it.name }

  /** The primary key or a unique index is exactly these columns. */
  fun isUnique(names: List<String>): Boolean =
    primaryKey.toSet() == names.toSet() || indexes.any { it.unique && it.columns.toSet() == names.toSet() }
}

/** A foreign key: [table].[columns] references [refTable].[refColumns]. */
data class ErdRelation(val name: String, val table: String, val columns: List<String>, val refTable: String, val refColumns: List<String>)

/**
 * A design: what a `.erd` file holds. Names are the identity of tables and columns, so the file reads and diffs well;
 * the rename operations carry a new name into indexes and relations. [dataSource] and [schema] name what ALTER is
 * written against (names, as data source ids differ between machines).
 */
data class ErdDocument(
  val dbms: Dbms = Dbms.MYSQL,
  val dataSource: String? = null,
  val schema: String? = null,
  val tables: List<ErdTable> = emptyList(),
  val relations: List<ErdRelation> = emptyList(),
) {
  fun table(name: String): ErdTable? = tables.firstOrNull { it.name == name }

  fun updateTable(name: String, change: (ErdTable) -> ErdTable): ErdDocument = copy(tables = tables.map { if (it.name == name) change(it) else it })

  fun updateColumn(table: String, column: String, change: (ErdColumn) -> ErdColumn): ErdDocument =
    updateTable(table) { t -> t.copy(columns = t.columns.map { if (it.name == column) change(it) else it }) }

  fun isOneToOne(relation: ErdRelation): Boolean = table(relation.table)?.isUnique(relation.columns) == true

  /** A table with an `id` key at ([x], [y]); returns the new document and the table's name. */
  fun addTable(x: Int, y: Int): Pair<ErdDocument, String> {
    val name = free("table", tables.map { it.name })
    return copy(tables = tables + ErdTable(name, x = x, y = y, columns = listOf(ErdColumn("id", "BIGINT", primaryKey = true, nullable = false)))) to name
  }

  fun addColumn(table: String): ErdDocument =
    updateTable(table) { t -> t.copy(columns = t.columns + ErdColumn(free("column", t.columns.map { it.name }), "VARCHAR(255)")) }

  /** Null when [new] is blank or another table has it. */
  fun renameTable(old: String, new: String): ErdDocument? {
    val name = new.trim()
    if (name.isEmpty() || tables.any { it.name != old && it.name.equals(name, ignoreCase = true) }) return null
    return copy(
      tables = tables.map { if (it.name == old) it.copy(name = name) else it },
      relations = relations.map { it.copy(table = if (it.table == old) name else it.table, refTable = if (it.refTable == old) name else it.refTable) },
    )
  }

  /** Null when [new] is blank or another column of the table has it. */
  fun renameColumn(table: String, old: String, new: String): ErdDocument? {
    val name = new.trim()
    val columns = table(table)?.columns ?: return null
    if (name.isEmpty() || columns.any { it.name != old && it.name.equals(name, ignoreCase = true) }) return null
    fun List<String>.renamed() = map { if (it == old) name else it }
    return updateTable(table) { t ->
      t.copy(columns = t.columns.map { if (it.name == old) it.copy(name = name) else it }, indexes = t.indexes.map { it.copy(columns = it.columns.renamed()) })
    }.copy(relations = relations.map {
      it.copy(columns = if (it.table == table) it.columns.renamed() else it.columns,
              refColumns = if (it.refTable == table) it.refColumns.renamed() else it.refColumns)
    })
  }

  fun removeTable(name: String): ErdDocument = copy(tables = tables.filterNot { it.name == name }, relations = relations.filterNot { it.table == name || it.refTable == name })

  /** Also takes the column out of its indexes (an index left with none goes) and drops the relations it is part of. */
  fun removeColumn(table: String, name: String): ErdDocument = updateTable(table) { t ->
    t.copy(columns = t.columns.filterNot { it.name == name },
           indexes = t.indexes.map { it.copy(columns = it.columns - name) }.filter { it.columns.isNotEmpty() })
  }.copy(relations = relations.filterNot { (it.table == table && name in it.columns) || (it.refTable == table && name in it.refColumns) })

  fun setUnique(table: String, column: String, unique: Boolean): ErdDocument = updateTable(table) { t ->
    val others = t.indexes.filterNot { it.unique && it.columns == listOf(column) }
    t.copy(indexes = if (unique) others + ErdIndex(free("uq_${table}_$column", t.indexes.map { it.name }, always = false), true, listOf(column)) else others)
  }

  /**
   * [child] references [parent]'s primary key: a `parent_key` column is added for each key column it does not have yet.
   * Null when the parent has no primary key or the child already references it through those columns.
   */
  fun addRelation(child: String, parent: String): ErdDocument? {
    val from = table(child) ?: return null
    val to = table(parent) ?: return null
    val keys = to.columns.filter { it.primaryKey }
    if (keys.isEmpty()) return null
    val names = keys.map { "${parent}_${it.name}" }
    if (relations.any { it.table == child && it.refTable == parent && it.columns == names }) return null
    val added = keys.zip(names).filter { (_, name) -> from.columns.none { it.name == name } }
      .map { (key, name) -> ErdColumn(name, key.type, nullable = false) }
    val relation = ErdRelation(free("fk_${child}_$parent", relations.map { it.name }, always = false), child, names, parent, keys.map { it.name })
    return updateTable(child) { it.copy(columns = it.columns + added) }.copy(relations = relations + relation)
  }

  companion object {
    /** [base] when it is free and not [always] numbered, else `base_1`, `base_2`, … */
    private fun free(base: String, taken: List<String>, always: Boolean = true): String {
      val lower = taken.map { it.lowercase() }.toSet()
      if (!always && base.lowercase() !in lower) return base
      return generateSequence(1) { it + 1 }.map { "${base}_$it" }.first { it.lowercase() !in lower }
    }
  }
}

/** The `.erd` file format: JSON, with fields left out when they hold their default, so a diff shows what changed. */
object ErdJson {
  private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

  fun write(doc: ErdDocument): String {
    val root = JsonObject()
    root.addProperty("dbms", doc.dbms.name)
    doc.dataSource?.let { root.addProperty("dataSource", it) }
    doc.schema?.let { root.addProperty("schema", it) }
    root.add("tables", JsonArray().apply { doc.tables.forEach { add(table(it)) } })
    root.add("relations", JsonArray().apply {
      doc.relations.forEach { r ->
        add(JsonObject().apply {
          addProperty("name", r.name)
          addProperty("table", r.table)
          add("columns", strings(r.columns))
          addProperty("refTable", r.refTable)
          add("refColumns", strings(r.refColumns))
        })
      }
    })
    return gson.toJson(root) + "\n"
  }

  private fun table(t: ErdTable) = JsonObject().apply {
    addProperty("name", t.name)
    if (t.logicalName.isNotEmpty()) addProperty("logicalName", t.logicalName)
    if (t.comment.isNotEmpty()) addProperty("comment", t.comment)
    addProperty("x", t.x)
    addProperty("y", t.y)
    add("columns", JsonArray().apply {
      t.columns.forEach { c ->
        add(JsonObject().apply {
          addProperty("name", c.name)
          addProperty("type", c.type)
          if (c.logicalName.isNotEmpty()) addProperty("logicalName", c.logicalName)
          if (c.primaryKey) addProperty("primaryKey", true)
          if (!c.nullable) addProperty("nullable", false)
          if (c.autoIncrement) addProperty("autoIncrement", true)
          c.default?.let { addProperty("default", it) }
          if (c.comment.isNotEmpty()) addProperty("comment", c.comment)
        })
      }
    })
    if (t.indexes.isNotEmpty()) add("indexes", JsonArray().apply {
      t.indexes.forEach { i ->
        add(JsonObject().apply {
          addProperty("name", i.name)
          if (i.unique) addProperty("unique", true)
          add("columns", strings(i.columns))
        })
      }
    })
  }

  private fun strings(values: List<String>) = JsonArray().apply { values.forEach { add(it) } }

  /** An empty file is an empty design. Throws on anything that is not a design: such a file must not be overwritten. */
  fun read(text: String): ErdDocument {
    if (text.isBlank()) return ErdDocument()
    val root = JsonParser.parseString(text).asJsonObject
    val dbms = root.opt("dbms")?.let { Dbms.valueOf(it) } ?: Dbms.MYSQL
    require(dbms.hasDialect) { "Unsupported dbms: $dbms" }
    return ErdDocument(
      dbms, root.opt("dataSource"), root.opt("schema"),
      root.objects("tables").map { t ->
        ErdTable(
          t.need("name"), t.opt("logicalName").orEmpty(), t.opt("comment").orEmpty(), t.get("x")?.asInt ?: 0, t.get("y")?.asInt ?: 0,
          t.objects("columns").map { c ->
            ErdColumn(c.need("name"), c.need("type"), c.opt("logicalName").orEmpty(), c.flag("primaryKey", false), c.flag("nullable", true),
                      c.flag("autoIncrement", false), c.opt("default"), c.opt("comment").orEmpty())
          },
          t.objects("indexes").map { ErdIndex(it.need("name"), it.flag("unique", false), it.names("columns")) },
        )
      },
      root.objects("relations").map { ErdRelation(it.need("name"), it.need("table"), it.names("columns"), it.need("refTable"), it.names("refColumns")) },
    )
  }

  private fun JsonObject.opt(key: String): String? = get(key)?.takeUnless { it.isJsonNull }?.asString
  private fun JsonObject.need(key: String): String = requireNotNull(opt(key)) { "Missing \"$key\"" }
  private fun JsonObject.flag(key: String, default: Boolean): Boolean = get(key)?.takeUnless { it.isJsonNull }?.asBoolean ?: default
  private fun JsonObject.objects(key: String): List<JsonObject> = getAsJsonArray(key)?.map { it.asJsonObject }.orEmpty()
  private fun JsonObject.names(key: String): List<String> = getAsJsonArray(key)?.map { it.asString }.orEmpty()
}
