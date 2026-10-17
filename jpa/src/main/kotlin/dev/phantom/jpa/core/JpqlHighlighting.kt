// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.jpa.core

import com.intellij.lang.BracePair
import com.intellij.lang.PairedBraceMatcher
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as Colors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMember
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

object JpqlColors {
  val KEYWORD: TextAttributesKey = TextAttributesKey.createTextAttributesKey("JPQL_KEYWORD", Colors.KEYWORD)
  val STRING: TextAttributesKey = TextAttributesKey.createTextAttributesKey("JPQL_STRING", Colors.STRING)
  val NUMBER: TextAttributesKey = TextAttributesKey.createTextAttributesKey("JPQL_NUMBER", Colors.NUMBER)
  val PARAMETER: TextAttributesKey = TextAttributesKey.createTextAttributesKey("JPQL_PARAMETER", Colors.PARAMETER)
  val COMMENT: TextAttributesKey = TextAttributesKey.createTextAttributesKey("JPQL_COMMENT", Colors.LINE_COMMENT)
  val OPERATOR: TextAttributesKey = TextAttributesKey.createTextAttributesKey("JPQL_OPERATOR", Colors.OPERATION_SIGN)
  val ENTITY: TextAttributesKey = TextAttributesKey.createTextAttributesKey("JPQL_ENTITY", Colors.CLASS_REFERENCE)
  val ALIAS: TextAttributesKey = TextAttributesKey.createTextAttributesKey("JPQL_ALIAS", Colors.LOCAL_VARIABLE)
  val ATTRIBUTE: TextAttributesKey = TextAttributesKey.createTextAttributesKey("JPQL_ATTRIBUTE", Colors.INSTANCE_FIELD)
  val FUNCTION: TextAttributesKey = TextAttributesKey.createTextAttributesKey("JPQL_FUNCTION", Colors.FUNCTION_CALL)
  val PLACEHOLDER: TextAttributesKey = TextAttributesKey.createTextAttributesKey("JPQL_PLACEHOLDER", Colors.TEMPLATE_LANGUAGE_COLOR)
}

class JpqlSyntaxHighlighter : SyntaxHighlighterBase() {
  override fun getHighlightingLexer(): Lexer = JpqlLexer()

  override fun getTokenHighlights(tokenType: IElementType?): Array<TextAttributesKey> = pack(
    when (tokenType) {
      JpqlTokens.KEYWORD -> JpqlColors.KEYWORD
      JpqlTokens.STRING -> JpqlColors.STRING
      JpqlTokens.NUMBER -> JpqlColors.NUMBER
      JpqlTokens.PARAMETER -> JpqlColors.PARAMETER
      JpqlTokens.COMMENT -> JpqlColors.COMMENT
      JpqlTokens.OPERATOR -> JpqlColors.OPERATOR
      JpqlTokens.PLACEHOLDER -> JpqlColors.PLACEHOLDER
      TokenType.BAD_CHARACTER -> Colors.INVALID_STRING_ESCAPE
      else -> null
    }
  )
}

class JpqlSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
  override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter = JpqlSyntaxHighlighter()
}

class JpqlBraceMatcher : PairedBraceMatcher {
  override fun getPairs(): Array<BracePair> = arrayOf(BracePair(JpqlTokens.LPAREN, JpqlTokens.RPAREN, false))
  override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, contextType: IElementType?): Boolean = true
  override fun getCodeConstructStart(file: PsiFile?, openingBraceOffset: Int): Int = openingBraceOffset
}

/**
 * Semantic colors and the two errors that are safe to report on any query: an unknown entity name, and an unknown
 * attribute of a known entity/embeddable. Queries built from Kotlin templates are only partially known, so entity
 * errors are skipped there.
 */
class JpqlAnnotator : Annotator {
  override fun annotate(element: PsiElement, holder: AnnotationHolder) {
    when (element) {
      is JpqlEntityRef -> {
        val entity = element.resolveEntity()
        if (entity != null) color(holder, element, JpqlColors.ENTITY)
        else if ((element.containingFile as? JpqlFile)?.hasPlaceholders != true) {
          holder.newAnnotation(HighlightSeverity.ERROR, PhantomJpaBundle.message("jpql.unresolved.entity", element.text)).range(element).create()
        }
      }
      is JpqlAliasDecl -> color(holder, element, JpqlColors.ALIAS)
      is JpqlFunctionName -> color(holder, element, JpqlColors.FUNCTION)
      is JpqlPathSegment -> {
        val target = element.resolveTarget()
        when {
          target is JpqlAliasDecl -> color(holder, element, JpqlColors.ALIAS)
          target is PsiMember -> color(holder, element, JpqlColors.ATTRIBUTE)
          element.index > 0 -> {
            val owner = element.ownerType()
            if (owner != null && JpaModel.isPersistent(owner)) {
              holder.newAnnotation(HighlightSeverity.ERROR, PhantomJpaBundle.message("jpql.unresolved.attribute", element.text, owner.name ?: ""))
                .range(element).create()
            }
          }
        }
      }
    }
  }

  private fun color(holder: AnnotationHolder, element: PsiElement, key: TextAttributesKey) {
    holder.newSilentAnnotation(HighlightSeverity.INFORMATION).range(element).textAttributes(key).create()
  }
}
