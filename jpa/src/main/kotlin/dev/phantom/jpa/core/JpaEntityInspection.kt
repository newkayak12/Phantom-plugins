// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.jpa.core

import com.intellij.codeInspection.AbstractBaseUastLocalInspectionTool
import com.intellij.codeInspection.LocalInspectionToolSession
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.util.InheritanceUtil
import com.intellij.psi.util.PsiUtil
import com.intellij.uast.UastHintedVisitorAdapter
import org.jetbrains.uast.UAnnotated
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UClassLiteralExpression
import org.jetbrains.uast.UDeclaration
import org.jetbrains.uast.UField
import org.jetbrains.uast.evaluateString
import org.jetbrains.uast.visitor.AbstractUastNonRecursiveVisitor

/**
 * Mapping mistakes Hibernate rejects when the persistence unit starts: an entity without an identifier or with several
 * without `@IdClass`, a Java entity without a no-argument constructor, a `mappedBy` that names a missing attribute or
 * one that points at another entity, a raw collection association without `targetEntity`, and converters that do not
 * implement `AttributeConverter`.
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
      checkConverterClass(node, psiClass)
      if (!JpaModel.isPersistent(psiClass)) return true
      val anchor = node.uastAnchor?.sourcePsi
      if (JpaModel.isEntity(psiClass) && anchor != null) {
        if (!hasIdentifier(psiClass)) {
          holder.registerProblem(anchor, PhantomJpaBundle.message("entity.no.id", psiClass.name ?: ""))
        }
        else if (idCount(psiClass) > 1 && !hasIdClass(psiClass)) {
          holder.registerProblem(anchor, PhantomJpaBundle.message("entity.several.ids", psiClass.name ?: ""))
        }
        // Kotlin classes get their constructor from the kotlin-jpa plugin; PhantomKotlinJpaEntity checks that
        if (node.sourcePsi?.language == JavaLanguage.INSTANCE && !psiClass.isInterface && !hasNoArgConstructor(psiClass) &&
            !lombokReportsConstructor(psiClass)) {
          holder.registerProblem(anchor, PhantomJpaBundle.message("entity.no.default.constructor", psiClass.name ?: ""))
        }
      }
      for (declaration in node.fields.asList() + node.methods.asList()) {
        checkMappedBy(psiClass, declaration)
        checkConvert(declaration)
      }
      for (field in node.fields) checkRawCollection(field)
      return true
    }

    /** The identifier may sit on a field or getter of the entity or of any superclass (usually a `@MappedSuperclass`). */
    private fun hasIdentifier(psiClass: PsiClass): Boolean {
      val members: Sequence<PsiModifierListOwner> = psiClass.allFields.asSequence() + psiClass.allMethods.asSequence()
      return members.any { member -> ID.any { member.hasAnnotation(it) } }
    }

    /** Attributes with `@Id`, by attribute name: a Kotlin property may carry it on both its field and its getter. */
    private fun idCount(psiClass: PsiClass): Int {
      val fields = psiClass.allFields.filter { JpaMapping.hasOn(it, "Id") }.map { it.name }
      val getters = psiClass.allMethods.filter { JpaMapping.hasOn(it, "Id") }.map { it.name.removePrefix("get").removePrefix("is").replaceFirstChar(Char::lowercaseChar) }
      return (fields + getters).distinct().size
    }

    private fun hasIdClass(psiClass: PsiClass): Boolean =
      generateSequence(psiClass) { it.superClass }.any { JpaMapping.hasOn(it, "IdClass") }

    /** PhantomLombokEntity reports these with a fix that adds `@NoArgsConstructor`. */
    private fun lombokReportsConstructor(psiClass: PsiClass): Boolean =
      psiClass.hasAnnotation("lombok.Builder") || psiClass.hasAnnotation("lombok.AllArgsConstructor") && !psiClass.hasAnnotation("lombok.NoArgsConstructor")

    private fun hasNoArgConstructor(psiClass: PsiClass): Boolean =
      psiClass.constructors.isEmpty() || psiClass.constructors.any { it.parameterList.isEmpty }

    private fun <T> checkMappedBy(owner: PsiClass, declaration: T) where T : UDeclaration, T : UAnnotated {
      for (annotation in declaration.uAnnotations) {
        if (annotation.qualifiedName !in MAPPED_BY_OWNERS) continue
        val value = annotation.findDeclaredAttributeValue("mappedBy") ?: continue
        val mappedBy = value.evaluateString()?.takeIf { it.isNotBlank() } ?: continue
        val target = targetEntity(annotation) ?: JpaModel.valueClass(declaration.javaPsi as? PsiMember ?: continue) ?: continue
        if (!JpaModel.isPersistent(target)) continue
        val anchor = value.sourcePsi ?: continue
        val path = resolve(target, mappedBy)
        if (path == null) {
          holder.registerProblem(anchor, PhantomJpaBundle.message("entity.mapped.by.unknown", mappedBy, target.name ?: ""))
          continue
        }
        // the other side must refer back to this entity (or one of its superclasses)
        val back = JpaModel.valueClass(path) ?: continue
        if (!JpaModel.isPersistent(back) || InheritanceUtil.isInheritorOrSelf(owner, back, true) || InheritanceUtil.isInheritorOrSelf(back, owner, true)) continue
        holder.registerProblem(anchor, PhantomJpaBundle.message("entity.mapped.by.other", mappedBy, target.name ?: "", back.name ?: "", owner.name ?: ""))
      }
    }

    /** `mappedBy` may go through embeddables: `address.owner`. */
    private fun resolve(target: PsiClass, path: String): PsiMember? {
      var owner = target
      val parts = path.split('.')
      for ((index, part) in parts.withIndex()) {
        val attribute = JpaModel.attribute(owner, part) ?: return null
        if (index == parts.lastIndex) return attribute
        owner = JpaModel.valueClass(attribute) ?: return null
      }
      return null
    }

    private fun targetEntity(annotation: UAnnotation): PsiClass? {
      val literal = annotation.findDeclaredAttributeValue("targetEntity") as? UClassLiteralExpression ?: return null
      val type = literal.type as? PsiClassType ?: return null
      return PsiUtil.resolveClassInClassTypeOnly(type)
    }

    /** `@OneToMany List items` (raw): Hibernate cannot tell the target entity. */
    private fun checkRawCollection(field: UField) {
      val relation = JpaMapping.relation(field) ?: return
      if (JpaMapping.kind(relation) !in setOf("OneToMany", "ManyToMany") || targetEntity(relation) != null) return
      val type = field.type as? PsiClassType ?: return
      if (!type.isRaw || !JpaMapping.isCollectionOrMap(type)) return
      val anchor = field.uastAnchor?.sourcePsi ?: return
      holder.registerProblem(anchor, PhantomJpaBundle.message("entity.raw.collection", field.name))
    }

    private fun <T> checkConvert(declaration: T) where T : UDeclaration, T : UAnnotated {
      val convert = JpaMapping.annotation(declaration, "Convert") ?: return
      val literal = convert.findDeclaredAttributeValue("converter") as? UClassLiteralExpression ?: return
      val converter = PsiUtil.resolveClassInClassTypeOnly(literal.type) ?: return
      if (JpaMapping.implementsConverter(converter)) return
      holder.registerProblem(literal.sourcePsi ?: return, PhantomJpaBundle.message("converter.not.converter", converter.name ?: ""))
    }

    private fun checkConverterClass(node: UClass, psiClass: PsiClass) {
      if (!JpaMapping.has(node, "Converter") || psiClass.isAnnotationType || JpaMapping.implementsConverter(psiClass)) return
      val anchor = node.uastAnchor?.sourcePsi ?: return
      holder.registerProblem(anchor, PhantomJpaBundle.message("converter.not.converter", psiClass.name ?: ""))
    }
  }

  private companion object {
    val ID = listOf("jakarta.persistence.Id", "javax.persistence.Id", "jakarta.persistence.EmbeddedId", "javax.persistence.EmbeddedId")
    val MAPPED_BY_OWNERS = listOf("OneToMany", "OneToOne", "ManyToMany").flatMap { listOf("jakarta.persistence.$it", "javax.persistence.$it") }.toSet()
  }
}
