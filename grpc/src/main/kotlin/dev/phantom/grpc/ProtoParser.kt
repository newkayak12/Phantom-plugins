// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.grpc

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
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import dev.phantom.grpc.ProtoElements as E
import dev.phantom.grpc.ProtoTokens as T

class ProtoParserDefinition : ParserDefinition {
  override fun createLexer(project: Project?): Lexer = ProtoLexer()
  override fun createParser(project: Project?): PsiParser = ProtoParser()
  override fun getFileNodeType(): IFileElementType = E.FILE
  override fun getCommentTokens(): TokenSet = T.COMMENTS
  override fun getStringLiteralElements(): TokenSet = T.STRINGS
  override fun getWhitespaceTokens(): TokenSet = T.WHITE_SPACE
  override fun createFile(viewProvider: FileViewProvider): PsiFile = ProtoFile(viewProvider)

  override fun createElement(node: ASTNode): PsiElement = when (node.elementType) {
    E.MESSAGE, E.ENUM -> ProtoTypeDefinition(node)
    E.SERVICE, E.RPC, E.FIELD, E.MAP_FIELD, E.ONEOF, E.ENUM_VALUE -> ProtoNamedElement(node)
    E.TYPE_REF -> ProtoTypeRef(node)
    E.IMPORT -> ProtoImport(node)
    E.PACKAGE -> ProtoPackage(node)
    else -> ASTWrapperPsiElement(node)
  }
}

/**
 * A recursive descent parser for the declarations the IDE needs (messages, enums, services, rpcs, fields, imports).
 * Option values and reserved ranges are kept as flat token runs; a statement it does not know is reported and skipped
 * up to its `;` or block so the rest of the file still parses.
 */
class ProtoParser : PsiParser {
  override fun parse(root: IElementType, builder: PsiBuilder): ASTNode {
    val file = builder.mark()
    while (!builder.eof()) {
      topLevel(builder)
    }
    file.done(root)
    return builder.treeBuilt
  }

  private fun topLevel(b: PsiBuilder) {
    when (keyword(b)) {
      "syntax", "edition" -> untilSemicolon(b, E.SYNTAX)
      "package" -> {
        val m = b.mark()
        b.advanceLexer()
        fullName(b)
        semicolon(b)
        m.done(E.PACKAGE)
      }
      "import" -> import(b)
      "option" -> untilSemicolon(b, E.OPTION)
      "message" -> message(b)
      "enum" -> enum(b)
      "service" -> service(b)
      "extend" -> extend(b)
      else -> if (b.tokenType == T.SEMI) b.advanceLexer() else unexpected(b)
    }
  }

  private fun import(b: PsiBuilder) {
    val m = b.mark()
    b.advanceLexer()
    if (keyword(b) == "weak" || keyword(b) == "public") b.advanceLexer()
    if (b.tokenType == T.STRING) b.advanceLexer() else b.error(GrpcBundle.message("parser.expected.import.path"))
    semicolon(b)
    m.done(E.IMPORT)
  }

  private fun message(b: PsiBuilder) {
    val m = b.mark()
    b.advanceLexer()
    name(b)
    block(b) { messageItem(it) }
    m.done(E.MESSAGE)
  }

  private fun messageItem(b: PsiBuilder) {
    when (keyword(b)) {
      "message" -> message(b)
      "enum" -> enum(b)
      "extend" -> extend(b)
      "option" -> untilSemicolon(b, E.OPTION)
      "reserved", "extensions" -> untilSemicolon(b, E.RESERVED)
      "oneof" -> oneof(b)
      "map" -> if (b.lookAhead(1) == T.LT) mapField(b) else field(b)
      else -> if (b.tokenType == T.SEMI) b.advanceLexer() else field(b)
    }
  }

  private fun field(b: PsiBuilder) {
    if (!isWord(b.tokenType) && b.tokenType != T.DOT) {
      unexpected(b)
      return
    }
    val m = b.mark()
    if (keyword(b) in LABELS && (isWord(b.lookAhead(1)) || b.lookAhead(1) == T.DOT)) b.advanceLexer()
    type(b)
    name(b)
    fieldNumberAndOptions(b)
    m.done(E.FIELD)
  }

  private fun mapField(b: PsiBuilder) {
    val m = b.mark()
    b.advanceLexer()
    b.advanceLexer()
    type(b)
    expect(b, T.COMMA, ",")
    type(b)
    expect(b, T.GT, ">")
    name(b)
    fieldNumberAndOptions(b)
    m.done(E.MAP_FIELD)
  }

  private fun fieldNumberAndOptions(b: PsiBuilder) {
    if (expect(b, T.EQ, "=")) {
      if (b.tokenType == T.INT) b.advanceLexer() else b.error(GrpcBundle.message("parser.expected.field.number"))
    }
    if (b.tokenType == T.LBRACKET) balanced(b, T.LBRACKET, T.RBRACKET)
    semicolon(b)
  }

  private fun oneof(b: PsiBuilder) {
    val m = b.mark()
    b.advanceLexer()
    name(b)
    block(b) {
      when {
        keyword(it) == "option" -> untilSemicolon(it, E.OPTION)
        it.tokenType == T.SEMI -> it.advanceLexer()
        else -> field(it)
      }
    }
    m.done(E.ONEOF)
  }

