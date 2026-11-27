// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core

import com.intellij.lang.PsiBuilder
import com.intellij.psi.tree.IElementType

/**
 * Statement → names. The original SQL parser, now applied by [SqlParser] to statements it has no grammar for.
 * One pass per statement that tracks the clause it is in (SELECT list, FROM list,
 * expressions, INSERT columns, WITH) and marks table references, column references, aliases and subqueries. It never
 * reports errors: a half-typed statement still gets its names marked, so completion and navigation work while typing.
 */
internal object SqlLegacyNameMarker {
  /** Marks the names of one statement, up to its `;` (not consumed) or the end of the file. */
  fun markStatement(builder: PsiBuilder) {
    Block(builder, function = false).parse(stopAtParen = false)
  }

  private enum class Clause { NONE, SELECT, TABLES, EXPR, CTE }
  private enum class TableState { EXPECT, AFTER_TABLE, AFTER_ALIAS }

  /** One query block or the inside of one pair of parentheses. [function]: arguments, where FROM does not start a FROM list. */
  private class Block(private val b: PsiBuilder, private val function: Boolean, private var clause: Clause = if (function) Clause.EXPR else Clause.NONE) {
    private var tables = TableState.EXPECT
    private var insertPending = false
    private var skipNextName = false
    private var aliasNext = false
    /** The previous token ended an operand, so a bare name now is a select-list alias (`SELECT count(*) total`). */
    private var operandEnded = false
    private var previousKeyword: String? = null
    private var first = true

    fun parse(stopAtParen: Boolean) {
      while (!b.eof()) {
        val type = b.tokenType
        when {
          type == SqlTokens.SEMICOLON -> return
          type == SqlTokens.RPAREN -> if (stopAtParen) return else advance(ended = true)
          type == SqlTokens.LPAREN -> paren()
          type == SqlTokens.KEYWORD -> keyword(b.tokenText!!.uppercase())
          type in SqlTokens.NAMES -> name()
          type == SqlTokens.COMMA -> {
            if (clause == Clause.TABLES) tables = TableState.EXPECT
            advance(ended = false)
          }
          type == SqlTokens.OPERATOR -> {
            if (b.tokenText == "::") skipNextName = true
            // `SELECT *` ends an operand only as the whole item; a following name is not an alias of it
            advance(ended = false)
          }
          else -> advance(ended = type == SqlTokens.NUMBER || type == SqlTokens.STRING || type == SqlTokens.PARAMETER)
        }
        first = false
      }
    }

    private fun advance(ended: Boolean) {
      b.advanceLexer()
      operandEnded = ended
    }

    private fun keyword(kw: String) {
      skipNextName = false // `x::text`: the type was a keyword
      val previous = previousKeyword
      previousKeyword = kw
      when (kw) {
        "WITH" -> if (clause == Clause.NONE) clause = Clause.CTE
        "SELECT" -> clause = Clause.SELECT
        "FROM" -> if (!function) tablesFollow()
        "JOIN" -> tablesFollow()
        "INTO" -> {
          tablesFollow()
          insertPending = true
        }
        "UPDATE" -> if (previous == "KEY") clause = Clause.EXPR else tablesFollow() // ON DUPLICATE KEY UPDATE a = ...
        "TABLE" -> tablesFollow()
        "DESCRIBE", "DESC", "EXPLAIN" -> if (first) tablesFollow()
        // not WHEN/THEN/ELSE: a CASE in the select list stays in it, so `CASE ... END total` is still an alias
        "ON", "USING", "WHERE", "HAVING", "SET", "VALUES", "RETURNING", "LIMIT", "OFFSET", "GROUP", "ORDER" -> clause = Clause.EXPR
        "UNION", "EXCEPT", "INTERSECT", "MINUS" -> clause = Clause.NONE
        "WINDOW", "COLLATE", "CHARSET", "OVER" -> skipNextName = true
        "AS" -> when {
          clause == Clause.TABLES && tables == TableState.AFTER_TABLE -> aliasNext = true
          clause == Clause.SELECT && !function -> aliasNext = true
          clause == Clause.CTE -> {}
          else -> skipNextName = true // CAST(x AS type)
        }
      }
      advance(ended = kw in OPERAND_KEYWORDS)
    }

    private fun tablesFollow() {
      clause = Clause.TABLES
      tables = TableState.EXPECT
      aliasNext = false
    }

