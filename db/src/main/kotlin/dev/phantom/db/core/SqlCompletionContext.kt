// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.util.PsiTreeUtil

/** Built-in functions and data types offered and colored, by dialect; with no data source, all of them. */
internal object SqlFunctions {
  private val COMMON = listOf(
    "COUNT", "SUM", "AVG", "MIN", "MAX", "COALESCE", "NULLIF", "CAST", "CONCAT", "LOWER", "UPPER", "LENGTH", "SUBSTRING", "TRIM", "LTRIM", "RTRIM", "REPLACE",
    "ROUND", "FLOOR", "CEIL", "ABS", "MOD", "POWER", "SQRT", "ROW_NUMBER", "RANK", "DENSE_RANK", "LAG", "LEAD", "FIRST_VALUE", "LAST_VALUE", "NTILE",
  )
  private val MYSQL = listOf(
    "IFNULL", "IF", "NOW", "CURDATE", "CURTIME", "DATE_FORMAT", "DATE_ADD", "DATE_SUB", "DATEDIFF", "TIMESTAMPDIFF", "STR_TO_DATE", "UNIX_TIMESTAMP", "FROM_UNIXTIME",
    "GROUP_CONCAT", "JSON_EXTRACT", "JSON_OBJECT", "JSON_ARRAY", "JSON_UNQUOTE", "LEFT", "RIGHT", "LPAD", "RPAD", "INSTR", "LOCATE", "REVERSE", "FIELD", "FIND_IN_SET",
    "LAST_INSERT_ID", "UUID", "YEAR", "MONTH", "DAY", "HOUR", "MINUTE", "SECOND", "DATE",
  )
  private val POSTGRESQL = listOf(
    "NOW", "AGE", "DATE_TRUNC", "DATE_PART", "EXTRACT", "TO_CHAR", "TO_DATE", "TO_TIMESTAMP", "TO_NUMBER", "STRING_AGG", "ARRAY_AGG", "JSON_AGG", "JSONB_AGG",
    "JSON_BUILD_OBJECT", "JSONB_BUILD_OBJECT", "UNNEST", "GENERATE_SERIES", "REGEXP_REPLACE", "SPLIT_PART", "LEFT", "RIGHT", "POSITION", "GEN_RANDOM_UUID",
  )
  private val H2 = listOf("NOW", "DATEADD", "DATEDIFF", "EXTRACT", "FORMATDATETIME", "PARSEDATETIME", "IFNULL", "LISTAGG", "RANDOM_UUID", "LEFT", "RIGHT", "LOCATE", "REGEXP_REPLACE")
  private val NO_ARGUMENTS = setOf("NOW", "CURDATE", "CURTIME", "UUID", "LAST_INSERT_ID", "GEN_RANDOM_UUID", "RANDOM_UUID", "ROW_NUMBER", "RANK", "DENSE_RANK")

  val TYPES: Set<String> = setOf(
    "BIGINT", "INT", "INTEGER", "SMALLINT", "TINYINT", "DECIMAL", "NUMERIC", "FLOAT", "DOUBLE", "REAL", "BOOLEAN", "CHAR", "VARCHAR", "TEXT", "LONGTEXT", "MEDIUMTEXT",
    "DATE", "TIME", "DATETIME", "TIMESTAMP", "BLOB", "BINARY", "VARBINARY", "JSON", "JSONB", "UUID", "SERIAL", "ENUM",
  )

  private val ALL = (COMMON + MYSQL + POSTGRESQL + H2).toSet()

  fun forDialect(dbms: Dbms?): List<String> = when (dbms) {
    Dbms.MYSQL, Dbms.MARIADB -> COMMON + MYSQL
    Dbms.POSTGRESQL -> COMMON + POSTGRESQL
    Dbms.H2 -> COMMON + H2
    else -> ALL.toList()
  }.distinct()

  fun isKnown(word: String): Boolean = word in ALL

  fun takesNoArguments(word: String): Boolean = word in NO_ARGUMENTS
}

