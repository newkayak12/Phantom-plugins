// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.graphql.psi

import com.intellij.lang.ASTNode
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFileFactory
import dev.phantom.graphql.lang.GraphQLElementTypes
import dev.phantom.graphql.lang.GraphQLLanguage

internal object GraphQLElementFactory {
  /** A NAME (or KEYWORD) leaf for [name], parsed from a throwaway definition. */
  fun nameLeaf(project: Project, name: String): ASTNode {
    val file = PsiFileFactory.getInstance(project).createFileFromText("dummy.graphqls", GraphQLLanguage, "scalar $name")
    val identifier = file.node.firstChildNode.findChildByType(GraphQLElementTypes.IDENTIFIER)
    return requireNotNull(identifier?.firstChildNode) { "Not a GraphQL name: $name" }
  }
}
