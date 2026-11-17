// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core

import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.util.PsiTreeUtil

/**
 * A built-in function called with a number of arguments it does not take: `NULLIF(a)`, `IFNULL(a, b, c)`. A curated
 * table per dialect; with no data source only the functions every dialect agrees on. Special forms (`SUBSTRING(x FROM
 * 1)`, `CAST(x AS t)`) and qualified names (`schema.f(...)`, a user function) are left alone.
 */
class SqlFunctionArgumentsInspection : SqlInspection() {
  override fun checkQuery(query: SqlScope, items: List<SqlItem>, holder: ProblemsHolder) {
    val dbms = SqlSchema.dataSourceFor(holder.file, strict = false)?.dialect()
    for (i in 0 until items.size - 1) {
      val name = items[i]
      val open = items[i + 1]
      if (open.type != SqlTokens.LPAREN || (name.type != SqlTokens.IDENTIFIER && name.type != SqlTokens.KEYWORD)) continue
      if (items.getOrNull(i - 1)?.type == SqlTokens.DOT) continue
      val arity = arity(name.text.uppercase(), dbms) ?: continue
      val close = SqlClauses.closing(items, i + 1) ?: continue
      val depth = open.depth + 1
      if ((i + 2 until close).any { items[it].depth == depth && items[it].keyword in SPECIAL_FORM }) continue
      val count = SqlClauses.arguments(items, i + 1, close).values.size
      if (count in arity) continue
      val expected = when {
        arity.last == Int.MAX_VALUE -> PhantomDbBundle.message("sql.inspection.function.arguments.at.least", arity.first)
        arity.first == arity.last -> arity.first.toString()
        else -> "${arity.first}–${arity.last}"
      }
      register(holder, name.element, items[close].element,
               PhantomDbBundle.message("sql.inspection.function.arguments", name.text, expected, count), ProblemHighlightType.GENERIC_ERROR)
    }
  }

  companion object {
    private const val MANY = Int.MAX_VALUE
    /** Keywords that make a call a special form whose arguments are not comma-separated. */
    private val SPECIAL_FORM = setOf("FROM", "FOR", "AS", "IN", "USING", "SEPARATOR", "ORDER", "DISTINCT", "WITHIN", "PLACING")

    /** Every supported dialect (and the common ones beyond) agrees. */
    private val UNIVERSAL = mapOf("NULLIF" to 2..2, "COALESCE" to 1..MANY, "LOWER" to 1..1, "UPPER" to 1..1, "ABS" to 1..1)

    /** MySQL, PostgreSQL and H2 agree. */
    private val COMMON = UNIVERSAL + mapOf(
      "MOD" to 2..2, "POWER" to 2..2, "SQRT" to 1..1, "FLOOR" to 1..1, "CEIL" to 1..1, "CEILING" to 1..1, "EXP" to 1..1, "SIGN" to 1..1,
      "ROUND" to 1..2, "LEFT" to 2..2, "RIGHT" to 2..2, "REPEAT" to 2..2, "CHAR_LENGTH" to 1..1, "CHARACTER_LENGTH" to 1..1,
      "SUBSTRING" to 2..3, "SUBSTR" to 2..3,
    )

    private val MYSQL = COMMON + mapOf(
      "IFNULL" to 2..2, "IF" to 3..3, "REPLACE" to 3..3, "LENGTH" to 1..1, "CONCAT" to 1..MANY, "CONCAT_WS" to 2..MANY,
      "GREATEST" to 2..MANY, "LEAST" to 2..MANY, "NOW" to 0..1, "CURDATE" to 0..0, "CURTIME" to 0..1, "UNIX_TIMESTAMP" to 0..1,
      "FROM_UNIXTIME" to 1..2, "DATE_FORMAT" to 2..2, "STR_TO_DATE" to 2..2, "DATEDIFF" to 2..2, "TIMESTAMPDIFF" to 3..3,
      "TIMESTAMPADD" to 3..3, "DATE_ADD" to 2..2, "DATE_SUB" to 2..2, "ADDDATE" to 2..2, "SUBDATE" to 2..2, "LAST_DAY" to 1..1,
      "FIND_IN_SET" to 2..2, "INSTR" to 2..2, "LOCATE" to 2..3, "LPAD" to 3..3, "RPAD" to 3..3, "SUBSTRING_INDEX" to 3..3,
      "FIELD" to 2..MANY, "ELT" to 2..MANY, "TRUNCATE" to 2..2, "LAST_INSERT_ID" to 0..1, "JSON_EXTRACT" to 2..MANY,
      "JSON_UNQUOTE" to 1..1, "JSON_CONTAINS" to 2..3, "JSON_LENGTH" to 1..2, "UUID" to 0..0, "POW" to 2..2, "LCASE" to 1..1,
      "UCASE" to 1..1,
    )

    private val POSTGRESQL = COMMON + mapOf(
      "REPLACE" to 3..3, "LENGTH" to 1..2, "CONCAT" to 1..MANY, "CONCAT_WS" to 1..MANY, "NOW" to 0..0, "TO_CHAR" to 2..2,
      "TO_DATE" to 2..2, "TO_NUMBER" to 2..2, "TO_TIMESTAMP" to 1..2, "DATE_TRUNC" to 2..3, "DATE_PART" to 2..2, "LPAD" to 2..3,
      "RPAD" to 2..3, "STRPOS" to 2..2, "SPLIT_PART" to 3..3, "REGEXP_REPLACE" to 3..6, "AGE" to 1..2, "GEN_RANDOM_UUID" to 0..0,
    )

    fun arity(function: String, dbms: Dbms?): IntRange? = when (dbms) {
      null -> UNIVERSAL[function]
      Dbms.MYSQL, Dbms.MARIADB -> MYSQL[function]
      Dbms.POSTGRESQL -> POSTGRESQL[function]
      Dbms.H2 -> COMMON[function]
      else -> UNIVERSAL[function]
    }
  }
}

