// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.graphql.psi

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.ResolveResult

/**
 * A name that refers to definitions anywhere in the project's GraphQL files. Soft, because built-in scalars and types
 * declared by a server library are legitimately missing from the project's schema files.
 */
class GraphQLNameReference(
  element: PsiElement,
  nameOffset: Int,
  private val targets: (Project, String) -> List<GraphQLNamedElement>,
) : PsiPolyVariantReferenceBase<PsiElement>(element, TextRange(nameOffset, element.textLength), true) {
  override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
    PsiElementResolveResult.createResults(targets(element.project, value))

  override fun handleElementRename(newElementName: String): PsiElement {
    val leaf = element.findElementAt(rangeInElement.startOffset) ?: return element
    leaf.node.treeParent.replaceChild(leaf.node, GraphQLElementFactory.nameLeaf(element.project, newElementName))
    return element
  }
}
