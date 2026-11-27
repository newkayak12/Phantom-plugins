// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.graphql.lang

import com.intellij.lang.ASTNode
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.psi.tree.IElementType
import dev.phantom.graphql.GraphQLBundle
import dev.phantom.graphql.lang.GraphQLElementTypes as E
import dev.phantom.graphql.lang.GraphQLTokenTypes as T

/**
 * A recursive-descent parser for schema (SDL) and executable documents. It keeps going after errors: a definition keyword
 * inside an unclosed type or enum body ends that body, so one missing brace does not swallow the rest of the file.
 */
class GraphQLParser : PsiParser {
  override fun parse(root: IElementType, builder: PsiBuilder): ASTNode {
    val file = builder.mark()
    Run(builder).document()
    file.done(root)
    return builder.treeBuilt
  }

  private class Run(val b: PsiBuilder) {
    fun document() {
      while (!b.eof()) definition()
    }

    private fun at(type: IElementType) = b.tokenType == type

    private fun atName() = b.tokenType in T.NAMES

    private fun atKeyword(text: String) = b.tokenType == T.KEYWORD && b.tokenText == text

    private fun expect(type: IElementType, what: String): Boolean {
      if (at(type)) {
        b.advanceLexer()
        return true
      }
      b.error(GraphQLBundle.message("parser.expected", what))
      return false
    }

    private fun errorAdvance(key: String) {
      val m = b.mark()
      b.advanceLexer()
      m.error(GraphQLBundle.message(key))
    }

    /** In a schema body: true at a keyword that starts a definition, followed by a name or brace instead of ':' or '('. */
    private fun atDefinitionStart(): Boolean {
      if (b.tokenType != T.KEYWORD || b.tokenText !in DEFINITION_KEYWORDS) return false
      val next = b.lookAhead(1)
      return next in T.NAMES || next == T.LBRACE || next == T.AT
    }

    private fun name(type: IElementType? = null) {
      if (!atName()) {
        b.error(GraphQLBundle.message("parser.expected", "name"))
        return
      }
      if (type == null) {
        b.advanceLexer()
      } else {
        val m = b.mark()
        b.advanceLexer()
        m.done(type)
      }
    }

    private fun definition() {
      val m = b.mark()
      if (at(T.STRING)) b.advanceLexer()
      when {
        at(T.LBRACE) -> operation(m)
        b.tokenType != T.KEYWORD -> {
          m.drop()
          errorAdvance("parser.definition.expected")
        }
        else -> when (b.tokenText) {
          "schema" -> schema(m)
          "extend" -> {
            b.advanceLexer()
            if (atKeyword("schema")) schema(m) else typeDefinition(m, extension = true)
          }
          "scalar", "type", "interface", "union", "enum", "input" -> typeDefinition(m, extension = false)
          "directive" -> directiveDefinition(m)
          "query", "mutation", "subscription" -> operation(m)
          "fragment" -> fragment(m)
          else -> {
            m.drop()
            errorAdvance("parser.definition.expected")
          }
        }
      }
    }

    private fun typeDefinition(m: PsiBuilder.Marker, extension: Boolean) {
      val kind = b.tokenText
      if (b.tokenType == T.KEYWORD && kind in TYPE_KEYWORDS) b.advanceLexer() else b.error(GraphQLBundle.message("parser.expected", "type"))
      name(if (extension) E.TYPE_REFERENCE else E.IDENTIFIER)
      if (atKeyword("implements")) {
        b.advanceLexer()
        if (at(T.AMP)) b.advanceLexer()
        typeReference()
        while (at(T.AMP)) {
          b.advanceLexer()
          typeReference()
        }
      }
      directives()
      when (kind) {
        "type", "interface" -> if (at(T.LBRACE)) fieldsDefinition(E.FIELD_DEFINITION)
        "input" -> if (at(T.LBRACE)) fieldsDefinition(E.INPUT_VALUE_DEFINITION)
        "enum" -> if (at(T.LBRACE)) enumValues()
        "union" -> if (at(T.EQ)) {
          b.advanceLexer()
          if (at(T.PIPE)) b.advanceLexer()
          typeReference()
          while (at(T.PIPE)) {
            b.advanceLexer()
            typeReference()
          }
        }
      }
      m.done(if (extension) E.TYPE_EXTENSION else E.TYPE_DEFINITION)
    }

    private inline fun body(open: IElementType, close: IElementType, closeText: String, recover: Boolean = false, item: () -> Unit) {
      expect(open, open.toString())
      while (!b.eof() && !at(close)) {
        if (recover && atDefinitionStart()) break
        val before = b.currentOffset
        item()
        if (b.currentOffset == before) errorAdvance("parser.unexpected")
      }
      expect(close, closeText)
    }

    private fun fieldsDefinition(fieldType: IElementType) = body(T.LBRACE, T.RBRACE, "}", recover = true) {
      if (atName() || at(T.STRING)) inputValueOrField(fieldType)
    }

    private fun inputValueOrField(type: IElementType) {
      val m = b.mark()
      if (at(T.STRING)) b.advanceLexer()
      name(E.IDENTIFIER)
      if (at(T.LPAREN)) body(T.LPAREN, T.RPAREN, ")") { if (atName() || at(T.STRING)) inputValueOrField(E.INPUT_VALUE_DEFINITION) }
      expect(T.COLON, ":")
      type()
      if (at(T.EQ)) {
        b.advanceLexer()
        value()
      }
      directives()
      m.done(type)
    }

