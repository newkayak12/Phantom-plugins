// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.graphql.lang

import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet

class GraphQLTokenType(debugName: String) : IElementType(debugName, GraphQLLanguage)

object GraphQLTokenTypes {
  @JvmField val NAME = GraphQLTokenType("NAME")

  /** A name the grammar gives a meaning in some places; it stays usable as a field or argument name. */
  @JvmField val KEYWORD = GraphQLTokenType("KEYWORD")
  @JvmField val STRING = GraphQLTokenType("STRING")
  @JvmField val NUMBER = GraphQLTokenType("NUMBER")
  @JvmField val COMMENT = GraphQLTokenType("COMMENT")
  @JvmField val LBRACE = GraphQLTokenType("{")
  @JvmField val RBRACE = GraphQLTokenType("}")
  @JvmField val LPAREN = GraphQLTokenType("(")
  @JvmField val RPAREN = GraphQLTokenType(")")
  @JvmField val LBRACKET = GraphQLTokenType("[")
  @JvmField val RBRACKET = GraphQLTokenType("]")
  @JvmField val COLON = GraphQLTokenType(":")
  @JvmField val BANG = GraphQLTokenType("!")
  @JvmField val EQ = GraphQLTokenType("=")
  @JvmField val PIPE = GraphQLTokenType("|")
  @JvmField val AMP = GraphQLTokenType("&")
  @JvmField val AT = GraphQLTokenType("@")
  @JvmField val DOLLAR = GraphQLTokenType("$")
  @JvmField val SPREAD = GraphQLTokenType("...")

  /** Commas are insignificant in GraphQL, so the parser skips them like whitespace. */
  @JvmField val COMMA = GraphQLTokenType(",")

  val KEYWORDS = setOf(
    "type", "interface", "input", "enum", "union", "scalar", "schema", "extend", "directive", "implements", "repeatable",
    "query", "mutation", "subscription", "fragment", "on", "true", "false", "null",
  )

  val NAMES = TokenSet.create(NAME, KEYWORD)
  val SKIPPED = TokenSet.create(TokenType.WHITE_SPACE, COMMA)
  val COMMENTS = TokenSet.create(COMMENT)
  val STRINGS = TokenSet.create(STRING)

  val PUNCTUATION = mapOf(
    '{' to LBRACE, '}' to RBRACE, '(' to LPAREN, ')' to RPAREN, '[' to LBRACKET, ']' to RBRACKET,
    ':' to COLON, '!' to BANG, '=' to EQ, '|' to PIPE, '&' to AMP, '@' to AT, '$' to DOLLAR, ',' to COMMA,
  )
}