/** MySQL's `IF(c, a, b)` runs only on MySQL and MariaDB; `CASE WHEN c THEN a ELSE b END` means the same everywhere. Off by default. */
class SqlIfFunctionInspection : SqlInspection() {
  override fun checkQuery(query: SqlScope, items: List<SqlItem>, holder: ProblemsHolder) {
    val dbms = SqlSchema.dataSourceFor(holder.file, strict = false)?.dialect() ?: return
    if (dbms != Dbms.MYSQL && dbms != Dbms.MARIADB) return
    for (i in 0 until items.size - 1) {
      if (!items[i].isKeyword("IF") || items[i + 1].type != SqlTokens.LPAREN || items.getOrNull(i - 1)?.type == SqlTokens.DOT) continue
      val close = SqlClauses.closing(items, i + 1) ?: continue
      val args = SqlClauses.arguments(items, i + 1, close).values
      if (args.size != 3 || args.any { it.isEmpty() }) continue
      val text = holder.file.text
      val (c, a, b) = args.map { arg -> text.substring(arg.first().element.textRange.startOffset, arg.last().element.textRange.endOffset) }
      val replacement = "CASE WHEN $c THEN $a ELSE $b END"
      register(holder, items[i].element, items[close].element, PhantomDbBundle.message("sql.inspection.if.function"),
               ProblemHighlightType.WEAK_WARNING, SqlTextFix(PhantomDbBundle.message("sql.fix.replace.with", "CASE"), replacement))
    }
  }
}

/**
 * A table or column defined with an unquoted name that the dialect reserves (`CREATE TABLE t (rank INT)` on MySQL 8,
 * `user` on PostgreSQL): the statement fails, and every later query must quote the name. MySQL and PostgreSQL data
 * sources only, and only where a name is defined (CREATE TABLE, ALTER TABLE ... ADD).
 */
