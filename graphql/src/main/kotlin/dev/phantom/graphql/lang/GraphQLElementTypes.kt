// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.graphql.lang

import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType

class GraphQLElementType(debugName: String) : IElementType(debugName, GraphQLLanguage)

object GraphQLElementTypes {
  @JvmField val FILE = IFileElementType(GraphQLLanguage)

  /** The name a definition declares; a name that refers to another definition is a TYPE_REFERENCE or FRAGMENT_SPREAD. */
  @JvmField val IDENTIFIER = GraphQLElementType("IDENTIFIER")
  @JvmField val TYPE_DEFINITION = GraphQLElementType("TYPE_DEFINITION")
  @JvmField val TYPE_EXTENSION = GraphQLElementType("TYPE_EXTENSION")
  @JvmField val SCHEMA_DEFINITION = GraphQLElementType("SCHEMA_DEFINITION")
  @JvmField val DIRECTIVE_DEFINITION = GraphQLElementType("DIRECTIVE_DEFINITION")
  @JvmField val FIELD_DEFINITION = GraphQLElementType("FIELD_DEFINITION")
  @JvmField val INPUT_VALUE_DEFINITION = GraphQLElementType("INPUT_VALUE_DEFINITION")
  @JvmField val ENUM_VALUE_DEFINITION = GraphQLElementType("ENUM_VALUE_DEFINITION")
  @JvmField val TYPE_REFERENCE = GraphQLElementType("TYPE_REFERENCE")
  @JvmField val DIRECTIVE = GraphQLElementType("DIRECTIVE")
  @JvmField val ARGUMENTS = GraphQLElementType("ARGUMENTS")
  @JvmField val VALUE = GraphQLElementType("VALUE")
  @JvmField val OPERATION_DEFINITION = GraphQLElementType("OPERATION_DEFINITION")
  @JvmField val VARIABLE_DEFINITION = GraphQLElementType("VARIABLE_DEFINITION")
  @JvmField val FRAGMENT_DEFINITION = GraphQLElementType("FRAGMENT_DEFINITION")
  @JvmField val SELECTION_SET = GraphQLElementType("SELECTION_SET")
  @JvmField val FIELD = GraphQLElementType("FIELD")
  @JvmField val FRAGMENT_SPREAD = GraphQLElementType("FRAGMENT_SPREAD")
  @JvmField val INLINE_FRAGMENT = GraphQLElementType("INLINE_FRAGMENT")
}
