// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core

import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import dev.phantom.db.core.exec.DbForeignKey
import dev.phantom.db.core.exec.DbNames
import dev.phantom.db.core.exec.DbTableRef

/**
 * Join completion (as in DataGrip). After `JOIN t [alias] ON`: whole conditions joining t to each table before it in
 * FROM, from foreign keys in either direction first, then guesses by column name (`x_id` = `x.id`, the same `*_id`
 * column on both sides). After `JOIN`: the tables a foreign key links to the ones in FROM, with the ON clause.
 *
 * Only cached metadata is read; what is missing is loaded in the background and completion is restarted on the next
 * keystroke, so it never waits for the database.
 */
internal class SqlJoinCompletion(private val dataSource: DbDataSource, private val schema: SqlSchema) {
  /** Something was not cached yet: a load was scheduled. */
  var pending = false
    private set

  /** A table in the query: its reference, the database table and the name to qualify columns with (alias or table name). */
  class Side(val ref: SqlTableRef, val table: DbTableRef, val name: String)

  class Condition(val text: String, val foreignKey: Boolean)

  /** `ON <caret>` (or `ON ... AND <caret>`) after `JOIN t [a]`: conditions joining t to the tables before it. */
  fun addOnConditions(column: SqlColumnRef, result: CompletionResultSet) {
    if (column.qualifier != null || column.isInsertColumn) return
    val on = onBefore(column) ?: return
    val joined = when (val before = PsiTreeUtil.skipWhitespacesAndCommentsBackward(on)) {
      is SqlAlias -> before.table
      is SqlTableRef -> before
      else -> null
    } ?: return
    if (isCrossOrNatural(joined)) return
    val new = side(joined) ?: return
    val keyword = keywordCase(on.text)
    val seen = HashSet<String>()
    for (existing in before(joined)) {
      for (c in conditions(existing, new, keyword)) {
        if (!seen.add(c.text.lowercase())) continue
        result.addElement(PrioritizedLookupElement.withPriority(
          LookupElementBuilder.create(c.text).withIcon(AllIcons.Nodes.DataColumn).bold()
            .withTypeText(PhantomDbBundle.message(if (c.foreignKey) "sql.completion.join.fk" else "sql.completion.join.guess")),
          if (c.foreignKey) 100.0 else 90.0))
      }
    }
  }

  /** `JOIN <caret>`: tables with a foreign key to or from a table before it, each with its ON clause. */
  fun addJoinTables(ref: SqlTableRef, joinKeyword: String, snapshot: SqlSchemaSnapshot, result: CompletionResultSet) {
    if (ref.schemaName != null) return
    val existing = before(ref)
    if (existing.isEmpty()) return
    val taken = HashSet<String>()
    for (scope in SqlScopes.chain(ref).take(1)) {
      SqlScopes.direct(scope, SqlTableRef::class.java).forEach { taken += it.tableName.lowercase(); it.alias?.let { a -> taken += a.name.lowercase() } }
    }
    val dbms = dataSource.dialect()
    val keyword = keywordCase(joinKeyword)
    val seen = HashSet<String>()
    // one lookup per name instead of a scan of every table for each key
    val byName = snapshot.tables.groupBy { it.name.lowercase() }
    fun find(name: String, schema: String?) = byName[name.lowercase()].orEmpty().filter { schema == null || SqlNames.same(it.schema ?: it.catalog.orEmpty(), schema) }
    val aliases = HashMap<DbTableRef, String>()
    for (e in existing) {
      val (imported, referencing) = keys(e.table) ?: continue
      val links = imported.map { it.refTable to it } + referencing.map { it.table to it }
      for ((other, fk) in links) {
        val table = find(other.name, other.schema ?: other.catalog).firstOrNull() ?: find(other.name, null).firstOrNull() ?: continue
        val alias = aliases.getOrPut(table) { alias(table.name, taken) }
        val condition = linkCondition(fk, e, Side(ref, table, alias), keyword) ?: continue
        val unqualified = find(table.name, null).firstOrNull()
        val tableText = if (unqualified == null || same(unqualified, table)) quote(table.name)
                        else listOfNotNull(table.schema ?: table.catalog, table.name).joinToString(".") { DbNames.identifier(dbms, it) }
        val text = "$tableText $alias ${keyword("ON")} $condition"
        if (!seen.add(text.lowercase())) continue
        result.addElement(PrioritizedLookupElement.withPriority(
          LookupElementBuilder.create(text).withIcon(AllIcons.Nodes.DataTables).withTypeText(table.schema ?: table.catalog), 9.0))
      }
    }
  }

