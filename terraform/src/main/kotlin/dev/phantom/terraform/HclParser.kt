// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.terraform

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import dev.phantom.terraform.HclElements as E
import dev.phantom.terraform.HclTokens as T

class HclParserDefinition : ParserDefinition {
  override fun createLexer(project: Project?): Lexer = HclLexer()
  override fun createParser(project: Project?): PsiParser = HclParser()
  override fun getFileNodeType(): IFileElementType = E.FILE
  override fun getCommentTokens(): TokenSet = T.COMMENTS
  override fun getStringLiteralElements(): TokenSet = T.STRINGS
  override fun getWhitespaceTokens(): TokenSet = T.WHITE_SPACE
  override fun createFile(viewProvider: FileViewProvider): PsiFile = HclFile(viewProvider)

  override fun createElement(node: ASTNode): PsiElement = when (node.elementType) {
    E.BLOCK -> HclBlock(node)
    E.BODY -> HclBody(node)
    E.LABEL -> HclLabel(node)
    E.ATTRIBUTE -> HclAttribute(node)
    E.VARIABLE -> HclVariable(node)
    E.GET_ATTR -> HclGetAttr(node)
    E.FOR_VAR -> HclForVar(node)
    else -> ASTWrapperPsiElement(node)
  }
}

/**
 * A recursive descent parser for HCL native syntax: bodies of attributes and blocks, and expressions with HCL's operator
 * precedence. A line break ends an attribute and an object element, but not inside parentheses, brackets or a for
 * expression, as in HCL. A line it cannot read is reported and skipped, so the rest of the file still parses.
 */
class HclParser : PsiParser {
  private var newlineEnds = true

  override fun parse(root: IElementType, builder: PsiBuilder): ASTNode {
    val file = builder.mark()
    newlineEnds = true
    body(builder, null)
    file.done(root)
    return builder.treeBuilt
  }

  private fun body(b: PsiBuilder, closing: IElementType?) {
    while (!b.eof() && b.tokenType != closing) {
      val before = b.currentOffset
      when {
        isWord(b.tokenType) && b.lookAhead(1) == T.EQ -> attribute(b)
        isWord(b.tokenType) -> block(b)
        else -> restOfLine(b, TerraformBundle.message("parser.expected.item"))
      }
      if (b.currentOffset == before) b.advanceLexer()
    }
  }

  private fun attribute(b: PsiBuilder) {
    val m = b.mark()
    b.advanceLexer()
    b.advanceLexer()
    newlineEnds(b, true) { requireExpression(it) }
    endOfLine(b)
    m.done(E.ATTRIBUTE)
  }

  private fun block(b: PsiBuilder) {
    val m = b.mark()
    b.advanceLexer()
    while (!newlineBefore(b) && (b.tokenType == T.QUOTE_OPEN || isWord(b.tokenType))) {
      val label = b.mark()
      if (b.tokenType == T.QUOTE_OPEN) template(b) else b.advanceLexer()
      label.done(E.LABEL)
    }
    if (expect(b, T.LBRACE, "{")) {
      val body = b.mark()
      body(b, T.RBRACE)
      body.done(E.BODY)
      expect(b, T.RBRACE, "}")
      endOfLine(b)
    } else if (!b.eof() && b.tokenType != T.RBRACE && !newlineBefore(b)) {
      restOfLine(b, null)
    }
    m.done(E.BLOCK)
  }

  /** Reports what is left on the line, unless the line has ended or the enclosing block closes. */
  private fun endOfLine(b: PsiBuilder) {
    if (!b.eof() && b.tokenType != T.RBRACE && !newlineBefore(b)) restOfLine(b, TerraformBundle.message("parser.expected.newline"))
  }

  private fun restOfLine(b: PsiBuilder, error: String?) {
    val m = b.mark()
    do {
      b.advanceLexer()
    } while (!b.eof() && !newlineBefore(b) && b.tokenType != T.RBRACE)
    if (error == null) m.drop() else m.error(error)
  }