class SqlReservedIdentifierInspection : SqlInspection() {
  override fun checkDdl(statement: SqlStatement, holder: ProblemsHolder) {
    val dbms = SqlSchema.dataSourceFor(holder.file, strict = false)?.dialect() ?: return
    val reserved = when (dbms) {
      Dbms.MYSQL -> MYSQL_RESERVED
      Dbms.POSTGRESQL -> POSTGRESQL_RESERVED
      else -> return
    }
    val items = SqlQueries.items(statement)
    if (items.getOrNull(1)?.isKeyword("TABLE") != true && items.getOrNull(2)?.isKeyword("TABLE") != true) return
    val tableAt = items.indexOfFirst { it.isKeyword("TABLE") } + 1
    var nameAt = tableAt
    if (items.getOrNull(nameAt)?.isKeyword("IF") == true) nameAt += if (items[0].isKeyword("CREATE")) 3 else 2 // IF NOT EXISTS / IF EXISTS
    val fixQuote = if (dbms == Dbms.MYSQL) '`' else '"'
    fun check(item: SqlItem?) {
      if (item == null) return
      // a table reference or a column reference wraps the name leaf
      var leaf = item.element
      if (leaf is SqlTableRef) leaf = leaf.lastChild ?: return
      while (leaf.firstChild != null && leaf.firstChild == leaf.lastChild) leaf = leaf.firstChild
      val type = leaf.node.elementType
      if (type != SqlTokens.IDENTIFIER && type != SqlTokens.KEYWORD) return
      if (leaf.firstChild != null || leaf.text.uppercase() !in reserved) return
      // after a dot (`schema.table`) a reserved word is a name already
      if (PsiTreeUtil.prevLeaf(leaf)?.let { SqlRoutine.significantBackward(it) }?.node?.elementType == SqlTokens.DOT) return
      holder.registerProblem(leaf, PhantomDbBundle.message("sql.inspection.reserved.identifier", leaf.text, dbms.displayName),
                             ProblemHighlightType.WEAK_WARNING,
                             SqlTextFix(PhantomDbBundle.message("sql.fix.quote.identifier"), "$fixQuote${leaf.text}$fixQuote"))
    }
    if (items[0].isKeyword("CREATE")) {
      check(items.getOrNull(nameAt))
      val open = nameAt + 1
      if (items.getOrNull(open)?.type != SqlTokens.LPAREN || items[open].depth != 0) return
      val close = SqlClauses.closing(items, open) ?: return
      for (definition in SqlClauses.arguments(items, open, close).values) {
        val first = definition.firstOrNull() ?: continue
        if (first.text.uppercase() in DEFINITION_STARTS) continue
        check(first)
      }
    }
    else if (items[0].isKeyword("ALTER")) {
      // ALTER TABLE t ADD [COLUMN] [IF NOT EXISTS] name type, ...
      for ((i, item) in items.withIndex()) {
        if (item.depth != 0 || !item.isKeyword("ADD") || i <= nameAt) continue
        var at = i + 1
        if (items.getOrNull(at)?.isKeyword("COLUMN") == true) at++
        if (items.getOrNull(at)?.isKeyword("IF") == true) at += 3
        val name = items.getOrNull(at) ?: continue
        if (name.type == SqlTokens.LPAREN || name.text.uppercase() in DEFINITION_STARTS) continue
        check(name)
      }
    }
  }

  private companion object {
    /** Words that start something other than a column in a table definition. */
    val DEFINITION_STARTS = setOf("PRIMARY", "KEY", "INDEX", "UNIQUE", "CONSTRAINT", "FOREIGN", "CHECK", "FULLTEXT", "SPATIAL", "LIKE",
                                  "EXCLUDE", "PERIOD", "PARTITION")

    /** MySQL 8.0 reserved words that are plausible names (the manual's keyword list, marked (R)); type names left out. */
    val MYSQL_RESERVED: Set<String> = """
      ACCESSIBLE ADD ALL ALTER ANALYZE AND AS ASC ASENSITIVE BEFORE BETWEEN BOTH BY CALL CASCADE CASE CHANGE CHECK COLLATE COLUMN
      CONDITION CONSTRAINT CONTINUE CONVERT CREATE CROSS CUBE CUME_DIST CURRENT_DATE CURRENT_TIME CURRENT_TIMESTAMP CURRENT_USER
      CURSOR DATABASE DATABASES DECLARE DEFAULT DELAYED DELETE DENSE_RANK DESC DESCRIBE DETERMINISTIC DISTINCT DISTINCTROW DIV DROP
      DUAL EACH ELSE ELSEIF EMPTY ENCLOSED ESCAPED EXCEPT EXISTS EXIT EXPLAIN FALSE FETCH FIRST_VALUE FOR FORCE FOREIGN FROM
      FULLTEXT FUNCTION GENERATED GET GRANT GROUP GROUPING GROUPS HAVING HIGH_PRIORITY IF IGNORE IN INDEX INFILE INNER INOUT
      INSENSITIVE INSERT INTERSECT INTERVAL INTO IS ITERATE JOIN JSON_TABLE KEY KEYS KILL LAG LAST_VALUE LATERAL LEAD LEADING LEAVE
      LEFT LIKE LIMIT LINEAR LINES LOAD LOCALTIME LOCALTIMESTAMP LOCK LOOP LOW_PRIORITY MATCH MAXVALUE MOD MODIFIES NATURAL NOT
      NTH_VALUE NTILE NULL OF ON OPTIMIZE OPTION OPTIONALLY OR ORDER OUT OUTER OUTFILE OVER PARTITION PERCENT_RANK PRIMARY PROCEDURE
      PURGE RANGE RANK READ READS RECURSIVE REFERENCES REGEXP RELEASE RENAME REPEAT REPLACE REQUIRE RESIGNAL RESTRICT RETURN REVOKE
      RIGHT RLIKE ROW ROWS ROW_NUMBER SCHEMA SCHEMAS SELECT SENSITIVE SEPARATOR SET SHOW SIGNAL SPATIAL SPECIFIC SQL SQLEXCEPTION
      SQLSTATE SQLWARNING STARTING STORED STRAIGHT_JOIN SYSTEM TABLE TERMINATED THEN TO TRAILING TRIGGER TRUE UNDO UNION UNIQUE
      UNLOCK UNSIGNED UPDATE USAGE USE USING UTC_DATE UTC_TIME UTC_TIMESTAMP VALUES VIRTUAL WHEN WHERE WHILE WINDOW WITH WRITE XOR
    """.trim().split(Regex("\\s+")).toSet()

    /** PostgreSQL reserved key words (the documentation's appendix, "reserved" column). */
    val POSTGRESQL_RESERVED: Set<String> = """
      ALL ANALYSE ANALYZE AND ANY ARRAY AS ASC ASYMMETRIC BOTH CASE CAST CHECK COLLATE COLUMN CONSTRAINT CREATE CURRENT_CATALOG
      CURRENT_DATE CURRENT_ROLE CURRENT_TIME CURRENT_TIMESTAMP CURRENT_USER DEFAULT DEFERRABLE DESC DISTINCT DO ELSE END EXCEPT FALSE
      FETCH FOR FOREIGN FROM GRANT GROUP HAVING IN INITIALLY INTERSECT INTO LATERAL LEADING LIMIT LOCALTIME LOCALTIMESTAMP NOT NULL
      OFFSET ON ONLY OR ORDER PLACING PRIMARY REFERENCES RETURNING SELECT SESSION_USER SOME SYMMETRIC SYSTEM_USER TABLE THEN TO
      TRAILING TRUE UNION UNIQUE USER USING VARIADIC WHEN WHERE WINDOW WITH
    """.trim().split(Regex("\\s+")).toSet()
  }
}