  /** Conditions joining [new] to [existing]: foreign keys either way, then name guesses. */
  fun conditions(existing: Side, new: Side, keyword: (String) -> String = { it }): List<Condition> {
    val result = ArrayList<Condition>()
    keys(existing.table)?.first?.filter { same(it.refTable, new.table) }?.forEach { fk -> fkCondition(fk, existing, new, keyword)?.let { result += Condition(it, true) } }
    keys(new.table)?.first?.filter { same(it.refTable, existing.table) }
      ?.forEach { fk -> fkCondition(fk, new, existing, keyword, swap = true)?.let { result += Condition(it, true) } }
    val left = schema.columnsNoWait(dataSource, existing.table).also { if (it == null) pending = true } ?: return result
    val right = schema.columnsNoWait(dataSource, new.table).also { if (it == null) pending = true } ?: return result
    fun has(columns: List<dev.phantom.db.core.exec.DbColumnInfo>, name: String) = columns.firstOrNull { SqlNames.same(it.name, name) }?.name
    val guesses = ArrayList<Pair<String, String>>()
    has(right, "id")?.let { id -> bases(new.table.name).firstNotNullOfOrNull { has(left, it + "_id") }?.let { guesses += it to id } }
    has(left, "id")?.let { id -> bases(existing.table.name).firstNotNullOfOrNull { has(right, it + "_id") }?.let { guesses += id to it } }
    for (c in left) {
      if (!c.name.endsWith("_id", ignoreCase = true)) continue
      has(right, c.name)?.let { guesses += c.name to it }
    }
    val known = result.map { it.text.lowercase() }.toSet()
    for ((l, r) in guesses.distinct()) {
      val text = "${existing.name}.${quote(l)} = ${new.name}.${quote(r)}"
      if (text.lowercase() !in known) result += Condition(text, false)
    }
    return result
  }

  /** `a.x = b.y [AND ...]` for a key of [from] referencing [to]; [swap] writes [to] first (the table already in FROM). */
  private fun fkCondition(fk: DbForeignKey, from: Side, to: Side, keyword: (String) -> String = { it }, swap: Boolean = false): String? {
    if (fk.columns.isEmpty() || fk.columns.size != fk.refColumns.size) return null
    return fk.columns.zip(fk.refColumns).joinToString(" ${keyword("AND")} ") { (c, r) ->
      if (swap) "${to.name}.${quote(r)} = ${from.name}.${quote(c)}" else "${from.name}.${quote(c)} = ${to.name}.${quote(r)}"
    }
  }

  /** [fkCondition] for a key of either [existing] or the candidate table, with the table already in FROM written first. */
  private fun linkCondition(fk: DbForeignKey, existing: Side, candidate: Side, keyword: (String) -> String): String? =
    if (same(fk.table, existing.table) && same(fk.refTable, candidate.table)) fkCondition(fk, existing, candidate, keyword)
    else fkCondition(fk, candidate, existing, keyword, swap = true)

  private fun keys(table: DbTableRef): Pair<List<DbForeignKey>, List<DbForeignKey>>? =
    schema.foreignKeys(dataSource, table).also { if (it == null) pending = true }

  /** The tables of the query block of [ref] that come before it, which the database knows. */
  private fun before(ref: SqlTableRef): List<Side> {
    val scope = SqlScopes.chain(ref).firstOrNull() ?: return emptyList()
    return SqlScopes.direct(scope, SqlTableRef::class.java)
      .filter { it.textRange.startOffset < ref.textRange.startOffset }
      .mapNotNull(::side)
  }

  private fun side(ref: SqlTableRef): Side? {
    val target = (SqlResolver.table(ref, load = false) as? SqlResolution.Found)?.target as? SqlDbTarget ?: return null
    val name = ref.alias?.text ?: ref.node.getChildren(null).lastOrNull { SqlNames.isName(it) || it.elementType == SqlTokens.KEYWORD }?.text ?: return null
    return Side(ref, target.table, name)
  }