  private fun requireExpression(b: PsiBuilder) {
    if (!expression(b)) b.error(TerraformBundle.message("parser.expected.expression"))
  }

  private fun expression(b: PsiBuilder): Boolean {
    if (stop(b)) return false
    val m = b.mark()
    if (!binary(b, 0)) {
      m.drop()
      return false
    }
    if (b.tokenType == T.QUESTION && !stop(b)) {
      b.advanceLexer()
      requireExpression(b)
      expect(b, T.COLON, ":")
      requireExpression(b)
      m.done(E.CONDITIONAL)
    } else {
      m.drop()
    }
    return true
  }

  private fun binary(b: PsiBuilder, minPrecedence: Int): Boolean {
    var m = b.mark()
    if (!unary(b)) {
      m.drop()
      return false
    }
    while (true) {
      val precedence = if (b.tokenType == T.OPERATOR && !stop(b)) PRECEDENCE[b.tokenText] ?: -1 else -1
      if (precedence < minPrecedence) break
      b.advanceLexer()
      if (!binary(b, precedence + 1)) b.error(TerraformBundle.message("parser.expected.expression"))
      m.done(E.BINARY)
      m = m.precede()
    }
    m.drop()
    return true
  }

  private fun unary(b: PsiBuilder): Boolean {
    if (b.tokenType == T.OPERATOR && (b.tokenText == "!" || b.tokenText == "-")) {
      val m = b.mark()
      b.advanceLexer()
      if (!unary(b)) b.error(TerraformBundle.message("parser.expected.expression"))
      m.done(E.UNARY)
      return true
    }
    return postfix(b)
  }

  private fun postfix(b: PsiBuilder): Boolean {
    var m = b.mark()
    if (!primary(b)) {
      m.drop()
      return false
    }
    while (!stop(b)) {
      val type = postfixOperator(b) ?: break
      m.done(type)
      m = m.precede()
    }
    m.drop()
    return true
  }

  /** Consumes `.name`, `.0`, `.*`, `[*]` or `[index]` and returns the element it makes, or null when none follows. */
  private fun postfixOperator(b: PsiBuilder): IElementType? = when {
        b.tokenType == T.DOT && isWord(b.lookAhead(1)) -> E.GET_ATTR.also { b.advanceLexer(); b.advanceLexer() }
        b.tokenType == T.DOT && b.lookAhead(1) == T.NUMBER -> E.INDEX.also { b.advanceLexer(); b.advanceLexer() }
        b.tokenType == T.DOT && b.lookAhead(1) == T.OPERATOR -> E.SPLAT.also { b.advanceLexer(); b.advanceLexer() }
        b.tokenType == T.LBRACKET && b.lookAhead(1) == T.OPERATOR && b.lookAhead(2) == T.RBRACKET ->
          E.SPLAT.also { repeat(3) { b.advanceLexer() } }
        b.tokenType == T.LBRACKET -> E.INDEX.also {
          b.advanceLexer()
          newlineEnds(b, false) { requireExpression(it) }
          expect(b, T.RBRACKET, "]")
        }
        else -> null
      }

  private fun primary(b: PsiBuilder): Boolean {
    val type = b.tokenType
    when {
      type in LITERALS -> single(b, E.LITERAL)
      type == T.QUOTE_OPEN -> template(b)
      (type == T.LBRACKET || type == T.LBRACE) && b.lookAhead(1) == T.FOR -> forExpression(b, if (type == T.LBRACE) T.RBRACE else T.RBRACKET)
      type == T.LBRACKET -> tuple(b)
      type == T.LBRACE -> objectExpression(b)
      type == T.LPAREN -> {
        val m = b.mark()
        b.advanceLexer()
        newlineEnds(b, false) { requireExpression(it) }
        expect(b, T.RPAREN, ")")
        m.done(E.PARENS)
      }
      isWord(type) && (b.lookAhead(1) == T.LPAREN || b.lookAhead(1) == T.COLON && b.lookAhead(2) == T.COLON) -> call(b)
      isWord(type) -> single(b, E.VARIABLE)
      else -> return false
    }
    return true
  }