/** Which keywords make sense at a position, from the clause it is in and whether an operand just ended. */
internal class SqlCompletionKeywords(val words: List<String>, val afterOperand: Boolean, val expression: Boolean, val types: Boolean) {
  companion object {
    private val JOINS = listOf("JOIN", "INNER JOIN", "LEFT JOIN", "RIGHT JOIN", "CROSS JOIN")
    private val AFTER_WHERE = listOf("GROUP BY", "ORDER BY", "LIMIT")
    private val STARTS = listOf("SELECT", "INSERT INTO", "UPDATE", "DELETE FROM", "WITH", "CREATE TABLE", "CREATE INDEX", "CREATE VIEW", "ALTER TABLE", "DROP TABLE",
                                "TRUNCATE TABLE", "EXPLAIN", "SHOW", "USE", "SET", "BEGIN", "COMMIT", "ROLLBACK")
    private val CONDITION_NEXT = listOf("AND", "OR", "IN", "NOT IN", "LIKE", "BETWEEN", "IS NULL", "IS NOT NULL")
    private val COLUMN_OPTIONS = listOf("PRIMARY KEY", "NOT NULL", "NULL", "DEFAULT", "UNIQUE", "AUTO_INCREMENT", "REFERENCES", "COMMENT", "CHECK")
    private val CLAUSE_WORDS = setOf("SELECT", "FROM", "JOIN", "WHERE", "ON", "HAVING", "GROUP", "ORDER", "SET", "INTO", "VALUES", "UPDATE", "USING", "LIMIT")

    fun at(position: PsiElement): SqlCompletionKeywords {
      val statement = PsiTreeUtil.getParentOfType(position, SqlStatement::class.java)
      val first = previous(position, statement)
      val afterOperand = first != null && (first.node.elementType in SqlTokens.NAMES || first.node.elementType == SqlTokens.NUMBER ||
                                           first.node.elementType == SqlTokens.STRING || first.node.elementType == SqlTokens.RPAREN)
      val firstWord = statement?.let { firstWordBefore(it, position) }
      if (first == null) return SqlCompletionKeywords(STARTS, false, false, false)
      if (first.text.uppercase() in setOf("GROUP", "ORDER") && first.node.elementType == SqlTokens.KEYWORD) return SqlCompletionKeywords(listOf("BY"), false, false, false)
      if (firstWord == "CREATE" || firstWord == "ALTER") return SqlCompletionKeywords(COLUMN_OPTIONS, afterOperand, false, afterOperand)
      val clause = clause(position, statement)
      val words = when (clause) {
        null -> if (first.node.elementType == SqlTokens.LPAREN) listOf("SELECT") else emptyList()
        "SELECT" -> if (afterOperand) listOf("FROM", "AS") else listOf("DISTINCT", "CASE", "EXISTS")
        "FROM", "JOIN", "UPDATE" -> if (afterOperand) JOINS + listOf("ON", "USING", "SET", "WHERE") + AFTER_WHERE + "AS" else emptyList()
        "WHERE", "HAVING" -> if (afterOperand) CONDITION_NEXT + AFTER_WHERE else listOf("NOT", "EXISTS", "CASE", "NULL", "TRUE", "FALSE")
        "ON", "USING" -> if (afterOperand) CONDITION_NEXT + JOINS + "WHERE" + AFTER_WHERE else listOf("NOT", "EXISTS")
        "GROUP" -> if (afterOperand) listOf("HAVING", "ORDER BY", "LIMIT") else emptyList()
        "ORDER" -> if (afterOperand) listOf("ASC", "DESC", "NULLS FIRST", "NULLS LAST", "LIMIT", "OFFSET") else emptyList()
        "SET" -> if (afterOperand) listOf("WHERE") else emptyList()
        "INTO" -> if (afterOperand) listOf("VALUES", "SELECT") else emptyList()
        "LIMIT" -> if (afterOperand) listOf("OFFSET") else emptyList()
        else -> emptyList()
      }
      return SqlCompletionKeywords(words, afterOperand, !afterOperand && clause in setOf("SELECT", "WHERE", "HAVING", "ON", "GROUP", "ORDER", "SET", "VALUES", null), false)
    }

    private fun isSkipped(e: PsiElement) = e.node.elementType == TokenType.WHITE_SPACE || e.node.elementType in SqlTokens.COMMENTS

    private fun previous(position: PsiElement, statement: PsiElement?): PsiElement? {
      var e = PsiTreeUtil.prevLeaf(position)
      while (e != null && isSkipped(e)) e = PsiTreeUtil.prevLeaf(e)
      return e?.takeIf { statement != null && it.textRange.startOffset >= statement.textRange.startOffset }
    }

    private fun firstWordBefore(statement: PsiElement, position: PsiElement): String? {
      var e = PsiTreeUtil.firstChild(statement)
      while (e !== position && isSkipped(e)) e = PsiTreeUtil.nextLeaf(e) ?: return null
      return e.takeIf { it !== position && it.node.elementType == SqlTokens.KEYWORD }?.text?.uppercase()
    }

    /** The last clause keyword before [position] in its own parentheses. */
    private fun clause(position: PsiElement, statement: PsiElement?): String? {
      var depth = 0
      var e = PsiTreeUtil.prevLeaf(position)
      while (e != null && statement != null && e.textRange.startOffset >= statement.textRange.startOffset) {
        when (e.node.elementType) {
          SqlTokens.RPAREN -> depth++
          SqlTokens.LPAREN -> if (depth == 0) return null else depth--
          SqlTokens.KEYWORD -> if (depth == 0 && e.text.uppercase() in CLAUSE_WORDS && e.parent !is SqlColumnRef) return e.text.uppercase()
        }
        e = PsiTreeUtil.prevLeaf(e)
      }
      return null
    }
  }
}

/** Opens completion on `.` and on the space after a word a table name follows; letters open it by themselves. */
class SqlTypedHandler : TypedHandlerDelegate() {
  override fun checkAutoPopup(charTyped: Char, project: Project, editor: Editor, file: PsiFile): Result {
    if (file.language != SqlLanguage || !popsUp(charTyped, editor.document.charsSequence, editor.caretModel.offset)) return Result.CONTINUE
    AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
    return Result.STOP
  }

  override fun charTyped(c: Char, project: Project, editor: Editor, file: PsiFile): Result {
    if (c == '(' && file.language == SqlLanguage) AutoPopupController.getInstance(project).autoPopupParameterInfo(editor, null)
    return Result.CONTINUE
  }

  internal companion object {
    private val BEFORE_TABLE = setOf("FROM", "JOIN", "INTO", "UPDATE", "TABLE")

    fun popsUp(typed: Char, text: CharSequence, offset: Int): Boolean {
      if (offset <= 0) return false
      val before = text[offset - 1]
      if (typed == '.') return before.isLetterOrDigit() || before == '_' || before == '`' || before == '"'
      if (typed != ' ') return false
      var start = offset
      while (start > 0 && text[start - 1].isLetter()) start--
      return text.substring(start, offset).uppercase() in BEFORE_TABLE
    }
  }
}