  /** The ON this condition belongs to: right before it, or before `... AND` within the same ON clause. */
  private fun onBefore(column: SqlColumnRef): PsiElement? {
    var e = PsiTreeUtil.skipWhitespacesAndCommentsBackward(column) ?: return null
    if (isKeyword(e, "ON")) return e
    if (!isKeyword(e, "AND")) return null
    while (true) {
      e = PsiTreeUtil.skipWhitespacesAndCommentsBackward(e) ?: return null
      if (isKeyword(e, "ON")) return e
      if (e.node.elementType == SqlTokens.KEYWORD && e.text.uppercase() in CLAUSES || e.node.elementType == SqlTokens.COMMA) return null
    }
  }

  /** `CROSS JOIN t` and `NATURAL [LEFT] JOIN t` take no ON. */
  private fun isCrossOrNatural(joined: SqlTableRef): Boolean {
    var e = PsiTreeUtil.skipWhitespacesAndCommentsBackward(joined)
    if (e == null || !isKeyword(e, "JOIN")) return false
    while (true) {
      e = PsiTreeUtil.skipWhitespacesAndCommentsBackward(e!!) ?: return false
      if (isKeyword(e, "CROSS") || isKeyword(e, "NATURAL")) return true
      if (!(isKeyword(e, "LEFT") || isKeyword(e, "RIGHT") || isKeyword(e, "FULL") || isKeyword(e, "INNER") || isKeyword(e, "OUTER"))) return false
    }
  }

  private fun isKeyword(e: PsiElement, word: String) = e.node.elementType == SqlTokens.KEYWORD && e.text.equals(word, ignoreCase = true)

  private fun quote(name: String): String = DbNames.identifier(dataSource.dialect(), name)

  companion object {
    private val CLAUSES = setOf("SELECT", "FROM", "JOIN", "WHERE", "GROUP", "HAVING", "ORDER", "LIMIT", "USING", "SET", "UNION", "EXCEPT", "INTERSECT", "WINDOW")

    /** Tables are the same when name, schema and catalog agree; a side that does not say its schema or catalog matches any. */
    fun same(a: DbTableRef, b: DbTableRef): Boolean =
      SqlNames.same(a.name, b.name) && sameOrUnknown(a.schema, b.schema) && sameOrUnknown(a.catalog, b.catalog)

    private fun sameOrUnknown(a: String?, b: String?) = a == null || b == null || SqlNames.same(a, b)

    /** `customer` → c, `order_item` → oi; a digit is added while the name is taken. */
    fun alias(table: String, taken: MutableSet<String>): String {
      val base = table.split('_', ' ', '-').filter { it.isNotEmpty() }.joinToString("") { it.take(1) }.lowercase().ifEmpty { "t" }
      val alias = if (base !in taken && !SqlTokens.isKeyword(base)) base else generateSequence(1) { it + 1 }.map { "$base$it" }.first { it !in taken }
      taken += alias
      return alias
    }

    /** `orders` → [orders, order]; `categories` → [categories, category]. */
    fun bases(table: String): List<String> {
      val name = table.lowercase()
      return listOfNotNull(name, when {
        name.endsWith("ies") -> name.dropLast(3) + "y"
        name.endsWith("ses") -> name.dropLast(2)
        name.endsWith("s") -> name.dropLast(1)
        else -> null
      })
    }

    /** Keywords written in the case of the one the user typed. */
    fun keywordCase(typed: String): (String) -> String = if (typed.any { it.isLetter() } && typed == typed.lowercase()) { k -> k.lowercase() } else { k -> k }

    fun isAfterJoin(position: PsiElement): Boolean {
      var leaf = PsiTreeUtil.prevLeaf(position)
      while (leaf != null && (leaf.node.elementType == com.intellij.psi.TokenType.WHITE_SPACE || leaf.node.elementType in SqlTokens.COMMENTS)) leaf = PsiTreeUtil.prevLeaf(leaf)
      return leaf?.node?.elementType == SqlTokens.KEYWORD && leaf.text.equals("JOIN", ignoreCase = true)
    }
  }
}