  private fun single(b: PsiBuilder, type: IElementType) {
    val m = b.mark()
    b.advanceLexer()
    m.done(type)
  }

  /** A function call; provider functions are written `provider::name::function(...)`. */
  private fun call(b: PsiBuilder) {
    val m = b.mark()
    b.advanceLexer()
    while (b.tokenType == T.COLON && b.lookAhead(1) == T.COLON) {
      b.advanceLexer()
      b.advanceLexer()
      if (isWord(b.tokenType)) b.advanceLexer() else b.error(TerraformBundle.message("parser.expected.name"))
    }
    if (expect(b, T.LPAREN, "(")) {
      newlineEnds(b, false) {
        while (!it.eof() && it.tokenType != T.RPAREN) {
          requireExpression(it)
          if (it.tokenType == T.ELLIPSIS) it.advanceLexer()
          if (it.tokenType == T.COMMA) it.advanceLexer() else break
        }
      }
      expect(b, T.RPAREN, ")")
    }
    m.done(E.FUNCTION_CALL)
  }

  private fun tuple(b: PsiBuilder) {
    val m = b.mark()
    b.advanceLexer()
    newlineEnds(b, false) {
      while (!it.eof() && it.tokenType != T.RBRACKET) {
        requireExpression(it)
        if (it.tokenType == T.COMMA) it.advanceLexer() else break
      }
    }
    expect(b, T.RBRACKET, "]")
    m.done(E.TUPLE)
  }

  private fun objectExpression(b: PsiBuilder) {
    val m = b.mark()
    b.advanceLexer()
    newlineEnds(b, true) {
      while (!it.eof() && it.tokenType != T.RBRACE) {
        val before = it.currentOffset
        objectElement(it)
        if (it.tokenType == T.COMMA) it.advanceLexer()
        if (it.currentOffset == before) restOfLine(it, TerraformBundle.message("parser.expected.expression"))
      }
    }
    expect(b, T.RBRACE, "}")
    m.done(E.OBJECT)
  }

  /** `key = value` or `key: value`; a bare name as key is the key itself, not a variable. */
  private fun objectElement(b: PsiBuilder) {
    val m = b.mark()
    if (isWord(b.tokenType) && (b.lookAhead(1) == T.EQ || b.lookAhead(1) == T.COLON)) {
      single(b, E.OBJECT_KEY)
    } else if (!newlineEnds(b, false) { expression(it) }) {
      m.drop()
      return
    }
    if (b.tokenType == T.EQ || b.tokenType == T.COLON) b.advanceLexer() else b.error(TerraformBundle.message("parser.expected", "="))
    requireExpression(b)
    m.done(E.OBJECT_ELEMENT)
  }

  /** `[for k, v in coll : value if cond]` or `{for k, v in coll : key => value... if cond}`. */
  private fun forExpression(b: PsiBuilder, closing: IElementType) {
    val m = b.mark()
    b.advanceLexer()
    newlineEnds(b, false) {
      it.advanceLexer()
      forVariables(it)
      expect(it, T.IN, "in")
      requireExpression(it)
      expect(it, T.COLON, ":")
      requireExpression(it)
      if (closing == T.RBRACE && expect(it, T.ARROW, "=>")) {
        requireExpression(it)
        if (it.tokenType == T.ELLIPSIS) it.advanceLexer()
      }
      if (it.tokenType == T.IF) {
        it.advanceLexer()
        requireExpression(it)
      }
    }
    expect(b, closing, if (closing == T.RBRACE) "}" else "]")
    m.done(E.FOR_EXPR)
  }

  private fun forVariables(b: PsiBuilder) {
    do {
      if (isWord(b.tokenType) && b.tokenType != T.IN) single(b, E.FOR_VAR) else b.error(TerraformBundle.message("parser.expected.name"))
    } while (b.tokenType == T.COMMA && run { b.advanceLexer(); true })
  }

