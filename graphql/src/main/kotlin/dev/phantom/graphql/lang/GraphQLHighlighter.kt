// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.graphql.lang

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as D
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import dev.phantom.graphql.lang.GraphQLTokenTypes as T

object GraphQLColors {
  val KEYWORD = createTextAttributesKey("GRAPHQL_KEYWORD", D.KEYWORD)
  val STRING = createTextAttributesKey("GRAPHQL_STRING", D.STRING)
  val NUMBER = createTextAttributesKey("GRAPHQL_NUMBER", D.NUMBER)
  val COMMENT = createTextAttributesKey("GRAPHQL_COMMENT", D.LINE_COMMENT)
  val BRACES = createTextAttributesKey("GRAPHQL_BRACES", D.BRACES)
  val PARENTHESES = createTextAttributesKey("GRAPHQL_PARENTHESES", D.PARENTHESES)
  val BRACKETS = createTextAttributesKey("GRAPHQL_BRACKETS", D.BRACKETS)
  val COMMA = createTextAttributesKey("GRAPHQL_COMMA", D.COMMA)
  val TYPE_NAME = createTextAttributesKey("GRAPHQL_TYPE_NAME", D.CLASS_NAME)
  val TYPE_REFERENCE = createTextAttributesKey("GRAPHQL_TYPE_REFERENCE", D.CLASS_REFERENCE)
  val DIRECTIVE = createTextAttributesKey("GRAPHQL_DIRECTIVE", D.METADATA)
  val VARIABLE = createTextAttributesKey("GRAPHQL_VARIABLE", D.LOCAL_VARIABLE)
}

class GraphQLSyntaxHighlighter : SyntaxHighlighterBase() {
  override fun getHighlightingLexer() = GraphQLLexer()

  override fun getTokenHighlights(tokenType: IElementType?): Array<TextAttributesKey> = pack(
    when (tokenType) {
      T.KEYWORD -> GraphQLColors.KEYWORD
      T.STRING -> GraphQLColors.STRING
      T.NUMBER -> GraphQLColors.NUMBER
      T.COMMENT -> GraphQLColors.COMMENT
      T.LBRACE, T.RBRACE -> GraphQLColors.BRACES
      T.LPAREN, T.RPAREN -> GraphQLColors.PARENTHESES
      T.LBRACKET, T.RBRACKET -> GraphQLColors.BRACKETS
      T.COMMA -> GraphQLColors.COMMA
      T.AT -> GraphQLColors.DIRECTIVE
      T.DOLLAR -> GraphQLColors.VARIABLE
      TokenType.BAD_CHARACTER -> HighlighterColors.BAD_CHARACTER
      else -> null
    },
  )
}

class GraphQLSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
  override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter = GraphQLSyntaxHighlighter()
}

/** Colors names by role, which the lexer cannot tell: declared types, type references, directives and variables. */
class GraphQLAnnotator : Annotator {
  override fun annotate(element: PsiElement, holder: AnnotationHolder) {
    val parent = element.node.treeParent ?: return
    if (element.node.elementType !in T.NAMES) return
    val key = when (parent.elementType) {
      GraphQLElementTypes.TYPE_REFERENCE -> GraphQLColors.TYPE_REFERENCE
      GraphQLElementTypes.IDENTIFIER -> GraphQLColors.TYPE_NAME.takeIf { parent.treeParent?.elementType == GraphQLElementTypes.TYPE_DEFINITION }
      GraphQLElementTypes.DIRECTIVE -> GraphQLColors.DIRECTIVE
      GraphQLElementTypes.VARIABLE_DEFINITION, GraphQLElementTypes.VALUE -> GraphQLColors.VARIABLE.takeIf { element.prevSibling?.node?.elementType == T.DOLLAR }
      else -> null
    } ?: return
    holder.newSilentAnnotation(HighlightSeverity.INFORMATION).textAttributes(key).create()
  }
}
