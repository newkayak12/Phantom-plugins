// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.graphql.lang

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import dev.phantom.graphql.psi.GraphQLFieldDefinition
import dev.phantom.graphql.psi.GraphQLFile
import dev.phantom.graphql.psi.GraphQLFragmentDefinition
import dev.phantom.graphql.psi.GraphQLFragmentSpread
import dev.phantom.graphql.psi.GraphQLNamedElement
import dev.phantom.graphql.psi.GraphQLTypeDefinition
import dev.phantom.graphql.psi.GraphQLTypeExtension
import dev.phantom.graphql.psi.GraphQLTypeReference
import dev.phantom.graphql.lang.GraphQLElementTypes as E

class GraphQLParserDefinition : ParserDefinition {
  override fun createLexer(project: Project?): Lexer = GraphQLLexer()

  override fun createParser(project: Project?): PsiParser = GraphQLParser()

  override fun getFileNodeType(): IFileElementType = E.FILE

  override fun getWhitespaceTokens(): TokenSet = GraphQLTokenTypes.SKIPPED

  override fun getCommentTokens(): TokenSet = GraphQLTokenTypes.COMMENTS

  override fun getStringLiteralElements(): TokenSet = GraphQLTokenTypes.STRINGS

  override fun createElement(node: ASTNode): PsiElement = when (node.elementType) {
    E.TYPE_DEFINITION -> GraphQLTypeDefinition(node)
    E.FIELD_DEFINITION -> GraphQLFieldDefinition(node)
    E.FRAGMENT_DEFINITION -> GraphQLFragmentDefinition(node)
    E.INPUT_VALUE_DEFINITION, E.ENUM_VALUE_DEFINITION, E.DIRECTIVE_DEFINITION -> GraphQLNamedElement(node)
    E.TYPE_EXTENSION -> GraphQLTypeExtension(node)
    E.TYPE_REFERENCE -> GraphQLTypeReference(node)
    E.FRAGMENT_SPREAD -> GraphQLFragmentSpread(node)
    else -> ASTWrapperPsiElement(node)
  }

  override fun createFile(viewProvider: FileViewProvider): PsiFile = GraphQLFile(viewProvider)
}
