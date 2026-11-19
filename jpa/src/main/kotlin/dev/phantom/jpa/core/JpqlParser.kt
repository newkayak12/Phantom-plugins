// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.jpa.core

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
 * A tolerant structural parser. It never reports syntax errors (HQL has many extensions and queries are often
 * half-typed); it only marks the nodes that carry references: entity names, alias declarations, paths, `NEW` classes.
 */
class JpqlParser : PsiParser {
  override fun parse(root: IElementType, builder: PsiBuilder): ASTNode {
    val file = builder.mark()
    State(builder).run()
    file.done(root)
    return builder.treeBuilt
  }

  private class State(private val b: PsiBuilder) {
    /** Per parenthesis depth: are we in a FROM clause (a comma starts another range variable)? */
    private val inFrom = ArrayDeque<Boolean>().apply { addLast(false) }
    private var expectEntity = false
    private var expectAlias = false
    private var afterJoin = false
    private var closeParenStartsAlias = 0

    private fun keyword(): String? = if (b.tokenType == JpqlTokens.KEYWORD) b.tokenText?.uppercase() else null

    fun run() {
      while (!b.eof()) {
        val type = b.tokenType
        val kw = keyword()
        when {
          kw != null -> onKeyword(kw)
          type == JpqlTokens.IDENTIFIER -> onIdentifier()
          type == JpqlTokens.COMMA -> {
            expectAlias = false
            if (inFrom.last()) expectEntity = true
            b.advanceLexer()
          }
          type == JpqlTokens.LPAREN -> {
            inFrom.addLast(false)
            expectAlias = false
            b.advanceLexer()
          }
          type == JpqlTokens.RPAREN -> {
            if (inFrom.size > 1) inFrom.removeLast()
            b.advanceLexer()
            if (closeParenStartsAlias > 0 && closeParenStartsAlias == inFrom.size) {
              closeParenStartsAlias = 0
              expectAlias = true
            }
          }
          else -> {
            expectAlias = false
            b.advanceLexer()
          }
        }
      }
    }

    private fun onKeyword(kw: String) {
      when (kw) {
        "FROM" -> {
          inFrom[inFrom.lastIndex] = true
          expectEntity = true
        }
        "UPDATE" -> expectEntity = true
        "JOIN" -> afterJoin = true
        "AS" -> {
          b.advanceLexer()
          return
        }
        "IN" -> if (inFrom.last() && !afterJoin) closeParenStartsAlias = inFrom.size
        "NEW" -> {
          b.advanceLexer()
          if (b.tokenType == JpqlTokens.IDENTIFIER) qualifiedName(JpqlElements.CLASS_REF)
          return
        }
        "WHERE", "GROUP", "ORDER", "HAVING", "SET", "ON", "WITH", "SELECT", "UNION", "INTERSECT", "EXCEPT", "LIMIT", "OFFSET" ->
          inFrom[inFrom.lastIndex] = false
      }
      expectAlias = false
      if (kw != "FROM" && kw != "UPDATE" && kw != "DELETE") expectEntity = false
      b.advanceLexer()
    }

    private fun onIdentifier() {
      when {
        afterJoin && b.lookAhead(1) != JpqlTokens.DOT -> {
          // Hibernate entity join: JOIN Entity alias ON ...
          afterJoin = false
          qualifiedName(JpqlElements.ENTITY_REF)
          expectAlias = true
        }
        expectEntity -> {
          expectEntity = false
          qualifiedName(JpqlElements.ENTITY_REF)
          expectAlias = true
        }
        expectAlias -> {
          expectAlias = false
          val m = b.mark()
          b.advanceLexer()
          m.done(JpqlElements.ALIAS_DECL)
        }
        b.lookAhead(1) == JpqlTokens.LPAREN -> {
          val m = b.mark()
          b.advanceLexer()
          m.done(JpqlElements.FUNCTION_NAME)
        }
        else -> {
          path()
          if (afterJoin) {
            afterJoin = false
            expectAlias = true
          }
        }
      }
    }

    private fun path() {
      val path = b.mark()
      segment()
      while (b.tokenType == JpqlTokens.DOT) {
        b.advanceLexer()
        if (b.tokenType == JpqlTokens.IDENTIFIER || b.tokenType == JpqlTokens.KEYWORD) segment() else break
      }
      path.done(JpqlElements.PATH)
    }

    private fun segment() {
      val m = b.mark()
      b.advanceLexer()
      m.done(JpqlElements.PATH_SEGMENT)
    }

    private fun qualifiedName(type: IElementType) {
      val m = b.mark()
      b.advanceLexer()
      while (b.tokenType == JpqlTokens.DOT && b.lookAhead(1) == JpqlTokens.IDENTIFIER) {
        b.advanceLexer()
        b.advanceLexer()
      }
      m.done(type)
    }
  }
}

class JpqlParserDefinition : ParserDefinition {
  override fun createLexer(project: Project?): Lexer = JpqlLexer()
  override fun createParser(project: Project?): PsiParser = JpqlParser()
  override fun getFileNodeType(): IFileElementType = JpqlElements.FILE
  override fun getCommentTokens(): TokenSet = JpqlTokens.COMMENTS
  override fun getStringLiteralElements(): TokenSet = JpqlTokens.STRINGS
  override fun createFile(viewProvider: FileViewProvider): PsiFile = JpqlFile(viewProvider)

  override fun createElement(node: ASTNode): PsiElement = when (node.elementType) {
    JpqlElements.ENTITY_REF -> JpqlEntityRef(node)
    JpqlElements.ALIAS_DECL -> JpqlAliasDecl(node)
    JpqlElements.PATH -> JpqlPath(node)
    JpqlElements.PATH_SEGMENT -> JpqlPathSegment(node)
    JpqlElements.CLASS_REF -> JpqlClassRef(node)
    else -> JpqlFunctionName(node)
  }
}
