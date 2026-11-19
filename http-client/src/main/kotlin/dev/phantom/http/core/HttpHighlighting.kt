// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.http.core

import com.intellij.lang.ASTNode
import com.intellij.lang.Commenter
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as Colors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

object HttpColors {
  val METHOD: TextAttributesKey = TextAttributesKey.createTextAttributesKey("PHANTOM_HTTP_METHOD", Colors.KEYWORD)
  val URL: TextAttributesKey = TextAttributesKey.createTextAttributesKey("PHANTOM_HTTP_URL", Colors.IDENTIFIER)
  val VERSION: TextAttributesKey = TextAttributesKey.createTextAttributesKey("PHANTOM_HTTP_VERSION", Colors.NUMBER)
  val HEADER_NAME: TextAttributesKey = TextAttributesKey.createTextAttributesKey("PHANTOM_HTTP_HEADER_NAME", Colors.INSTANCE_FIELD)
  val HEADER_VALUE: TextAttributesKey = TextAttributesKey.createTextAttributesKey("PHANTOM_HTTP_HEADER_VALUE", Colors.STRING)
  val SEPARATOR: TextAttributesKey = TextAttributesKey.createTextAttributesKey("PHANTOM_HTTP_SEPARATOR", Colors.DOC_COMMENT)
  val COMMENT: TextAttributesKey = TextAttributesKey.createTextAttributesKey("PHANTOM_HTTP_COMMENT", Colors.LINE_COMMENT)
  val DIRECTIVE: TextAttributesKey = TextAttributesKey.createTextAttributesKey("PHANTOM_HTTP_DIRECTIVE", Colors.METADATA)
  val VARIABLE: TextAttributesKey = TextAttributesKey.createTextAttributesKey("PHANTOM_HTTP_VARIABLE", Colors.GLOBAL_VARIABLE)
  val BRACES: TextAttributesKey = TextAttributesKey.createTextAttributesKey("PHANTOM_HTTP_BRACES", Colors.BRACES)
  val FILE_VARIABLE: TextAttributesKey = TextAttributesKey.createTextAttributesKey("PHANTOM_HTTP_FILE_VARIABLE", Colors.GLOBAL_VARIABLE)
  val HANDLER: TextAttributesKey = TextAttributesKey.createTextAttributesKey("PHANTOM_HTTP_HANDLER", Colors.TEMPLATE_LANGUAGE_COLOR)
  val FILE_REF: TextAttributesKey = TextAttributesKey.createTextAttributesKey("PHANTOM_HTTP_FILE_REF", Colors.STRING)
}

class HttpSyntaxHighlighter : SyntaxHighlighterBase() {
  override fun getHighlightingLexer(): Lexer = HttpLexer()

  override fun getTokenHighlights(tokenType: IElementType?): Array<TextAttributesKey> = pack(
    when (tokenType) {
      HttpTokens.METHOD -> HttpColors.METHOD
      HttpTokens.URL_TEXT -> HttpColors.URL
      HttpTokens.HTTP_VERSION -> HttpColors.VERSION
      HttpTokens.HEADER_NAME -> HttpColors.HEADER_NAME
      HttpTokens.HEADER_VALUE -> HttpColors.HEADER_VALUE
      HttpTokens.SEPARATOR, HttpTokens.REQUEST_NAME -> HttpColors.SEPARATOR
      HttpTokens.COMMENT -> HttpColors.COMMENT
      HttpTokens.DIRECTIVE -> HttpColors.DIRECTIVE
      HttpTokens.VARIABLE_NAME -> HttpColors.VARIABLE
      HttpTokens.LBRACES, HttpTokens.RBRACES -> HttpColors.BRACES
      HttpTokens.FILE_VARIABLE_NAME -> HttpColors.FILE_VARIABLE
      HttpTokens.HANDLER_START, HttpTokens.HANDLER_END, HttpTokens.SCRIPT, HttpTokens.HANDLER_FILE -> HttpColors.HANDLER
      HttpTokens.INPUT_FILE, HttpTokens.IMPORT_PATH, HttpTokens.RUN_PATH -> HttpColors.FILE_REF
      HttpTokens.IMPORT_KEYWORD, HttpTokens.RUN_KEYWORD -> HttpColors.METHOD
      HttpTokens.RUN_REQUEST_NAME -> HttpColors.SEPARATOR
      HttpTokens.RUN_VARIABLES -> HttpColors.FILE_VARIABLE
      TokenType.BAD_CHARACTER -> Colors.INVALID_STRING_ESCAPE
      else -> null
    }
  )
}

class HttpSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
  override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter = HttpSyntaxHighlighter()
}

class HttpCommenter : Commenter {
  override fun getLineCommentPrefix(): String = "# "
  override fun getBlockCommentPrefix(): String? = null
  override fun getBlockCommentSuffix(): String? = null
  override fun getCommentedBlockCommentPrefix(): String? = null
  override fun getCommentedBlockCommentSuffix(): String? = null
}

/** Each request folds to its first line. */
class HttpFoldingBuilder : FoldingBuilderEx(), DumbAware {
  override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
    val file = root as? HttpRequestFile ?: return FoldingDescriptor.EMPTY_ARRAY
    return file.blocks.mapNotNull { block ->
      val line = block.requestLine ?: return@mapNotNull null
      val start = line.textRange.endOffset
      val end = block.textRange.endOffset
      if (end <= start || document.getLineNumber(start) == document.getLineNumber(end)) null
      else FoldingDescriptor(block.node, TextRange(start, end))
    }.toTypedArray()
  }

  override fun getPlaceholderText(node: ASTNode): String = " ..."
  override fun isCollapsedByDefault(node: ASTNode): Boolean = false
}

/** Unknown variables are warnings (the value might come from somewhere this plugin cannot see). */
class HttpAnnotator : Annotator {
  override fun annotate(element: PsiElement, holder: AnnotationHolder) {
    when (element) {
      is HttpVariableRef -> {
        val name = element.variableName
        if (name.isNotEmpty() && !HttpVariables.isKnown(element.containingFile, name)) {
          holder.newAnnotation(HighlightSeverity.WARNING, PhantomHttpBundle.message("http.variable.unresolved", name)).range(element).create()
        }
      }
      is HttpHandler -> if ((element.parent as? HttpRequestBlock)?.handlers?.contains(element) != true) {
        holder.newAnnotation(HighlightSeverity.WARNING, PhantomHttpBundle.message("http.handler.no.request")).range(element).create()
      }
      is HttpDirective -> if (element.directiveName !in KNOWN_DIRECTIVES) {
        holder.newAnnotation(HighlightSeverity.WEAK_WARNING, PhantomHttpBundle.message("http.directive.unknown", element.directiveName))
          .range(element).create()
      }
    }
  }

  companion object {
    val KNOWN_DIRECTIVES: Set<String> = setOf("name", "no-redirect", "no-cookie-jar", "timeout", "connection-timeout", "no-log")
  }
}
