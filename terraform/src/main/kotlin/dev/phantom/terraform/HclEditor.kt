// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.terraform

import com.intellij.lang.BracePair
import com.intellij.lang.Commenter
import com.intellij.lang.PairedBraceMatcher
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lang.cacheBuilder.DefaultWordsScanner
import com.intellij.lang.cacheBuilder.WordsScanner
import com.intellij.lang.findUsages.FindUsagesProvider
import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as C
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import dev.phantom.terraform.HclTokens as T

object HclColors {
  val KEYWORD: TextAttributesKey = TextAttributesKey.createTextAttributesKey("PHANTOM_TERRAFORM_KEYWORD", C.KEYWORD)
  val BLOCK_TYPE: TextAttributesKey = TextAttributesKey.createTextAttributesKey("PHANTOM_TERRAFORM_BLOCK_TYPE", C.KEYWORD)
  val ATTRIBUTE: TextAttributesKey = TextAttributesKey.createTextAttributesKey("PHANTOM_TERRAFORM_ATTRIBUTE", C.INSTANCE_FIELD)
  val STRING: TextAttributesKey = TextAttributesKey.createTextAttributesKey("PHANTOM_TERRAFORM_STRING", C.STRING)
  val TEMPLATE_BRACES: TextAttributesKey = TextAttributesKey.createTextAttributesKey("PHANTOM_TERRAFORM_TEMPLATE_BRACES", C.KEYWORD)
  val NUMBER: TextAttributesKey = TextAttributesKey.createTextAttributesKey("PHANTOM_TERRAFORM_NUMBER", C.NUMBER)
  val LINE_COMMENT: TextAttributesKey = TextAttributesKey.createTextAttributesKey("PHANTOM_TERRAFORM_LINE_COMMENT", C.LINE_COMMENT)
  val BLOCK_COMMENT: TextAttributesKey = TextAttributesKey.createTextAttributesKey("PHANTOM_TERRAFORM_BLOCK_COMMENT", C.BLOCK_COMMENT)
  val BRACES: TextAttributesKey = TextAttributesKey.createTextAttributesKey("PHANTOM_TERRAFORM_BRACES", C.BRACES)
  val BRACKETS: TextAttributesKey = TextAttributesKey.createTextAttributesKey("PHANTOM_TERRAFORM_BRACKETS", C.BRACKETS)
  val PARENTHESES: TextAttributesKey = TextAttributesKey.createTextAttributesKey("PHANTOM_TERRAFORM_PARENTHESES", C.PARENTHESES)
  val COMMA: TextAttributesKey = TextAttributesKey.createTextAttributesKey("PHANTOM_TERRAFORM_COMMA", C.COMMA)
  val DOT: TextAttributesKey = TextAttributesKey.createTextAttributesKey("PHANTOM_TERRAFORM_DOT", C.DOT)
  val OPERATOR: TextAttributesKey = TextAttributesKey.createTextAttributesKey("PHANTOM_TERRAFORM_OPERATOR", C.OPERATION_SIGN)
}

class HclSyntaxHighlighter : SyntaxHighlighterBase() {
  override fun getHighlightingLexer(): Lexer = HclLexer()

  override fun getTokenHighlights(tokenType: IElementType?): Array<TextAttributesKey> = pack(KEYS[tokenType])

  private companion object {
    val KEYS: Map<IElementType, TextAttributesKey> = mapOf(
      T.LITERAL_KEYWORD to HclColors.KEYWORD, T.FOR to HclColors.KEYWORD, T.IN to HclColors.KEYWORD, T.IF to HclColors.KEYWORD,
      T.QUOTE_OPEN to HclColors.STRING, T.QUOTE_CLOSE to HclColors.STRING, T.STRING_TEXT to HclColors.STRING, T.HEREDOC to HclColors.STRING,
      T.INTERPOLATION_START to HclColors.TEMPLATE_BRACES, T.DIRECTIVE_START to HclColors.TEMPLATE_BRACES,
      T.TEMPLATE_END to HclColors.TEMPLATE_BRACES, T.NUMBER to HclColors.NUMBER,
      T.LINE_COMMENT to HclColors.LINE_COMMENT, T.BLOCK_COMMENT to HclColors.BLOCK_COMMENT,
      T.LBRACE to HclColors.BRACES, T.RBRACE to HclColors.BRACES, T.LBRACKET to HclColors.BRACKETS, T.RBRACKET to HclColors.BRACKETS,
      T.LPAREN to HclColors.PARENTHESES, T.RPAREN to HclColors.PARENTHESES, T.COMMA to HclColors.COMMA, T.DOT to HclColors.DOT,
      T.OPERATOR to HclColors.OPERATOR, T.EQ to HclColors.OPERATOR, T.ARROW to HclColors.OPERATOR,
      T.QUESTION to HclColors.OPERATOR, T.COLON to HclColors.OPERATOR,
    )
  }
}

class HclSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
  override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter = HclSyntaxHighlighter()
}

/** Colors block types and attribute names, which the lexer cannot tell from other identifiers. */
class HclAnnotator : Annotator {
  override fun annotate(element: PsiElement, holder: AnnotationHolder) {
    val (name, key) = when (element) {
      is HclBlock -> element.firstChild to HclColors.BLOCK_TYPE
      is HclAttribute -> element.nameIdentifier to HclColors.ATTRIBUTE
      else -> return
    }
    if (name == null || name.node.elementType !in T.WORDS) return
    holder.newSilentAnnotation(HighlightSeverity.INFORMATION).range(name).textAttributes(key).create()
  }
}

class HclCommenter : Commenter {
  // terraform fmt keeps both, but # is the idiomatic single-line comment.
  override fun getLineCommentPrefix(): String = "#"
  override fun getBlockCommentPrefix(): String = "/*"
  override fun getBlockCommentSuffix(): String = "*/"
  override fun getCommentedBlockCommentPrefix(): String? = null
  override fun getCommentedBlockCommentSuffix(): String? = null
}

class HclBraceMatcher : PairedBraceMatcher {
  override fun getPairs(): Array<BracePair> = arrayOf(
    BracePair(T.LBRACE, T.RBRACE, true),
    BracePair(T.LBRACKET, T.RBRACKET, false),
    BracePair(T.LPAREN, T.RPAREN, false),
  )

  override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, contextType: IElementType?): Boolean = true
  override fun getCodeConstructStart(file: PsiFile?, openingBraceOffset: Int): Int = openingBraceOffset
}

class HclFindUsagesProvider : FindUsagesProvider {
  override fun getWordsScanner(): WordsScanner = DefaultWordsScanner(HclLexer(), T.WORDS, T.COMMENTS, T.STRINGS)

  override fun canFindUsagesFor(element: PsiElement): Boolean = when (element) {
    is HclBlock -> element.type in DECLARING_BLOCKS
    is HclAttribute -> (element.parent?.parent as? HclBlock)?.type == "locals"
    else -> false
  }

  override fun getHelpId(element: PsiElement): String? = null

  override fun getType(element: PsiElement): String = when (element) {
    is HclBlock -> element.type
    else -> TerraformBundle.message("terraform.kind.local")
  }

  override fun getDescriptiveName(element: PsiElement): String = when (element) {
    is HclBlock -> element.labels.joinToString(".") { it.value }
    is HclAttribute -> "local.${element.name}"
    else -> ""
  }

  override fun getNodeText(element: PsiElement, useFullName: Boolean): String = getDescriptiveName(element)

  private companion object {
    val DECLARING_BLOCKS = setOf("variable", "output", "module", "resource", "data", "ephemeral")
  }
}