  private fun enum(b: PsiBuilder) {
    val m = b.mark()
    b.advanceLexer()
    name(b)
    block(b) {
      when {
        keyword(it) == "option" && it.lookAhead(1) != T.EQ -> untilSemicolon(it, E.OPTION)
        keyword(it) == "reserved" && it.lookAhead(1) != T.EQ -> untilSemicolon(it, E.RESERVED)
        it.tokenType == T.SEMI -> it.advanceLexer()
        isWord(it.tokenType) -> {
          val v = it.mark()
          name(it)
          if (expect(it, T.EQ, "=")) {
            if (it.tokenText == "-") it.advanceLexer()
            if (it.tokenType == T.INT) it.advanceLexer() else it.error(GrpcBundle.message("parser.expected.field.number"))
          }
          if (it.tokenType == T.LBRACKET) balanced(it, T.LBRACKET, T.RBRACKET)
          semicolon(it)
          v.done(E.ENUM_VALUE)
        }
        else -> unexpected(it)
      }
    }
    m.done(E.ENUM)
  }

  private fun service(b: PsiBuilder) {
    val m = b.mark()
    b.advanceLexer()
    name(b)
    block(b) {
      when {
        keyword(it) == "option" -> untilSemicolon(it, E.OPTION)
        keyword(it) == "rpc" -> rpc(it)
        it.tokenType == T.SEMI -> it.advanceLexer()
        else -> unexpected(it)
      }
    }
    m.done(E.SERVICE)
  }

  private fun rpc(b: PsiBuilder) {
    val m = b.mark()
    b.advanceLexer()
    name(b)
    rpcType(b)
    if (keyword(b) == "returns") b.advanceLexer() else b.error(GrpcBundle.message("parser.expected", "returns"))
    rpcType(b)
    if (b.tokenType == T.LBRACE) {
      block(b) {
        if (keyword(it) == "option") untilSemicolon(it, E.OPTION) else if (it.tokenType == T.SEMI) it.advanceLexer() else unexpected(it)
      }
    } else {
      semicolon(b)
    }
    m.done(E.RPC)
  }

  private fun rpcType(b: PsiBuilder) {
    if (!expect(b, T.LPAREN, "(")) return
    if (keyword(b) == "stream" && (isWord(b.lookAhead(1)) || b.lookAhead(1) == T.DOT)) b.advanceLexer()
    type(b)
    expect(b, T.RPAREN, ")")
  }

  private fun extend(b: PsiBuilder) {
    val m = b.mark()
    b.advanceLexer()
    type(b)
    block(b) {
      if (it.tokenType == T.SEMI) it.advanceLexer() else field(it)
    }
    m.done(E.EXTEND)
  }

  /** A scalar type stays a keyword token; anything else becomes a TYPE_REF that resolves to a message or enum. */
  private fun type(b: PsiBuilder) {
    if (b.tokenType == T.KEYWORD && b.tokenText in T.SCALAR_TYPES && b.lookAhead(1) != T.DOT) {
      b.advanceLexer()
      return
    }
    if (!isWord(b.tokenType) && b.tokenType != T.DOT) {
      b.error(GrpcBundle.message("parser.expected.type"))
      return
    }
    val m = b.mark()
    if (b.tokenType == T.DOT) b.advanceLexer()
    fullName(b)
    m.done(E.TYPE_REF)
  }

  private fun fullName(b: PsiBuilder) {
    word(b)
    while (b.tokenType == T.DOT) {
      b.advanceLexer()
      word(b)
    }
  }

  private fun word(b: PsiBuilder) {
    if (isWord(b.tokenType)) b.advanceLexer() else b.error(GrpcBundle.message("parser.expected.name"))
  }

  private fun name(b: PsiBuilder) {
    if (isWord(b.tokenType)) {
      b.remapCurrentToken(T.NAME)
      b.advanceLexer()
    } else {
      b.error(GrpcBundle.message("parser.expected.name"))
    }
  }

  private fun block(b: PsiBuilder, item: (PsiBuilder) -> Unit) {
    if (!expect(b, T.LBRACE, "{")) return
    while (!b.eof() && b.tokenType != T.RBRACE) {
      val before = b.currentOffset
      item(b)
      if (b.currentOffset == before && !b.eof() && b.tokenType != T.RBRACE) b.advanceLexer()
    }
    expect(b, T.RBRACE, "}")
  }

  private fun untilSemicolon(b: PsiBuilder, type: IElementType) {
    val m = b.mark()
    skipStatement(b)
    m.done(type)
  }

  private fun unexpected(b: PsiBuilder) {
    val m = b.mark()
    skipStatement(b)
    m.error(GrpcBundle.message("parser.unexpected"))
  }

  /** Skips to the end of the statement: its `;` or the block it opens, without eating the `}` of the enclosing block. */
  private fun skipStatement(b: PsiBuilder) {
    b.advanceLexer()
    while (!b.eof()) {
      when (b.tokenType) {
        T.SEMI -> {
          b.advanceLexer()
          return
        }
        T.RBRACE -> return
        T.LBRACE -> {
          balanced(b, T.LBRACE, T.RBRACE)
          return
        }
        else -> b.advanceLexer()
      }
    }
  }

  private fun balanced(b: PsiBuilder, open: IElementType, close: IElementType) {
    var depth = 0
    while (!b.eof()) {
      val t = b.tokenType
      b.advanceLexer()
      if (t == open) depth++
      if (t == close && --depth == 0) return
    }
  }

  private fun semicolon(b: PsiBuilder) {
    expect(b, T.SEMI, ";")
  }

  private fun expect(b: PsiBuilder, type: IElementType, text: String): Boolean {
    if (b.tokenType == type) {
      b.advanceLexer()
      return true
    }
    b.error(GrpcBundle.message("parser.expected", text))
    return false
  }

  private fun keyword(b: PsiBuilder): String? = if (b.tokenType == T.KEYWORD) b.tokenText else null

  private fun isWord(type: IElementType?) = type == T.IDENT || type == T.KEYWORD

  private companion object {
    val LABELS = setOf("repeated", "optional", "required")
  }
}
