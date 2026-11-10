// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core

import com.intellij.codeInsight.editorActions.SimpleTokenSetQuoteHandler
import com.intellij.codeInsight.hints.declarative.HintFormat
import com.intellij.codeInsight.hints.declarative.InlayHintsCollector
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.InlineInlayPosition
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction
import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.lang.parameterInfo.CreateParameterInfoContext
import com.intellij.lang.parameterInfo.ParameterInfoHandler
import com.intellij.lang.parameterInfo.ParameterInfoUIContext
import com.intellij.lang.parameterInfo.UpdateParameterInfoContext
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.highlighter.HighlighterIterator
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.TokenType
import com.intellij.psi.util.PsiTreeUtil

/** Folds multi-line parentheses (subqueries, column lists, long calls) and block comments. */
class SqlFoldingBuilder : FoldingBuilderEx(), DumbAware {
  override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
    val result = ArrayList<FoldingDescriptor>()
    fun multiline(range: TextRange) = document.getLineNumber(range.startOffset) < document.getLineNumber(range.endOffset)
    root.accept(object : PsiRecursiveElementWalkingVisitor() {
      override fun visitElement(element: PsiElement) {
        if (element is PsiComment && element.node.elementType == SqlTokens.BLOCK_COMMENT && multiline(element.textRange)) {
          result += FoldingDescriptor(element.node, element.textRange, null, "/*...*/")
        }
        // parentheses are flat tokens among the children of a statement or subquery
        if (element is SqlScope) {
          val open = ArrayList<PsiElement>()
          var child = element.firstChild
          while (child != null) {
            when (child.node.elementType) {
              SqlTokens.LPAREN -> open += child
              SqlTokens.RPAREN -> open.removeLastOrNull()?.let { start ->
                val range = TextRange(start.textRange.startOffset, child.textRange.endOffset)
                if (multiline(range)) result += FoldingDescriptor(start.node, range, null, "(...)")
              }
            }
            child = child.nextSibling
          }
        }
        super.visitElement(element)
      }
    })
    return result.toTypedArray()
  }

  override fun getPlaceholderText(node: ASTNode): String = "..."
  override fun isCollapsedByDefault(node: ASTNode): Boolean = false
}

/** The parameters of a built-in function, shown while its arguments are typed. */
class SqlSignature(val name: String, val parameters: List<String>)