    private fun enumValues() = body(T.LBRACE, T.RBRACE, "}", recover = true) {
      if (atName() || at(T.STRING)) {
        val m = b.mark()
        if (at(T.STRING)) b.advanceLexer()
        name(E.IDENTIFIER)
        directives()
        m.done(E.ENUM_VALUE_DEFINITION)
      }
    }

    private fun type() {
      if (at(T.LBRACKET)) {
        b.advanceLexer()
        type()
        expect(T.RBRACKET, "]")
      } else {
        typeReference()
      }
      if (at(T.BANG)) b.advanceLexer()
    }

    private fun typeReference() = name(E.TYPE_REFERENCE)

    private fun directives() {
      while (at(T.AT)) {
        val m = b.mark()
        b.advanceLexer()
        name()
        if (at(T.LPAREN)) arguments()
        m.done(E.DIRECTIVE)
      }
    }

    private fun arguments() {
      val m = b.mark()
      body(T.LPAREN, T.RPAREN, ")") {
        if (atName()) {
          b.advanceLexer()
          expect(T.COLON, ":")
          value()
        }
      }
      m.done(E.ARGUMENTS)
    }

    private fun value() {
      val m = b.mark()
      when {
        at(T.DOLLAR) -> {
          b.advanceLexer()
          name()
        }
        at(T.LBRACKET) -> body(T.LBRACKET, T.RBRACKET, "]") { value() }
        at(T.LBRACE) -> body(T.LBRACE, T.RBRACE, "}") {
          if (atName()) {
            b.advanceLexer()
            expect(T.COLON, ":")
            value()
          }
        }
        atName() || at(T.STRING) || at(T.NUMBER) -> b.advanceLexer()
        else -> b.error(GraphQLBundle.message("parser.expected", "value"))
      }
      m.done(E.VALUE)
    }

    private fun schema(m: PsiBuilder.Marker) {
      b.advanceLexer()
      directives()
      if (at(T.LBRACE)) {
        body(T.LBRACE, T.RBRACE, "}") {
          if (atName()) {        b.advanceLexer()
            expect(T.COLON, ":")
            typeReference()
          }
        }
      }
      m.done(E.SCHEMA_DEFINITION)
    }

    private fun directiveDefinition(m: PsiBuilder.Marker) {
      b.advanceLexer()
      expect(T.AT, "@")
      name(E.IDENTIFIER)
      if (at(T.LPAREN)) body(T.LPAREN, T.RPAREN, ")") { if (atName() || at(T.STRING)) inputValueOrField(E.INPUT_VALUE_DEFINITION) }
      if (atKeyword("repeatable")) b.advanceLexer()
      if (atKeyword("on")) b.advanceLexer() else b.error(GraphQLBundle.message("parser.expected", "on"))
      if (at(T.PIPE)) b.advanceLexer()
      name()
      while (at(T.PIPE)) {
        b.advanceLexer()
        name()
      }
      m.done(E.DIRECTIVE_DEFINITION)
    }

    private fun operation(m: PsiBuilder.Marker) {
      if (b.tokenType == T.KEYWORD) {
        b.advanceLexer()
        if (atName()) name(E.IDENTIFIER)
        if (at(T.LPAREN)) {
          body(T.LPAREN, T.RPAREN, ")") {
            if (at(T.DOLLAR)) {
              val v = b.mark()
              b.advanceLexer()
              name()
              expect(T.COLON, ":")
              type()
              if (at(T.EQ)) {
                b.advanceLexer()
                value()
              }
              directives()
              v.done(E.VARIABLE_DEFINITION)
            }
          }
        }
        directives()
      }
      selectionSet()
      m.done(E.OPERATION_DEFINITION)
    }

    private fun fragment(m: PsiBuilder.Marker) {
      b.advanceLexer()
      name(E.IDENTIFIER)
      if (atKeyword("on")) {
        b.advanceLexer()
        typeReference()
      } else {
        b.error(GraphQLBundle.message("parser.expected", "on"))
      }
      directives()
      selectionSet()
      m.done(E.FRAGMENT_DEFINITION)
    }

    private fun selectionSet() {
      val m = b.mark()
      body(T.LBRACE, T.RBRACE, "}") { selection() }
      m.done(E.SELECTION_SET)
    }

    private fun selection() {
      val m = b.mark()
      when {
        at(T.SPREAD) -> {
          b.advanceLexer()
          if (atKeyword("on")) {
            b.advanceLexer()
            typeReference()
          } else if (atName()) {
            b.advanceLexer()
            directives()
            m.done(E.FRAGMENT_SPREAD)
            return
          }
          directives()
          selectionSet()
          m.done(E.INLINE_FRAGMENT)
        }
        atName() -> {
          b.advanceLexer()
          if (at(T.COLON)) {
            b.advanceLexer()
            name()
          }
          if (at(T.LPAREN)) arguments()
          directives()
          if (at(T.LBRACE)) selectionSet()
          m.done(E.FIELD)
        }
        else -> m.drop()
      }
    }
  }

  private companion object {
    val TYPE_KEYWORDS = setOf("scalar", "type", "interface", "union", "enum", "input")
    val DEFINITION_KEYWORDS = TYPE_KEYWORDS + setOf("schema", "extend", "directive", "query", "mutation", "subscription", "fragment")
  }
}