  /** A quoted string with its `${...}` interpolations and `%{...}` directives. */
  private fun template(b: PsiBuilder) {
    val m = b.mark()
    b.advanceLexer()
    newlineEnds(b, false) {
      loop@ while (!it.eof()) {
        when (it.tokenType) {
          T.STRING_TEXT -> it.advanceLexer()
          T.INTERPOLATION_START -> templatePart(it, E.INTERPOLATION) { p -> requireExpression(p) }
          T.DIRECTIVE_START -> templatePart(it, E.DIRECTIVE) { p -> directive(p) }
          T.QUOTE_CLOSE -> {
            it.advanceLexer()
            break@loop
          }
          else -> {
            it.error(TerraformBundle.message("parser.expected", "\""))
            break@loop
          }
        }
      }
    }
    m.done(E.TEMPLATE)
  }

  private fun templatePart(b: PsiBuilder, type: IElementType, content: (PsiBuilder) -> Unit) {
    val m = b.mark()
    b.advanceLexer()
    if (b.tokenType == T.STRIP) b.advanceLexer()
    content(b)
    if (b.tokenType == T.STRIP) b.advanceLexer()
    if (b.tokenType == T.TEMPLATE_END) {
      b.advanceLexer()
    } else {
      b.error(TerraformBundle.message("parser.expected", "}"))
      while (!b.eof() && b.tokenType != T.TEMPLATE_END && b.tokenType != T.QUOTE_CLOSE) b.advanceLexer()
      if (b.tokenType == T.TEMPLATE_END) b.advanceLexer()
    }
    m.done(type)
  }

  /** `if cond`, `for k, v in coll`, `else`, `endif` or `endfor`. */
  private fun directive(b: PsiBuilder) {
    when {
      b.tokenType == T.IF -> {
        b.advanceLexer()
        requireExpression(b)
      }
      b.tokenType == T.FOR -> {
        b.advanceLexer()
        forVariables(b)
        expect(b, T.IN, "in")
        requireExpression(b)
      }
      isWord(b.tokenType) -> b.advanceLexer()
      else -> b.error(TerraformBundle.message("parser.expected.name"))
    }
  }

  private inline fun <R> newlineEnds(b: PsiBuilder, value: Boolean, parse: (PsiBuilder) -> R): R {
    val saved = newlineEnds
    newlineEnds = value
    try {
      return parse(b)
    } finally {
      newlineEnds = saved
    }
  }

  /** An expression ends at a line break in a body or an object, where the next line starts the next item. */
  private fun stop(b: PsiBuilder): Boolean = b.eof() || newlineEnds && newlineBefore(b)

  private fun newlineBefore(b: PsiBuilder): Boolean {
    var i = -1
    while (true) {
      val type = b.rawLookup(i) ?: return false
      if (type != TokenType.WHITE_SPACE && type !in T.COMMENTS) return false
      if (b.originalText.subSequence(b.rawTokenTypeStart(i), b.rawTokenTypeStart(i + 1)).contains('\n')) return true
      i--
    }
  }

  private fun expect(b: PsiBuilder, type: IElementType, text: String): Boolean {
    if (b.tokenType == type) {
      b.advanceLexer()
      return true
    }
    b.error(TerraformBundle.message("parser.expected", text))
    return false
  }

  private fun isWord(type: IElementType?) = type != null && type in T.WORDS

  private companion object {
    val LITERALS = TokenSet.create(T.NUMBER, T.LITERAL_KEYWORD, T.HEREDOC)
    val PRECEDENCE = mapOf(
      "||" to 1, "&&" to 2, "==" to 3, "!=" to 3, "<" to 4, ">" to 4, "<=" to 4, ">=" to 4,
      "+" to 5, "-" to 5, "*" to 6, "/" to 6, "%" to 6,
    )
  }
}