internal object SqlSignatures {
  private val PARAMETERS: Map<String, String> = mapOf(
    "COUNT" to "expression", "SUM" to "expression", "AVG" to "expression", "MIN" to "expression", "MAX" to "expression",
    "COALESCE" to "value, value...", "NULLIF" to "value, value_if_equal", "IFNULL" to "value, value_if_null", "IF" to "condition, then, else",
    "CAST" to "expression AS type", "CONCAT" to "text, text...", "CONCAT_WS" to "separator, text, text...", "LOWER" to "text", "UPPER" to "text",
    "LENGTH" to "text", "CHAR_LENGTH" to "text", "SUBSTRING" to "text, start, [length]", "SUBSTR" to "text, start, [length]", "TRIM" to "text",
    "LTRIM" to "text", "RTRIM" to "text", "REPLACE" to "text, from, to", "LEFT" to "text, length", "RIGHT" to "text, length",
    "LPAD" to "text, length, [fill]", "RPAD" to "text, length, [fill]", "INSTR" to "text, search", "LOCATE" to "search, text, [start]",
    "POSITION" to "search IN text", "STRPOS" to "text, search", "SPLIT_PART" to "text, delimiter, field", "REVERSE" to "text", "REPEAT" to "text, count",
    "SUBSTRING_INDEX" to "text, delimiter, count", "FIELD" to "value, value...", "FIND_IN_SET" to "value, list",
    "REGEXP_REPLACE" to "text, pattern, replacement, [flags]", "ROUND" to "number, [decimals]", "FLOOR" to "number", "CEIL" to "number",
    "CEILING" to "number", "ABS" to "number", "MOD" to "dividend, divisor", "POWER" to "base, exponent", "POW" to "base, exponent", "SQRT" to "number",
    "TRUNCATE" to "number, decimals", "GREATEST" to "value, value...", "LEAST" to "value, value...",
    "ROW_NUMBER" to "", "RANK" to "", "DENSE_RANK" to "", "NTILE" to "buckets", "LAG" to "expression, [offset], [default]",
    "LEAD" to "expression, [offset], [default]", "FIRST_VALUE" to "expression", "LAST_VALUE" to "expression",
    "NOW" to "", "CURDATE" to "", "CURTIME" to "", "DATE_FORMAT" to "date, format", "DATE_ADD" to "date, INTERVAL n unit",
    "DATE_SUB" to "date, INTERVAL n unit", "DATEDIFF" to "end, start", "TIMESTAMPDIFF" to "unit, start, end", "TIMESTAMPADD" to "unit, n, timestamp",
    "STR_TO_DATE" to "text, format", "UNIX_TIMESTAMP" to "[date]", "FROM_UNIXTIME" to "seconds, [format]", "LAST_DAY" to "date",
    "YEAR" to "date", "MONTH" to "date", "DAY" to "date", "HOUR" to "time", "MINUTE" to "time", "SECOND" to "time", "DATE" to "expression",
    "GROUP_CONCAT" to "expression [ORDER BY ...] [SEPARATOR text]", "JSON_EXTRACT" to "json, path, path...", "JSON_UNQUOTE" to "json",
    "JSON_OBJECT" to "key, value...", "JSON_ARRAY" to "value...", "JSON_CONTAINS" to "json, candidate, [path]", "JSON_LENGTH" to "json, [path]",
    "LAST_INSERT_ID" to "[expression]", "UUID" to "", "AGE" to "timestamp, [timestamp]", "DATE_TRUNC" to "unit, timestamp, [zone]",
    "DATE_PART" to "unit, timestamp", "EXTRACT" to "unit FROM date", "TO_CHAR" to "value, format", "TO_DATE" to "text, format",
    "TO_TIMESTAMP" to "text, [format]", "TO_NUMBER" to "text, format", "STRING_AGG" to "expression, delimiter", "ARRAY_AGG" to "expression",
    "JSON_AGG" to "expression", "JSONB_AGG" to "expression", "JSON_BUILD_OBJECT" to "key, value...", "JSONB_BUILD_OBJECT" to "key, value...",
    "UNNEST" to "array", "GENERATE_SERIES" to "start, stop, [step]", "GEN_RANDOM_UUID" to "", "DATEADD" to "unit, n, date",
    "FORMATDATETIME" to "timestamp, format", "PARSEDATETIME" to "text, format", "LISTAGG" to "expression, [delimiter]", "RANDOM_UUID" to "",
  )

  fun of(name: String): SqlSignature? {
    val upper = name.uppercase()
    val parameters = PARAMETERS[upper] ?: return null
    return SqlSignature(upper, if (parameters.isEmpty()) emptyList() else parameters.split(", "))
  }

  /** The call whose argument list holds [offset], with the index of the argument the caret is in. */
  class Call(val open: PsiElement, val signature: SqlSignature, val index: Int)

  fun callAt(file: PsiFile, offset: Int): Call? {
    var leaf = if (offset > 0) file.findElementAt(offset - 1) else null
    var depth = 0
    var commas = 0
    while (leaf != null) {
      when (leaf.node.elementType) {
        SqlTokens.SEMICOLON -> return null
        SqlTokens.RPAREN -> depth++
        SqlTokens.COMMA -> if (depth == 0) commas++
        SqlTokens.LPAREN -> {
          if (depth > 0) depth--
          else {
            val name = significantBefore(leaf) ?: return null
            if (name.node.elementType != SqlTokens.IDENTIFIER && name.node.elementType != SqlTokens.KEYWORD) return null
            if (significantBefore(name)?.node?.elementType == SqlTokens.DOT) return null
            return of(name.text)?.let { Call(leaf!!, it, commas) }
          }
        }
      }
      leaf = PsiTreeUtil.prevLeaf(leaf)
    }
    return null
  }

