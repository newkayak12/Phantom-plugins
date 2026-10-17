// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.jpa.core

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.openapi.util.TextRange
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil

open class JpqlElement(node: ASTNode) : ASTWrapperPsiElement(node)

/** `FROM ReservationEntity r`: the entity name, a reference to the entity class. */
class JpqlEntityRef(node: ASTNode) : JpqlElement(node) {
  override fun getReference(): PsiReference = EntityReference(this)

  fun resolveEntity(): PsiClass? = reference.resolve() as? PsiClass

  private class EntityReference(element: JpqlEntityRef) : PsiReferenceBase<JpqlEntityRef>(element, TextRange(0, element.textLength)) {
    override fun resolve(): PsiElement? = JpaModel.findEntity(element.text, element)

    override fun getVariants(): Array<Any> = JpaModel.entities(element).mapNotNull { cls ->
      val name = JpaModel.entityName(cls) ?: return@mapNotNull null
      LookupElementBuilder.create(cls, name).withTypeText(cls.qualifiedName?.substringBeforeLast('.'), true)
    }.toTypedArray()

    override fun handleElementRename(newElementName: String): PsiElement = element // v0.1: rename is not propagated
  }
}

/** `r` in `FROM ReservationEntity r` or `JOIN r.items i`: an identification variable. */
class JpqlAliasDecl(node: ASTNode) : JpqlElement(node), PsiNamedElement {
  override fun getName(): String = text

  override fun setName(name: String): PsiElement = this

  /** What the alias ranges over, following entity names and join paths. */
  fun resolveType(depth: Int = 0): PsiClass? {
    if (depth > MAX_DEPTH) return null
    var prev = PsiTreeUtil.skipWhitespacesAndCommentsBackward(this)
    while (prev != null && (prev.node.elementType == JpqlTokens.KEYWORD && prev.text.equals("AS", true) || prev.node.elementType == JpqlTokens.RPAREN)) {
      prev = PsiTreeUtil.skipWhitespacesAndCommentsBackward(prev)
    }
    return when (prev) {
      is JpqlEntityRef -> prev.resolveEntity()
      is JpqlPath -> prev.segments().lastOrNull()?.resolveType(depth + 1)
      else -> null
    }
  }

  companion object {
    const val MAX_DEPTH: Int = 12
  }
}

/** `r.timeTable.date`: a path, each segment its own reference. */
class JpqlPath(node: ASTNode) : JpqlElement(node) {
  fun segments(): List<JpqlPathSegment> = PsiTreeUtil.getChildrenOfTypeAsList(this, JpqlPathSegment::class.java)
}

class JpqlPathSegment(node: ASTNode) : JpqlElement(node) {
  private val path: JpqlPath? get() = parent as? JpqlPath

  val index: Int get() = path?.segments()?.indexOf(this) ?: 0

  /** The class whose attribute this segment names (null for the first segment, which names an alias). */
  fun ownerType(depth: Int = 0): PsiClass? {
    val segments = path?.segments() ?: return null
    val i = segments.indexOf(this)
    return if (i <= 0) null else segments[i - 1].resolveType(depth + 1)
  }

  fun resolveType(depth: Int = 0): PsiClass? {
    if (depth > JpqlAliasDecl.MAX_DEPTH) return null
    return when (val target = resolveTarget(depth)) {
      is JpqlAliasDecl -> target.resolveType(depth + 1)
      is PsiMember -> JpaModel.valueClass(target)
      else -> null
    }
  }

  fun resolveTarget(depth: Int = 0): PsiElement? {
    if (index == 0) return findAlias(text)
    val owner = ownerType(depth) ?: return null
    return JpaModel.attribute(owner, text)
  }

  private fun findAlias(name: String): JpqlAliasDecl? =
    PsiTreeUtil.findChildrenOfType(containingFile, JpqlAliasDecl::class.java).firstOrNull { it.name.equals(name, ignoreCase = true) }

  override fun getReference(): PsiReference = SegmentReference(this)

  private class SegmentReference(element: JpqlPathSegment) : PsiReferenceBase<JpqlPathSegment>(element, TextRange(0, element.textLength), true) {
    override fun resolve(): PsiElement? = element.resolveTarget()

    override fun getVariants(): Array<Any> {
      if (element.index == 0) {
        return PsiTreeUtil.findChildrenOfType(element.containingFile, JpqlAliasDecl::class.java)
          .map { LookupElementBuilder.create(it.name) }.toTypedArray()
      }
      val owner = element.ownerType() ?: return emptyArray()
      return JpaModel.attributes(owner).map { field ->
        val element: LookupElement = LookupElementBuilder.create(field, field.name).withTypeText(field.type.presentableText, true)
        element
      }.toTypedArray()
    }

    override fun handleElementRename(newElementName: String): PsiElement = element
  }
}

/** `SELECT NEW com.example.Dto(...)`: a class reference. */
class JpqlClassRef(node: ASTNode) : JpqlElement(node) {
  override fun getReference(): PsiReference = object : PsiReferenceBase<JpqlClassRef>(this, TextRange(0, textLength), true) {
    override fun resolve(): PsiElement? = JavaPsiFacade.getInstance(project).findClass(element.text, JpaModel.scopeFor(element))
    override fun handleElementRename(newElementName: String): PsiElement = element
  }
}

class JpqlFunctionName(node: ASTNode) : JpqlElement(node)
