// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.lang.BracePair
import com.intellij.lang.CodeDocumentationAwareCommenter
import com.intellij.lang.PairedBraceMatcher
import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import dev.phantom.db.core.exec.DbColumnInfo
import dev.phantom.db.core.exec.DbTableRef

object SqlColors {
  @JvmField val KEYWORD: TextAttributesKey = TextAttributesKey.createTextAttributesKey("PHANTOM_SQL_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD)
  @JvmField val IDENTIFIER: TextAttributesKey = TextAttributesKey.createTextAttributesKey("PHANTOM_SQL_IDENTIFIER", DefaultLanguageHighlighterColors.IDENTIFIER)
  @JvmField val QUOTED_IDENTIFIER: TextAttributesKey = TextAttributesKey.createTextAttributesKey("PHANTOM_SQL_QUOTED_IDENTIFIER", DefaultLanguageHighlighterColors.INSTANCE_FIELD)
  @JvmField val STRING: TextAttributesKey = TextAttributesKey.createTextAttributesKey("PHANTOM_SQL_STRING", DefaultLanguageHighlighterColors.STRING)
  @JvmField val NUMBER: TextAttributesKey = TextAttributesKey.createTextAttributesKey("PHANTOM_SQL_NUMBER", DefaultLanguageHighlighterColors.NUMBER)
  @JvmField val LINE_COMMENT: TextAttributesKey = TextAttributesKey.createTextAttributesKey("PHANTOM_SQL_LINE_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT)
  @JvmField val BLOCK_COMMENT: TextAttributesKey = TextAttributesKey.createTextAttributesKey("PHANTOM_SQL_BLOCK_COMMENT", DefaultLanguageHighlighterColors.BLOCK_COMMENT)
  @JvmField val PARAMETER: TextAttributesKey = TextAttributesKey.createTextAttributesKey("PHANTOM_SQL_PARAMETER", DefaultLanguageHighlighterColors.PARAMETER)
  @JvmField val OPERATOR: TextAttributesKey = TextAttributesKey.createTextAttributesKey("PHANTOM_SQL_OPERATOR", DefaultLanguageHighlighterColors.OPERATION_SIGN)
  @JvmField val SEMICOLON: TextAttributesKey = TextAttributesKey.createTextAttributesKey("PHANTOM_SQL_SEMICOLON", DefaultLanguageHighlighterColors.SEMICOLON)
  @JvmField val COMMA: TextAttributesKey = TextAttributesKey.createTextAttributesKey("PHANTOM_SQL_COMMA", DefaultLanguageHighlighterColors.COMMA)
  @JvmField val DOT: TextAttributesKey = TextAttributesKey.createTextAttributesKey("PHANTOM_SQL_DOT", DefaultLanguageHighlighterColors.DOT)
  @JvmField val PARENTHESES: TextAttributesKey = TextAttributesKey.createTextAttributesKey("PHANTOM_SQL_PARENTHESES", DefaultLanguageHighlighterColors.PARENTHESES)
}

class SqlSyntaxHighlighter : SyntaxHighlighterBase() {
  override fun getHighlightingLexer(): Lexer = SqlLexer()

  override fun getTokenHighlights(tokenType: IElementType?): Array<TextAttributesKey> = pack(
    when (tokenType) {
      SqlTokens.KEYWORD -> SqlColors.KEYWORD
      SqlTokens.QUOTED_IDENTIFIER -> SqlColors.QUOTED_IDENTIFIER
      SqlTokens.STRING -> SqlColors.STRING
      SqlTokens.NUMBER -> SqlColors.NUMBER
      SqlTokens.LINE_COMMENT -> SqlColors.LINE_COMMENT
      SqlTokens.BLOCK_COMMENT -> SqlColors.BLOCK_COMMENT
      SqlTokens.PARAMETER -> SqlColors.PARAMETER
      SqlTokens.OPERATOR -> SqlColors.OPERATOR
      SqlTokens.SEMICOLON -> SqlColors.SEMICOLON
      SqlTokens.COMMA -> SqlColors.COMMA
      SqlTokens.DOT -> SqlColors.DOT
      SqlTokens.LPAREN, SqlTokens.RPAREN -> SqlColors.PARENTHESES
      TokenType.BAD_CHARACTER -> HighlighterColors.BAD_CHARACTER
      else -> null
    }
  )
}

class SqlSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
  override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter = SqlSyntaxHighlighter()
}

class SqlCommenter : CodeDocumentationAwareCommenter {
  override fun getLineCommentPrefix(): String = "-- "
  override fun getBlockCommentPrefix(): String = "/*"
  override fun getBlockCommentSuffix(): String = "*/"
  override fun getCommentedBlockCommentPrefix(): String? = null
  override fun getCommentedBlockCommentSuffix(): String? = null
  override fun getLineCommentTokenType(): IElementType = SqlTokens.LINE_COMMENT
  override fun getBlockCommentTokenType(): IElementType = SqlTokens.BLOCK_COMMENT
  override fun getDocumentationCommentTokenType(): IElementType? = null
  override fun getDocumentationCommentPrefix(): String? = null
  override fun getDocumentationCommentLinePrefix(): String? = null
  override fun getDocumentationCommentSuffix(): String? = null
  override fun isDocumentationComment(element: PsiComment?): Boolean = false
}

class SqlBraceMatcher : PairedBraceMatcher {
  override fun getPairs(): Array<BracePair> = PAIRS
  override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, contextType: IElementType?): Boolean = true
  override fun getCodeConstructStart(file: PsiFile?, openingBraceOffset: Int): Int = openingBraceOffset