  private fun significantBefore(element: PsiElement): PsiElement? {
    var leaf = PsiTreeUtil.prevLeaf(element)
    while (leaf != null && (leaf is PsiWhiteSpace || leaf is PsiComment || leaf.node.elementType == TokenType.WHITE_SPACE)) leaf = PsiTreeUtil.prevLeaf(leaf)
    return leaf
  }
}

class SqlParameterInfoHandler : ParameterInfoHandler<PsiElement, SqlSignature>, DumbAware {
  override fun findElementForParameterInfo(context: CreateParameterInfoContext): PsiElement? {
    val call = SqlSignatures.callAt(context.file, context.offset) ?: return null
    context.itemsToShow = arrayOf(call.signature)
    return call.open
  }

  override fun showParameterInfo(element: PsiElement, context: CreateParameterInfoContext) =
    context.showHint(element, element.textRange.startOffset, this)

  override fun findElementForUpdatingParameterInfo(context: UpdateParameterInfoContext): PsiElement? =
    SqlSignatures.callAt(context.file, context.offset)?.open

  override fun updateParameterInfo(parameterOwner: PsiElement, context: UpdateParameterInfoContext) {
    if (context.parameterOwner != parameterOwner) {
      context.removeHint()
      return
    }
    context.setCurrentParameter(SqlSignatures.callAt(context.file, context.offset)?.index ?: 0)
  }

  override fun updateUI(p: SqlSignature, context: ParameterInfoUIContext) {
    if (p.parameters.isEmpty()) {
      context.setupUIComponentPresentation(PhantomDbBundle.message("sql.parameter.info.none"), -1, -1, false, false, false, context.defaultParameterColor)
      return
    }
    // the last parameter of `value, value...` takes the rest
    val current = context.currentParameterIndex
    val index = when {
      current < p.parameters.size -> current
      p.parameters.last().endsWith("...") -> p.parameters.size - 1
      else -> -1
    }
    var start = -1
    var end = -1
    val text = StringBuilder()
    for ((i, parameter) in p.parameters.withIndex()) {
      if (i > 0) text.append(", ")
      if (i == index) start = text.length
      text.append(parameter)
      if (i == index) end = text.length
    }
    context.setupUIComponentPresentation(text.toString(), start, end, false, false, false, context.defaultParameterColor)
  }
}

/** `INSERT INTO t (a, b) VALUES (1, 'x')`: the column each value goes to, shown before it. */
internal object SqlInsertHints {
  fun hints(statement: SqlStatement): List<Pair<Int, String>> {
    val insert = SqlQueries.insert(statement) ?: return emptyList()
    if (insert.rows.isEmpty()) return emptyList()
    val names = insert.columns.map { it.name }.ifEmpty { targetColumns(insert.target) }
    if (names.isEmpty()) return emptyList()
    val result = ArrayList<Pair<Int, String>>()
    for (row in insert.rows) {
      for ((i, value) in row.values.withIndex()) {
        val name = names.getOrNull(i) ?: break
        val first = value.firstOrNull() ?: continue
        // `VALUES (name, ...)` naming the column itself needs no hint
        if (value.size == 1 && (first.element as? SqlColumnRef)?.columnName?.let { SqlNames.same(it, name) } == true) continue
        result += first.element.textRange.startOffset to name
      }
    }
    return result
  }

  /** Without a column list: the target's columns when they are already loaded. */
  private fun targetColumns(target: SqlTableRef?): List<String> {
    target ?: return emptyList()
    val table = (SqlResolver.table(target, load = false) as? SqlResolution.Found)?.target as? SqlDbTarget ?: return emptyList()
    return SqlSchema.getInstance(target.project).columnsNoWait(table.dataSource, table.table)?.map { it.name }.orEmpty()
  }
}

