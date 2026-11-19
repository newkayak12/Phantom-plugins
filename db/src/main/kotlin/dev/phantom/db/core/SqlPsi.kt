// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiReference
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.IncorrectOperationException

/**
 * Names in a statement. The parser marks where a name is a table, a column, an alias or a CTE; the rest of the
 * statement stays flat tokens. A name is one identifier, a quoted identifier, or a keyword after a dot (`t.type`).
 */
object SqlNames {
  fun isName(node: ASTNode?): Boolean = node != null && node.elementType in SqlTokens.NAMES

  /** `` `a b` `` → `a b`; `"x""y"` → `x"y`. */
  fun unquote(text: String): String = when {
    // SQL injected into a Java or Kotlin string keeps the string's escapes
    text.length >= 4 && text.startsWith("\\\"") && text.endsWith("\\\"") -> text.substring(2, text.length - 2)
    text.length >= 2 && text[0] == '`' && text.last() == '`' -> text.substring(1, text.length - 1).replace("``", "`")
    text.length >= 2 && text[0] == '"' && text.last() == '"' -> text.substring(1, text.length - 1).replace("\"\"", "\"")
    text.length >= 2 && text[0] == '[' && text.last() == ']' -> text.substring(1, text.length - 1)
    else -> text
  }

  /** The name tokens of a dotted chain inside [element], in order: `s.t` → [s, t]. */
  fun parts(element: PsiElement): List<ASTNode> =
    element.node.getChildren(null).filter { it.elementType in SqlTokens.NAMES || it.elementType == SqlTokens.KEYWORD }

  fun same(a: String, b: String): Boolean = a.equals(b, ignoreCase = true)
}

/** A query block: the statement itself or a parenthesized subquery. Names resolve in the nearest one first. */
interface SqlScope : PsiElement

class SqlSubquery(node: ASTNode) : ASTWrapperPsiElement(node), SqlScope

/** `[schema.]table` where a table is read or written: after FROM, JOIN, UPDATE, INTO, TABLE and in FROM lists. */
class SqlTableRef(node: ASTNode) : ASTWrapperPsiElement(node) {
  private val parts: List<ASTNode> get() = SqlNames.parts(this)
  val tableName: String get() = parts.lastOrNull()?.let { SqlNames.unquote(it.text) }.orEmpty()
  val schemaName: String? get() = parts.dropLast(1).lastOrNull()?.let { SqlNames.unquote(it.text) }
  val nameRange: TextRange get() = parts.lastOrNull()?.let { TextRange.from(it.startOffset - textRange.startOffset, it.textLength) } ?: TextRange.EMPTY_RANGE

  /** The alias right after this reference: `FROM reservation r`, `JOIN guest AS g`. */
  val alias: SqlAlias?
    get() {
      var next = PsiTreeUtil.skipWhitespacesAndCommentsForward(this)
      if (next?.node?.elementType == SqlTokens.KEYWORD && next.text.equals("AS", ignoreCase = true)) next = PsiTreeUtil.skipWhitespacesAndCommentsForward(next)
      return next as? SqlAlias
    }

  override fun getReference(): PsiReference = SqlTableReference(this)
}

/** `x` in `t.x`, or a bare `x`. [qualifier] is `t`. In an INSERT column list the table is the INSERT target. */
class SqlColumnRef(node: ASTNode) : ASTWrapperPsiElement(node) {
  val qualifier: SqlQualifier? get() = findChildByClass(SqlQualifier::class.java)
  private val nameNode: ASTNode? get() = node.getChildren(null).lastOrNull { it.elementType in SqlTokens.NAMES || it.elementType == SqlTokens.KEYWORD }
  val columnName: String get() = nameNode?.let { SqlNames.unquote(it.text) }.orEmpty()
  val nameRange: TextRange get() = nameNode?.let { TextRange.from(it.startOffset - textRange.startOffset, it.textLength) } ?: TextRange.EMPTY_RANGE
  val isInsertColumn: Boolean get() = node.elementType == SqlElements.INSERT_COLUMN

  override fun getReference(): PsiReference? = if (columnName.isEmpty()) null else SqlColumnReference(this)
}