  private companion object {
    val PAIRS = arrayOf(BracePair(SqlTokens.LPAREN, SqlTokens.RPAREN, false))
  }
}

/**
 * Keywords, tables and columns. The data source's schema loads on demand (completion waits for it briefly, never on
 * the EDT), so this works before the Database tree was opened. After `t.`: the columns of `t`, whether a table or an
 * alias. In a FROM list: tables, schemas and CTEs. Elsewhere: the columns of the query's tables, its aliases, keywords.
 */
class SqlCompletionContributor : CompletionContributor() {
  init {
    extend(CompletionType.BASIC, PlatformPatterns.psiElement().withLanguage(SqlLanguage), object : CompletionProvider<CompletionParameters>() {
      override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, resultSet: CompletionResultSet) {
        val position = parameters.position
        if (position.node.elementType in SqlTokens.COMMENTS || position.node.elementType == SqlTokens.STRING) return
        // right after a digit (`LIMIT 10`, `id = 2`) the lexer splits off the dummy identifier with an empty prefix,
        // which would offer every name and let Enter insert one instead of running or applying the text
        val offset = parameters.offset
        if (resultSet.prefixMatcher.prefix.isEmpty() && offset > 0 && parameters.editor.document.charsSequence[offset - 1].isDigit()) return
        val result = resultSet.caseInsensitive()
        // a WHERE / ORDER BY fragment over a grid: the columns it filters, then keywords
        dev.phantom.db.core.ui.DbFilterContext.of(parameters.originalFile)?.let { filter ->
          filter.columns().forEach { result.addElement(column(it, null)) }
          keywords(result)
          return
        }
        val project = position.project
        val dataSource = SqlSchema.dataSourceFor(parameters.originalFile, strict = false)
        val schema = dataSource?.let { SqlSchema.getInstance(project).snapshotFor(parameters.originalFile, it) }
        val columns = { table: DbTableRef -> dataSource?.let { SqlSchema.getInstance(project).columns(it, table) }.orEmpty() }
        val joins = dataSource?.let { SqlJoinCompletion(it, SqlSchema.getInstance(project)) }
        try {
          addCompletions(position, parameters, result, dataSource, schema, columns, joins)
        }
        finally {
          // foreign keys or columns are loading: ask again on the next keystroke
          if (joins?.pending == true) result.restartCompletionOnAnyPrefixChange()
        }
      }
    })
  }

  private fun addCompletions(position: PsiElement, parameters: CompletionParameters, result: CompletionResultSet, dataSource: DbDataSource?,
                             schema: SqlSchemaSnapshot?, columns: (DbTableRef) -> List<DbColumnInfo>, joins: SqlJoinCompletion?) {
    val project = position.project
    when (val parent = position.parent) {
      is SqlColumnRef -> {
        joins?.addOnConditions(parent, result)
        val q = parent.qualifier
        if (q != null) {
          qualifiedTable(q)?.let { t -> schema?.let { tableOf(t, it) } }?.let { columns(it).forEach { c -> result.addElement(column(c, null)) } }
          return
        }
        if (parent.isInsertColumn) {
          SqlScopes.chain(parent).firstOrNull()?.let { SqlScopes.direct(it, SqlTableRef::class.java).firstOrNull() }
            ?.let { t -> schema?.let { tableOf(t, it) } }?.let { columns(it).forEach { c -> result.addElement(column(c, null)) } }
          return
        }
        for (scope in SqlScopes.chain(parent)) {
          for (ref in SqlScopes.direct(scope, SqlTableRef::class.java)) {
            val table = schema?.let { tableOf(ref, it) } ?: continue
            columns(table).forEach { result.addElement(column(it, ref.alias?.name ?: table.name)) }
            ref.alias?.let { result.addElement(PrioritizedLookupElement.withPriority(LookupElementBuilder.create(it.name).withIcon(AllIcons.Nodes.Alias).withTypeText(table.name), 5.0)) }
          }
        }
        keywords(result)
      }
      is SqlTableRef -> {
        if (joins != null && schema != null && SqlJoinCompletion.isAfterJoin(position)) {
          PsiTreeUtil.skipWhitespacesAndCommentsBackward(parent)?.let { joins.addJoinTables(parent, it.text, schema, result) }
        }
        val schemaName = parent.schemaName
        val tables = if (schemaName == null || dataSource == null) schema
        else schema?.schema(schemaName)?.let { SqlSchema.getInstance(project).withSchema(dataSource, it, current = SqlSchema.schemaFor(parameters.originalFile)) }
        tables?.tables?.filter { schemaName == null || SqlNames.same(it.schema ?: it.catalog.orEmpty(), schemaName) }?.forEach { result.addElement(table(it)) }
        if (schemaName != null) return
        schema?.schemas?.forEach { result.addElement(PrioritizedLookupElement.withPriority(LookupElementBuilder.create(it.name).withIcon(AllIcons.Nodes.DataSchema), 2.0)) }
        SqlScopes.ctes(parent).forEach { result.addElement(PrioritizedLookupElement.withPriority(LookupElementBuilder.create(it.name).withIcon(AllIcons.Nodes.DataTables).withTypeText("CTE"), 8.0)) }
        keywords(result)
      }
      else -> {
        if (previousKeyword(position) in TABLE_CONTEXT) schema?.tables?.forEach { result.addElement(table(it)) }
        keywords(result)
      }
    }
  }

  /** The table `t` stands for in `t.x`: through an alias, or a table in FROM, or the name itself. */
  private fun qualifiedTable(q: SqlQualifier): SqlTableRef? = when (val r = SqlResolver.qualifier(q)) {
    is SqlResolution.Found -> when (val t = r.target) {
      is SqlTableRef -> t
      is SqlAlias -> t.table
      else -> null
    }
    else -> null
  } ?: PsiTreeUtil.getParentOfType(q, SqlStatement::class.java)?.let { st ->
    PsiTreeUtil.findChildrenOfType(st, SqlTableRef::class.java).firstOrNull { SqlNames.same(it.tableName, q.qualifierName) }
  }

  private fun tableOf(ref: SqlTableRef, schema: SqlSchemaSnapshot): DbTableRef? = schema.find(ref.tableName, ref.schemaName).firstOrNull()

  private fun column(c: DbColumnInfo, owner: String?) = PrioritizedLookupElement.withPriority(
    LookupElementBuilder.create(c.name).withIcon(AllIcons.Nodes.DataColumn).withTypeText(c.typeName).withTailText(owner?.let { " ($it)" }, true), 10.0)

  private fun table(t: DbTableRef) = PrioritizedLookupElement.withPriority(
    LookupElementBuilder.create(t.name).withIcon(AllIcons.Nodes.DataTables).withTypeText(t.schema ?: t.catalog), 5.0)

  private fun keywords(result: CompletionResultSet) {
    val upper = result.prefixMatcher.prefix.firstOrNull()?.isUpperCase() != false
    for (k in SqlTokens.KEYWORDS) {
      result.addElement(LookupElementBuilder.create(if (upper) k else k.lowercase()).bold())
    }
  }

  private fun previousKeyword(position: PsiElement): String? {
    var leaf = PsiTreeUtil.prevLeaf(position)
    while (leaf != null && (leaf.node.elementType == TokenType.WHITE_SPACE || leaf.node.elementType in SqlTokens.COMMENTS)) {
      leaf = PsiTreeUtil.prevLeaf(leaf)
    }
    return leaf?.takeIf { it.node.elementType == SqlTokens.KEYWORD }?.text?.uppercase()
  }

  private companion object {
    val TABLE_CONTEXT = setOf("FROM", "JOIN", "INTO", "UPDATE", "TABLE", "DESCRIBE", "DESC", "EXPLAIN")
  }
}
