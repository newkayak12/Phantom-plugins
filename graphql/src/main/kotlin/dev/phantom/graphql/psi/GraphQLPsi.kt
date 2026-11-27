// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.graphql.psi

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.ASTNode
import com.intellij.openapi.fileTypes.FileType
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiReference
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import dev.phantom.graphql.lang.GraphQLElementTypes
import dev.phantom.graphql.lang.GraphQLFileType
import dev.phantom.graphql.lang.GraphQLLanguage
import dev.phantom.graphql.lang.GraphQLTokenTypes

class GraphQLFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, GraphQLLanguage) {
  override fun getFileType(): FileType = GraphQLFileType

  // The lists below are cached until the file changes, so project-wide lookups walk each file's tree only once.
  val typeDefinitions: List<GraphQLTypeDefinition>
    get() = CachedValuesManager.getProjectPsiDependentCache(this) { PsiTreeUtil.getChildrenOfTypeAsList(it, GraphQLTypeDefinition::class.java) }

  val fieldDefinitions: List<GraphQLFieldDefinition>
    get() = CachedValuesManager.getProjectPsiDependentCache(this) { PsiTreeUtil.findChildrenOfType(it, GraphQLFieldDefinition::class.java).toList() }

  val fragmentDefinitions: List<GraphQLFragmentDefinition>
    get() = CachedValuesManager.getProjectPsiDependentCache(this) {
      PsiTreeUtil.getChildrenOfTypeAsList(it, GraphQLFragmentDefinition::class.java)
    }

  override fun toString(): String = "GraphQL file"
}

/** A definition with a name of its own: types, fields, fragments, arguments, enum values and directives. */
open class GraphQLNamedElement(node: ASTNode) : ASTWrapperPsiElement(node), PsiNameIdentifierOwner {
  override fun getNameIdentifier(): PsiElement? = node.findChildByType(GraphQLElementTypes.IDENTIFIER)?.psi

  override fun getName(): String? = nameIdentifier?.text

  override fun setName(name: String): PsiElement {
    val identifier = node.findChildByType(GraphQLElementTypes.IDENTIFIER) ?: return this
    identifier.replaceChild(identifier.firstChildNode, GraphQLElementFactory.nameLeaf(project, name))
    return this
  }

  override fun getTextOffset(): Int = nameIdentifier?.textOffset ?: super.getTextOffset()
}

class GraphQLTypeDefinition(node: ASTNode) : GraphQLNamedElement(node) {
  /** The keyword the definition starts with: type, interface, input, enum, union or scalar. */
  val kind: String? get() = node.findChildByType(GraphQLTokenTypes.KEYWORD)?.text
}

class GraphQLFieldDefinition(node: ASTNode) : GraphQLNamedElement(node) {
  /** The name of the type the field belongs to, also when it is declared in an `extend type`. */
  val ownerTypeName: String?
    get() = when (val owner = parent) {
      is GraphQLTypeDefinition -> owner.name
      is GraphQLTypeExtension -> owner.typeReference?.text
      else -> null
    }
}

class GraphQLFragmentDefinition(node: ASTNode) : GraphQLNamedElement(node)

class GraphQLTypeExtension(node: ASTNode) : ASTWrapperPsiElement(node) {
  val typeReference: GraphQLTypeReference? get() = findChildByClass(GraphQLTypeReference::class.java)
}

class GraphQLTypeReference(node: ASTNode) : ASTWrapperPsiElement(node) {
  override fun getReference(): PsiReference = GraphQLNameReference(this, 0) { project, name -> GraphQLSchemaSearch.typeDefinitions(project, name) }
}

class GraphQLFragmentSpread(node: ASTNode) : ASTWrapperPsiElement(node) {
  /** The fragment name after the spread; null for an incomplete spread. */
  val nameElement: PsiElement? get() = node.findChildByType(GraphQLTokenTypes.NAMES)?.psi

  override fun getReference(): PsiReference? = nameElement?.let { name ->
    GraphQLNameReference(this, name.startOffsetInParent) { project, text -> GraphQLSchemaSearch.fragmentDefinitions(project, text) }
  }
}
