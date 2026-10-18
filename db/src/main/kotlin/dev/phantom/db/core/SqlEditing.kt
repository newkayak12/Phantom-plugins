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
import dev.phantom.db.core.exec.DbConsoles
import dev.phantom.db.core.exec.DbMetadataCache
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
 * Keywords, plus table and column names the schema tree has already loaded for the file's data source. Completion
 * never opens a connection: with an empty cache only keywords are offered.
 */
class SqlCompletionContributor : CompletionContributor() {
  init {
    extend(CompletionType.BASIC, PlatformPatterns.psiElement().withLanguage(SqlLanguage), object : CompletionProvider<CompletionParameters>() {
      override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
        val position = parameters.position
        if (position.node.elementType in SqlTokens.COMMENTS || position.node.elementType == SqlTokens.STRING) return
        val file = parameters.originalFile
        val tables = cachedTables(file)
        val qualifier = qualifierBefore(position)
        if (qualifier != null) {
          val table = aliasTarget(position, qualifier) ?: qualifier
          tables.firstOrNull { it.name.equals(table, ignoreCase = true) }?.let { t ->
            DbMetadataCache.cachedColumns(file.project, t)?.forEach { column ->
              result.addElement(LookupElementBuilder.create(column.name).withIcon(AllIcons.Nodes.DataColumn).withTypeText(column.typeName))
            }
          }
          return
        }
        if (previousKeyword(position) in TABLE_CONTEXT) {
          for (t in tables) {
            result.addElement(LookupElementBuilder.create(t.name).withIcon(AllIcons.Nodes.DataTables).withTypeText(t.schema))
          }
        }
        val upper = result.prefixMatcher.prefix.firstOrNull()?.isUpperCase() != false
        for (k in SqlTokens.KEYWORDS) {
          result.addElement(LookupElementBuilder.create(if (upper) k else k.lowercase()).bold())
        }
      }
    })
  }

  private fun cachedTables(file: PsiFile): List<DbTableRef> {
    val vFile = file.virtualFile ?: file.viewProvider.virtualFile
    // SQL injected into a Java or Kotlin string has no console of its own: use a data source whose schema is loaded
    val dataSource = DbConsoles.dataSourceFor(file.project, vFile)
                     ?: DbDataSourceService.getInstance(file.project).dataSources.takeIf {
                       com.intellij.lang.injection.InjectedLanguageManager.getInstance(file.project).isInjectedFragment(file)
                     }?.firstOrNull { DbMetadataCache.cachedTables(file.project, it.id).isNotEmpty() }
                     ?: return emptyList()
    return DbMetadataCache.cachedTables(file.project, dataSource.id)
  }

  /** `t` in `t.<caret>`. */
  private fun qualifierBefore(position: PsiElement): String? {
    val dot = PsiTreeUtil.prevLeaf(position) ?: return null
    if (dot.node.elementType != SqlTokens.DOT) return null
    val name = PsiTreeUtil.prevLeaf(dot) ?: return null
    return if (name.node.elementType in SqlTokens.NAMES || name.node.elementType == SqlTokens.KEYWORD) unquote(name.text) else null
  }

  /** `reservation` for alias `r` in `FROM reservation r` / `JOIN reservation AS r`. */
  private fun aliasTarget(position: PsiElement, alias: String): String? {
    val statement = PsiTreeUtil.getParentOfType(position, SqlStatement::class.java) ?: return null
    val tokens = generateSequence(PsiTreeUtil.firstChild(statement)) { PsiTreeUtil.nextLeaf(it) }
      .takeWhile { it.textRange.endOffset <= statement.textRange.endOffset }
      .filter { it.node.elementType != TokenType.WHITE_SPACE && it.node.elementType !in SqlTokens.COMMENTS }
      .toList()
    for (i in 1 until tokens.size) {
      if (tokens[i].node.elementType !in SqlTokens.NAMES || !unquote(tokens[i].text).equals(alias, ignoreCase = true)) continue
      var j = i - 1
      if (tokens[j].text.equals("AS", ignoreCase = true) && j > 0) j--
      if (tokens[j].node.elementType in SqlTokens.NAMES) return unquote(tokens[j].text)
    }
    return null
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

    fun unquote(s: String): String = s.trim('`', '"', '[', ']')
  }
}
