// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.jpa.core

import com.intellij.codeInspection.AbstractBaseUastLocalInspectionTool
import com.intellij.codeInspection.LocalInspectionToolSession
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.util.PsiUtil
import com.intellij.uast.UastHintedVisitorAdapter
import org.jetbrains.uast.UAnnotated
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UClassLiteralExpression
import org.jetbrains.uast.UDeclaration
import org.jetbrains.uast.evaluateString
import org.jetbrains.uast.visitor.AbstractUastNonRecursiveVisitor

/**
 * Mapping mistakes Hibernate rejects when the persistence unit starts: an entity without an identifier, and a
 * `mappedBy` that names an attribute the other side does not have.
 */
class JpaEntityInspection : AbstractBaseUastLocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    val module = ModuleUtilCore.findModuleForPsiElement(holder.file) ?: return PsiElementVisitor.EMPTY_VISITOR
    if (!JpaGate.isJpaModule(module)) return PsiElementVisitor.EMPTY_VISITOR
    return UastHintedVisitorAdapter.create(holder.file.language, Visitor(holder), arrayOf(UClass::class.java), true)
  }

  private class Visitor(private val holder: ProblemsHolder) : AbstractUastNonRecursiveVisitor() {
    override fun visitClass(node: UClass): Boolean {
      val psiClass = node.javaPsi
      if (!JpaModel.isPersistent(psiClass)) return true
      if (JpaModel.isEntity(psiClass) && !hasIdentifier(psiClass)) {
        node.uastAnchor?.sourcePsi?.let { holder.registerProblem(it, PhantomJpaBundle.message("entity.no.id", psiClass.name ?: "")) }
      }
      for (declaration in node.fields.asList() + node.methods.asList()) checkMappedBy(declaration)
      return true
    }

    /** The identifier may sit on a field or getter of the entity or of any superclass (usually a `@MappedSuperclass`). */
    private fun hasIdentifier(psiClass: PsiClass): Boolean {
      val members: Sequence<PsiModifierListOwner> = psiClass.allFields.asSequence() + psiClass.allMethods.asSequence()
      return members.any { member -> ID.any { member.hasAnnotation(it) } }
    }

    private fun <T> checkMappedBy(declaration: T) where T : UDeclaration, T : UAnnotated {
      for (annotation in declaration.uAnnotations) {
        if (annotation.qualifiedName !in MAPPED_BY_OWNERS) continue
        val value = annotation.findDeclaredAttributeValue("mappedBy") ?: continue
        val mappedBy = value.evaluateString()?.takeIf { it.isNotBlank() } ?: continue
        val target = targetEntity(annotation) ?: JpaModel.valueClass(declaration.javaPsi as? PsiMember ?: continue) ?: continue
        if (!JpaModel.isPersistent(target) || resolves(target, mappedBy)) continue
        val anchor = value.sourcePsi ?: continue
        holder.registerProblem(anchor, PhantomJpaBundle.message("entity.mapped.by.unknown", mappedBy, target.name ?: ""))
      }
    }

    /** `mappedBy` may go through embeddables: `address.owner`. */
    private fun resolves(target: PsiClass, path: String): Boolean {
      var owner = target
      val parts = path.split('.')
      for ((index, part) in parts.withIndex()) {
        val attribute = JpaModel.attribute(owner, part) ?: return false
        if (index < parts.lastIndex) owner = JpaModel.valueClass(attribute) ?: return false
      }
      return true
    }

    private fun targetEntity(annotation: UAnnotation): PsiClass? {
      val literal = annotation.findDeclaredAttributeValue("targetEntity") as? UClassLiteralExpression ?: return null
      val type = literal.type as? PsiClassType ?: return null
      return PsiUtil.resolveClassInClassTypeOnly(type)
    }
  }

  private companion object {
    val ID = listOf("jakarta.persistence.Id", "javax.persistence.Id", "jakarta.persistence.EmbeddedId", "javax.persistence.EmbeddedId")
    val MAPPED_BY_OWNERS = listOf("OneToMany", "OneToOne", "ManyToMany").flatMap { listOf("jakarta.persistence.$it", "javax.persistence.$it") }.toSet()
  }
}
