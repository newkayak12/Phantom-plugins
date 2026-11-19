// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.TokenType
import com.intellij.psi.util.PsiTreeUtil
import javax.swing.Icon

/** Colors what a name is, not only what token it is: tables, schemas, columns, aliases, CTEs, functions, data types. */
class SqlSemanticAnnotator : Annotator, DumbAware {
  override fun annotate(element: PsiElement, holder: AnnotationHolder) {
    val base = element.textRange.startOffset
    fun paint(range: TextRange, key: TextAttributesKey) {
      if (!range.isEmpty) holder.newSilentAnnotation(HighlightSeverity.INFORMATION).range(range).textAttributes(key).create()
    }
    when (element) {
      is SqlTableRef -> {
        paint(element.nameRange.shiftRight(base), SqlColors.TABLE)
        SqlNames.parts(element).dropLast(1).forEach { paint(it.textRange, SqlColors.SCHEMA) }
      }
      is SqlQualifier -> paint(element.nameRange.shiftRight(base), SqlColors.ALIAS)
      is SqlColumnRef -> paint(element.nameRange.shiftRight(base), SqlColors.COLUMN)
      is SqlAlias, is SqlColumnAlias -> paint(element.textRange, SqlColors.ALIAS)
      is SqlCteName -> paint(element.textRange, SqlColors.CTE)
      else -> if (element.firstChild == null) leaf(element, ::paint)
    }
  }

  private fun leaf(leaf: PsiElement, paint: (TextRange, TextAttributesKey) -> Unit) {
    val type = leaf.node.elementType
    if (type != SqlTokens.IDENTIFIER && type != SqlTokens.KEYWORD) return
    if (leaf.parent is SqlTableRef || leaf.parent is SqlColumnRef || leaf.parent is SqlQualifier) return
    val word = leaf.text.uppercase()
    val next = significant(leaf, forward = true)
    if (next?.node?.elementType == SqlTokens.LPAREN && (type == SqlTokens.IDENTIFIER || SqlFunctions.isKnown(word))) {
      if (!(type == SqlTokens.KEYWORD && isTypeContext(leaf))) {
        paint(leaf.textRange, SqlColors.FUNCTION)
        return
      }
    }
    if (type == SqlTokens.KEYWORD && word in SqlFunctions.TYPES && isTypeContext(leaf)) paint(leaf.textRange, SqlColors.DATA_TYPE)
  }

  private fun isTypeContext(leaf: PsiElement): Boolean {
    val previous = significant(leaf, forward = false)
    if (previous?.text == "::" || previous?.text.equals("AS", ignoreCase = true)) return true
    return PsiTreeUtil.getParentOfType(leaf, SqlStatement::class.java)?.firstKeyword in setOf("CREATE", "ALTER", "DECLARE")
  }

  private fun significant(leaf: PsiElement, forward: Boolean): PsiElement? {
    var e = if (forward) PsiTreeUtil.nextLeaf(leaf) else PsiTreeUtil.prevLeaf(leaf)
    while (e != null && (e.node.elementType == TokenType.WHITE_SPACE || e.node.elementType in SqlTokens.COMMENTS)) {
      e = if (forward) PsiTreeUtil.nextLeaf(e) else PsiTreeUtil.prevLeaf(e)
    }
    return e
  }
}

/** Settings → Editor → Color Scheme → SQL (Phantom). */
class SqlColorSettingsPage : ColorSettingsPage {
  private val descriptors = listOf(
    "keyword" to SqlColors.KEYWORD, "function" to SqlColors.FUNCTION, "type" to SqlColors.DATA_TYPE,
    "table" to SqlColors.TABLE, "schema" to SqlColors.SCHEMA, "column" to SqlColors.COLUMN, "alias" to SqlColors.ALIAS, "cte" to SqlColors.CTE,
    "identifier" to SqlColors.IDENTIFIER, "quoted" to SqlColors.QUOTED_IDENTIFIER, "string" to SqlColors.STRING, "number" to SqlColors.NUMBER,
    "parameter" to SqlColors.PARAMETER, "operator" to SqlColors.OPERATOR, "line.comment" to SqlColors.LINE_COMMENT, "block.comment" to SqlColors.BLOCK_COMMENT,
    "parentheses" to SqlColors.PARENTHESES, "comma" to SqlColors.COMMA, "dot" to SqlColors.DOT, "semicolon" to SqlColors.SEMICOLON,
  ).map { (name, key) -> AttributesDescriptor(PhantomDbBundle.message("sql.color.$name"), key) }.toTypedArray()

  override fun getIcon(): Icon = SqlFileType.INSTANCE.icon
  override fun getHighlighter(): SyntaxHighlighter = SqlSyntaxHighlighter()
  override fun getDisplayName(): String = "SQL (Phantom)"
  override fun getAttributeDescriptors(): Array<AttributesDescriptor> = descriptors
  override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY

  override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey> = mapOf(
    "t" to SqlColors.TABLE, "s" to SqlColors.SCHEMA, "c" to SqlColors.COLUMN, "a" to SqlColors.ALIAS, "e" to SqlColors.CTE, "f" to SqlColors.FUNCTION, "d" to SqlColors.DATA_TYPE,
  )

  override fun getDemoText(): String = """
    -- recent orders
    WITH <e>recent</e> AS (
      SELECT <c>customer_id</c>, <f>count</f>(*) AS <a>total</a>
      FROM <s>shop</s>.<t>orders</t> <a>o</a>
      WHERE <a>o</a>.<c>created_at</c> > :since AND <a>o</a>.<c>note</c> <> 'test'
      GROUP BY <c>customer_id</c>
    )
    /* block comment */
    SELECT <a>c</a>.`<c>display name</c>`, <a>r</a>.<c>total</c> FROM <t>customer</t> <a>c</a> JOIN <t>recent</t> <a>r</a> ON <a>r</a>.<c>customer_id</c> = <a>c</a>.<c>id</c> LIMIT 10;

    CREATE TABLE <t>coupon</t> (<c>id</c> <d>BIGINT</d> PRIMARY KEY, <c>code</c> <d>VARCHAR</d>(20) NOT NULL);
  """.trimIndent()
}