    /** A dotted chain: `a`, `a.b`, `s.t.c`, `t.*`. Marks it by where it stands. */
    private fun name() {
      val probe = b.mark()
      var names = 0
      var star = false
      b.advanceLexer()
      names++
      while (b.tokenType == SqlTokens.DOT) {
        b.advanceLexer()
        val t = b.tokenType
        if (t in SqlTokens.NAMES || t == SqlTokens.KEYWORD) {
          b.advanceLexer()
          names++
        }
        else {
          if (t == SqlTokens.OPERATOR && b.tokenText == "*") star = true
          break
        }
      }
      // `INSERT INTO t (a, b)` and `CREATE TABLE t (...)` are a table and its columns, not a call
      val call = b.tokenType == SqlTokens.LPAREN &&
                 !(clause == Clause.TABLES && tables == TableState.EXPECT && (insertPending || previousKeyword in setOf("TABLE", "EXISTS")))
      probe.rollbackTo()

      when {
        skipNextName -> {
          skipNextName = false
          chain(names, star)
        }
        call -> {
          chain(names, star)
          if (clause == Clause.TABLES && tables == TableState.EXPECT) tables = TableState.AFTER_TABLE // table function
          return
        }
        clause == Clause.CTE -> single(SqlElements.CTE_NAME)
        clause == Clause.TABLES -> when (tables) {
          TableState.EXPECT -> {
            val m = b.mark()
            chain(names, star)
            m.done(SqlElements.TABLE_REF)
            tables = TableState.AFTER_TABLE
          }
          TableState.AFTER_TABLE -> {
            if (names == 1) single(SqlElements.ALIAS) else chain(names, star)
            tables = TableState.AFTER_ALIAS
            aliasNext = false
          }
          TableState.AFTER_ALIAS -> chain(names, star) // index hints and the like
        }
        clause == Clause.SELECT && !function && names == 1 && (aliasNext || operandEnded) -> {
          single(SqlElements.COLUMN_ALIAS)
          aliasNext = false
          operandEnded = false
          return
        }
        clause == Clause.NONE -> chain(names, star)
        else -> column(names, star, SqlElements.COLUMN_REF)
      }
      operandEnded = true
    }

    private fun chain(names: Int, star: Boolean) {
      b.advanceLexer()
      repeat(names - 1) { b.advanceLexer(); b.advanceLexer() }
      if (star) { b.advanceLexer(); b.advanceLexer() }
    }

    private fun single(type: IElementType) {
      val m = b.mark()
      b.advanceLexer()
      m.done(type)
    }

    /** `x` → COLUMN(x); `t.x` → COLUMN(QUALIFIER(t) . x); `t.*` → QUALIFIER(t) . *. */
    private fun column(names: Int, star: Boolean, type: IElementType) {
      val column = if (star) null else b.mark()
      val qualifierNames = if (star) names else names - 1
      if (qualifierNames > 0) {
        val q = b.mark()
        b.advanceLexer()
        repeat(qualifierNames - 1) { b.advanceLexer(); b.advanceLexer() }
        q.done(SqlElements.QUALIFIER)
        b.advanceLexer() // the dot
      }
      b.advanceLexer() // the column name, or `*`
      column?.done(type)
    }

    private fun paren() {
      skipNextName = false
      val probe = b.mark()
      b.advanceLexer()
      val subquery = b.tokenType == SqlTokens.KEYWORD && b.tokenText!!.uppercase() in setOf("SELECT", "WITH")
      val nestedTables = b.tokenType in SqlTokens.NAMES
      probe.rollbackTo()
      when {
        subquery -> {
          val m = b.mark()
          b.advanceLexer()
          Block(b, function = false).parse(stopAtParen = true)
          if (b.tokenType == SqlTokens.RPAREN) b.advanceLexer()
          m.done(SqlElements.SUBQUERY)
          if (clause == Clause.TABLES && tables == TableState.EXPECT) tables = TableState.AFTER_TABLE // derived table: an alias follows
          operandEnded = true
          return
        }
        clause == Clause.TABLES && insertPending && tables != TableState.EXPECT -> insertColumns()
        clause == Clause.CTE -> skipBalanced() // WITH x (a, b) AS (...)
        clause == Clause.TABLES && tables == TableState.EXPECT && nestedTables -> {
          b.advanceLexer()
          Block(b, function = false, clause = Clause.TABLES).parse(stopAtParen = true)
          if (b.tokenType == SqlTokens.RPAREN) b.advanceLexer()
        }
        else -> {
          b.advanceLexer()
          Block(b, function = true).parse(stopAtParen = true)
          if (b.tokenType == SqlTokens.RPAREN) b.advanceLexer()
        }
      }
      operandEnded = true
    }

    private fun insertColumns() {
      insertPending = false
      b.advanceLexer()
      while (!b.eof() && b.tokenType != SqlTokens.RPAREN && b.tokenType != SqlTokens.SEMICOLON) {
        if (b.tokenType in SqlTokens.NAMES) single(SqlElements.INSERT_COLUMN) else b.advanceLexer()
      }
      if (b.tokenType == SqlTokens.RPAREN) b.advanceLexer()
      clause = Clause.NONE
    }

    private fun skipBalanced() {
      var depth = 0
      while (!b.eof() && b.tokenType != SqlTokens.SEMICOLON) {
        if (b.tokenType == SqlTokens.LPAREN) depth++
        if (b.tokenType == SqlTokens.RPAREN) depth--
        b.advanceLexer()
        if (depth == 0) return
      }
    }

    private companion object {
      val OPERAND_KEYWORDS = setOf("END", "NULL", "TRUE", "FALSE", "CURRENT_DATE", "CURRENT_TIME", "CURRENT_TIMESTAMP", "CURRENT_USER")
    }
  }
}