/**
 * `ALTER TABLE t DROP COLUMN c` when c is part of an index or the primary key: the index changes or disappears with it
 * (MySQL narrows a composite index, PostgreSQL drops it). Needs the data source's schema; silent otherwise.
 */
class SqlDropIndexedColumnInspection : SqlSchemaInspection() {
  override fun checkDdl(statement: SqlStatement, holder: ProblemsHolder) {
    val items = SqlQueries.items(statement)
    if (items.firstOrNull()?.isKeyword("ALTER") != true || items.getOrNull(1)?.isKeyword("TABLE") != true) return
    val ref = items.firstOrNull { it.element is SqlTableRef }?.element as? SqlTableRef ?: return
    val target = tableOf(ref) ?: return
    val (primaryKey, indexes) = SqlSchema.getInstance(statement.project).indexes(target.dataSource, target.table) ?: return
    // indexes this statement drops itself
    val droppedIndexes = HashSet<String>()
    for ((i, item) in items.withIndex()) {
      if (item.depth == 0 && item.isKeyword("DROP") && items.getOrNull(i + 1)?.text?.uppercase() in setOf("INDEX", "KEY")) {
        items.getOrNull(i + 2)?.let { droppedIndexes.add(SqlNames.unquote(it.text).lowercase()) }
      }
    }
    val dropsPrimaryKey = items.withIndex().any { (i, it) -> it.depth == 0 && it.isKeyword("DROP") && items.getOrNull(i + 1)?.isKeyword("PRIMARY") == true }
    for ((i, item) in items.withIndex()) {
      if (item.depth != 0 || !item.isKeyword("DROP")) continue
      var at = i + 1
      if (items.getOrNull(at)?.isKeyword("COLUMN") == true) at++
      if (items.getOrNull(at)?.isKeyword("IF") == true) at += 2 // IF EXISTS
      val name = items.getOrNull(at) ?: continue
      if (name.text.uppercase() in NOT_COLUMNS || name.type == SqlTokens.LPAREN) continue
      val column = SqlNames.unquote(name.text)
      val inKey = !dropsPrimaryKey && primaryKey.columns.any { SqlNames.same(it, column) }
      val index = indexes.firstOrNull { idx -> idx.name.lowercase() !in droppedIndexes && idx.columns.any { SqlNames.same(it, column) } }
      val message = when {
        inKey -> PhantomDbBundle.message("sql.inspection.drop.indexed.column.pk", column)
        index != null -> PhantomDbBundle.message("sql.inspection.drop.indexed.column", column, index.name)
        else -> continue
      }
      register(holder, item.element, name.element, message, ProblemHighlightType.WEAK_WARNING)
    }
  }

  private companion object {
    val NOT_COLUMNS = setOf("INDEX", "KEY", "PRIMARY", "FOREIGN", "CONSTRAINT", "CHECK", "PARTITION", "DEFAULT", "UNIQUE")
  }
}