class SqlInsertColumnHintsProvider : InlayHintsProvider {
  override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector? {
    if (file !is SqlFile) return null
    return object : SharedBypassCollector {
      override fun collectFromElement(element: PsiElement, sink: InlayTreeSink) {
        if (element !is SqlStatement) return
        for ((offset, name) in SqlInsertHints.hints(element)) {
          sink.addPresentation(InlineInlayPosition(offset, false), null, null, HintFormat.default) { text("$name:") }
        }
      }
    }
  }
}

/** `SELECT *` or `t.*` → the columns, from loaded metadata. */
class SqlExpandStarIntention : PsiElementBaseIntentionAction() {
  override fun getFamilyName(): String = PhantomDbBundle.message("sql.intention.expand.star")
  override fun getText(): String = familyName
  override fun startInWriteAction(): Boolean = true

  override fun isAvailable(project: Project, editor: Editor, element: PsiElement): Boolean = expansion(editor, element.containingFile) != null

  override fun invoke(project: Project, editor: Editor, element: PsiElement) {
    val (range, text) = expansion(editor, element.containingFile) ?: return
    editor.document.replaceString(range.startOffset, range.endOffset, text)
  }

  internal companion object {
    private val BEFORE_ITEM = setOf("SELECT", "DISTINCT", "ALL")

    /** The text range of the star (with its qualifier) and the column list that replaces it. */
    fun expansion(editor: Editor, file: PsiFile): Pair<TextRange, String>? {
      if (file !is SqlFile) return null
      val offset = editor.caretModel.offset
      val star = listOfNotNull(file.findElementAt(offset), if (offset > 0) file.findElementAt(offset - 1) else null)
        .firstOrNull { it.node.elementType == SqlTokens.OPERATOR && it.text == "*" } ?: return null
      val scope = star.parent as? SqlScope ?: return null
      val before = PsiTreeUtil.skipWhitespacesAndCommentsBackward(star) ?: return null
      if (before.node.elementType == SqlTokens.DOT) {
        val qualifier = PsiTreeUtil.skipWhitespacesAndCommentsBackward(before) as? SqlQualifier ?: return null
        val table = when (val r = SqlResolver.qualifier(qualifier)) {
          is SqlResolution.Found -> (r.target as? SqlAlias)?.table ?: r.target as? SqlTableRef
          else -> null
        } ?: return null
        val columns = columns(table) ?: return null
        return TextRange(qualifier.textRange.startOffset, star.textRange.endOffset) to columns.joinToString(", ") { "${qualifier.text}.$it" }
      }
      if (before.node.elementType != SqlTokens.COMMA && !(before.node.elementType == SqlTokens.KEYWORD && before.text.uppercase() in BEFORE_ITEM)) return null
      // a derived table's columns are not in the metadata
      if (SqlScopes.direct(scope, SqlAlias::class.java).any { it.table == null }) return null
      val tables = SqlScopes.direct(scope, SqlTableRef::class.java)
      if (tables.isEmpty()) return null
      val names = ArrayList<String>()
      for (table in tables) {
        val columns = columns(table) ?: return null
        val prefix = if (tables.size == 1) "" else (table.alias?.name ?: table.tableName) + "."
        columns.mapTo(names) { prefix + it }
      }
      return star.textRange to names.joinToString(", ")
    }

    private fun columns(table: SqlTableRef): List<String>? {
      val target = (SqlResolver.table(table, load = false) as? SqlResolution.Found)?.target as? SqlDbTarget ?: return null
      return SqlSchema.getInstance(table.project).columnsNoWait(target.dataSource, target.table)?.map { it.name }?.takeIf { it.isNotEmpty() }
    }
  }
}

/** Typing `'`, `"` or `` ` `` adds the closing one; typing it before the closing one steps over it. */
class SqlQuoteHandler : SimpleTokenSetQuoteHandler(SqlTokens.STRING, SqlTokens.QUOTED_IDENTIFIER) {
  override fun isNonClosedLiteral(iterator: HighlighterIterator, chars: CharSequence): Boolean {
    val start = iterator.start
    val end = iterator.end
    if (end - start < 2) return true
    val open = chars[start]
    val close = if (open == '[') ']' else open
    return open in "'\"`[" && chars[end - 1] != close
  }
}
