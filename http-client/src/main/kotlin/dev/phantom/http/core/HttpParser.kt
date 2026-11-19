// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.http.core

import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet

/**
 * Structural parser: the lexer already decided what each line is, so the parser only groups tokens into blocks
 * (separated by `###`), request lines, headers, bodies and handlers. It never reports errors, so a half-written
 * request does not disturb the others.
 */
class HttpParser : PsiParser {
  override fun parse(root: IElementType, builder: PsiBuilder): ASTNode {
    val file = builder.mark()
    while (!builder.eof()) block(builder)
    file.done(root)
    return builder.treeBuilt
  }

  private fun block(b: PsiBuilder) {
    val block = b.mark()
    if (b.tokenType == HttpTokens.SEPARATOR) {
      b.advanceLexer()
      if (b.tokenType == HttpTokens.REQUEST_NAME) b.advanceLexer()
    }
    var requestSeen = false
    while (!b.eof() && b.tokenType != HttpTokens.SEPARATOR) {
      when (b.tokenType) {
        HttpTokens.LBRACES -> if (requestSeen) body(b) else { requestLine(b); requestSeen = true }
        HttpTokens.DIRECTIVE -> single(b, HttpElements.DIRECTIVE)
        HttpTokens.FILE_VARIABLE_NAME -> fileVariable(b)
        HttpTokens.METHOD, HttpTokens.URL_TEXT -> { requestLine(b); requestSeen = true }
        HttpTokens.HEADER_NAME -> header(b)
        HttpTokens.BODY_TEXT, HttpTokens.INPUT_FILE -> body(b)
        HttpTokens.HANDLER_START, HttpTokens.HANDLER_FILE -> handler(b)
        HttpTokens.IMPORT_KEYWORD -> line(b, HttpElements.IMPORT, HttpTokens.IMPORT_PATH)
        HttpTokens.RUN_KEYWORD -> line(b, HttpElements.RUN, HttpTokens.RUN_REQUEST_NAME, HttpTokens.RUN_PATH, HttpTokens.RUN_VARIABLES)
        else -> b.advanceLexer()
      }
    }
    block.done(HttpElements.REQUEST_BLOCK)
  }

  private fun single(b: PsiBuilder, type: IElementType) {
    val m = b.mark()
    b.advanceLexer()
    m.done(type)
  }

  /** A keyword and the tokens of the same line that may follow it. */
  private fun line(b: PsiBuilder, type: IElementType, vararg parts: IElementType) {
    val m = b.mark()
    b.advanceLexer()
    while (b.tokenType in parts) b.advanceLexer()
    m.done(type)
  }

  private fun fileVariable(b: PsiBuilder) {
    val m = b.mark()
    b.advanceLexer()
    if (b.tokenType == HttpTokens.EQ) b.advanceLexer()
    while (b.tokenType == HttpTokens.VALUE_TEXT || b.tokenType == HttpTokens.LBRACES) value(b)
    m.done(HttpElements.FILE_VARIABLE)
  }

  private fun requestLine(b: PsiBuilder) {
    val line = b.mark()
    if (b.tokenType == HttpTokens.METHOD) b.advanceLexer()
    val target = b.mark()
    while (b.tokenType == HttpTokens.URL_TEXT || b.tokenType == HttpTokens.LBRACES) value(b)
    target.done(HttpElements.REQUEST_TARGET)
    if (b.tokenType == HttpTokens.HTTP_VERSION) b.advanceLexer()
    line.done(HttpElements.REQUEST_LINE)
  }

  private fun header(b: PsiBuilder) {
    val m = b.mark()
    b.advanceLexer()
    if (b.tokenType == HttpTokens.COLON) {
      b.advanceLexer()
      while (b.tokenType == HttpTokens.HEADER_VALUE || b.tokenType == HttpTokens.LBRACES) value(b)
    }
    m.done(HttpElements.HEADER)
  }

  private fun body(b: PsiBuilder) {
    val m = b.mark()
    while (b.tokenType == HttpTokens.BODY_TEXT || b.tokenType == HttpTokens.INPUT_FILE || b.tokenType == HttpTokens.LBRACES) {
      value(b)
    }
    m.done(HttpElements.BODY)
  }

  private fun handler(b: PsiBuilder) {
    val m = b.mark()
    if (b.tokenType == HttpTokens.HANDLER_FILE) {
      b.advanceLexer()
    }
    else {
      b.advanceLexer()
      while (b.tokenType == HttpTokens.SCRIPT) b.advanceLexer()
      if (b.tokenType == HttpTokens.HANDLER_END) b.advanceLexer()
    }
    m.done(HttpElements.HANDLER)
  }

  /** One text token, or a `{{name}}` reference wrapped in [HttpElements.VARIABLE_REF]. */
  private fun value(b: PsiBuilder) {
    if (b.tokenType != HttpTokens.LBRACES) {
      b.advanceLexer()
      return
    }
    val ref = b.mark()
    b.advanceLexer()
    if (b.tokenType == HttpTokens.VARIABLE_NAME) b.advanceLexer()
    if (b.tokenType == HttpTokens.RBRACES) b.advanceLexer()
    ref.done(HttpElements.VARIABLE_REF)
  }
}

class HttpParserDefinition : ParserDefinition {
  override fun createLexer(project: Project?): Lexer = HttpLexer()
  override fun createParser(project: Project?): PsiParser = HttpParser()
  override fun getFileNodeType(): IFileElementType = HttpElements.FILE
  override fun getCommentTokens(): TokenSet = HttpTokens.COMMENTS
  override fun getStringLiteralElements(): TokenSet = TokenSet.EMPTY
  override fun createFile(viewProvider: FileViewProvider): PsiFile = HttpRequestFile(viewProvider)

  override fun createElement(node: ASTNode): PsiElement = when (node.elementType) {
    HttpElements.REQUEST_BLOCK -> HttpRequestBlock(node)
    HttpElements.REQUEST_LINE -> HttpRequestLine(node)
    HttpElements.REQUEST_TARGET -> HttpRequestTarget(node)
    HttpElements.HEADER -> HttpHeader(node)
    HttpElements.BODY -> HttpBody(node)
    HttpElements.HANDLER -> HttpHandler(node)
    HttpElements.DIRECTIVE -> HttpDirective(node)
    HttpElements.FILE_VARIABLE -> HttpFileVariable(node)
    HttpElements.VARIABLE_REF -> HttpVariableRef(node)
    HttpElements.IMPORT -> HttpImport(node)
    HttpElements.RUN -> HttpRun(node)
    else -> HttpElement(node)
  }
}
