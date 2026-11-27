// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.graphql.lang

import com.intellij.lang.BracePair
import com.intellij.lang.Commenter
import com.intellij.lang.PairedBraceMatcher
import com.intellij.lang.cacheBuilder.DefaultWordsScanner
import com.intellij.lang.cacheBuilder.WordsScanner
import com.intellij.lang.findUsages.FindUsagesProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import dev.phantom.graphql.GraphQLBundle
import dev.phantom.graphql.psi.GraphQLFieldDefinition
import dev.phantom.graphql.psi.GraphQLFragmentDefinition
import dev.phantom.graphql.psi.GraphQLNamedElement
import dev.phantom.graphql.psi.GraphQLTypeDefinition
import dev.phantom.graphql.lang.GraphQLTokenTypes as T

class GraphQLCommenter : Commenter {
  override fun getLineCommentPrefix(): String = "#"

  override fun getBlockCommentPrefix(): String? = null

  override fun getBlockCommentSuffix(): String? = null

  override fun getCommentedBlockCommentPrefix(): String? = null

  override fun getCommentedBlockCommentSuffix(): String? = null
}

class GraphQLBraceMatcher : PairedBraceMatcher {
  override fun getPairs(): Array<BracePair> = arrayOf(
    BracePair(T.LBRACE, T.RBRACE, true),
    BracePair(T.LPAREN, T.RPAREN, false),
    BracePair(T.LBRACKET, T.RBRACKET, false),
  )

  override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, contextType: IElementType?): Boolean = true

  override fun getCodeConstructStart(file: PsiFile?, openingBraceOffset: Int): Int = openingBraceOffset
}

class GraphQLFindUsagesProvider : FindUsagesProvider {
  override fun getWordsScanner(): WordsScanner = DefaultWordsScanner(GraphQLLexer(), T.NAMES, T.COMMENTS, T.STRINGS)

  override fun canFindUsagesFor(element: PsiElement): Boolean = element is GraphQLNamedElement

  override fun getHelpId(element: PsiElement): String? = null

  override fun getType(element: PsiElement): String = when (element) {
    is GraphQLTypeDefinition -> GraphQLBundle.message("element.type")
    is GraphQLFieldDefinition -> GraphQLBundle.message("element.field")
    is GraphQLFragmentDefinition -> GraphQLBundle.message("element.fragment")
    else -> GraphQLBundle.message("element.definition")
  }

  override fun getDescriptiveName(element: PsiElement): String = (element as? GraphQLNamedElement)?.name.orEmpty()

  override fun getNodeText(element: PsiElement, useFullName: Boolean): String = getDescriptiveName(element)
}