/** `t` (or `s.t`) before the column in `t.x`: an alias, a table, or a CTE. */
class SqlQualifier(node: ASTNode) : ASTWrapperPsiElement(node) {
  private val parts: List<ASTNode> get() = SqlNames.parts(this)
  val qualifierName: String get() = parts.lastOrNull()?.let { SqlNames.unquote(it.text) }.orEmpty()
  val schemaName: String? get() = parts.dropLast(1).lastOrNull()?.let { SqlNames.unquote(it.text) }
  val nameRange: TextRange get() = parts.lastOrNull()?.let { TextRange.from(it.startOffset - textRange.startOffset, it.textLength) } ?: TextRange.EMPTY_RANGE

  override fun getReference(): PsiReference = SqlQualifierReference(this)
}

/** A declared name: a table alias, a derived table alias, a CTE name, or a select-list column alias. */
abstract class SqlDeclaration(node: ASTNode) : ASTWrapperPsiElement(node), PsiNameIdentifierOwner {
  override fun getName(): String = SqlNames.unquote(text)
  override fun getNameIdentifier(): PsiElement = firstChild ?: this
  override fun getTextOffset(): Int = textRange.startOffset
  override fun setName(name: String): PsiElement = throw IncorrectOperationException("Rename is not supported in SQL")
}

/** `r` in `FROM reservation r`, or `d` in `FROM (SELECT ...) d`. */
class SqlAlias(node: ASTNode) : SqlDeclaration(node) {
  /** The table it names, or null for a derived table. */
  val table: SqlTableRef?
    get() {
      var prev = PsiTreeUtil.skipWhitespacesAndCommentsBackward(this)
      if (prev?.node?.elementType == SqlTokens.KEYWORD && prev.text.equals("AS", ignoreCase = true)) prev = PsiTreeUtil.skipWhitespacesAndCommentsBackward(prev)
      return prev as? SqlTableRef
    }

  /** The subquery of `(SELECT ...) d`. */
  val subquery: SqlSubquery?
    get() {
      var prev = PsiTreeUtil.skipWhitespacesAndCommentsBackward(this)
      if (prev?.node?.elementType == SqlTokens.KEYWORD && prev.text.equals("AS", ignoreCase = true)) prev = PsiTreeUtil.skipWhitespacesAndCommentsBackward(prev)
      return prev as? SqlSubquery
    }
}

/** `recent` in `WITH recent AS (SELECT ...)`. */
class SqlCteName(node: ASTNode) : SqlDeclaration(node)

/** `total` in `SELECT count(*) AS total` or `SELECT count(*) total`. */
class SqlColumnAlias(node: ASTNode) : SqlDeclaration(node)

object SqlScopes {
  /** Scopes from the innermost outwards. */
  fun chain(element: PsiElement): List<SqlScope> {
    val result = ArrayList<SqlScope>()
    var e: PsiElement? = element.parent
    while (e != null && e !is SqlFile) {
      if (e is SqlScope) result.add(e)
      e = e.parent
    }
    return result
  }

  /** Declarations and table references directly in [scope], not in its nested subqueries (CTEs: in the whole statement). */
  fun <T : PsiElement> direct(scope: SqlScope, type: Class<T>): List<T> {
    val result = ArrayList<T>()
    fun walk(e: PsiElement) {
      var child = e.firstChild
      while (child != null) {
        if (type.isInstance(child)) result.add(type.cast(child))
        if (child !is SqlScope && child.firstChild != null) walk(child)
        child = child.nextSibling
      }
    }
    walk(scope)
    return result
  }

  /** CTE names are visible in the whole statement, including inside each other (recursive CTEs). */
  fun ctes(element: PsiElement): List<SqlCteName> {
    val statement = PsiTreeUtil.getParentOfType(element, SqlStatement::class.java, false) ?: return emptyList()
    return PsiTreeUtil.findChildrenOfType(statement, SqlCteName::class.java).toList()
  }
}

internal fun elementFor(node: ASTNode): PsiElement? = when (node.elementType) {
  SqlElements.SUBQUERY -> SqlSubquery(node)
  SqlElements.TABLE_REF -> SqlTableRef(node)
  SqlElements.COLUMN_REF, SqlElements.INSERT_COLUMN -> SqlColumnRef(node)
  SqlElements.QUALIFIER -> SqlQualifier(node)
  SqlElements.ALIAS -> SqlAlias(node)
  SqlElements.CTE_NAME -> SqlCteName(node)
  SqlElements.COLUMN_ALIAS -> SqlColumnAlias(node)
  else -> null
}
