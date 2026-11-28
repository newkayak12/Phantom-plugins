// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.validation.core

import com.intellij.codeInsight.AnnotationUtil
import com.intellij.codeInspection.AbstractBaseUastLocalInspectionTool
import com.intellij.codeInspection.LocalInspectionToolSession
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassObjectAccessExpression
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.intellij.psi.util.InheritanceUtil
import com.intellij.psi.util.PsiUtil
import com.intellij.uast.UastHintedVisitorAdapter
import org.jetbrains.uast.UClass
import org.jetbrains.uast.visitor.AbstractUastNonRecursiveVisitor

/**
 * A custom constraint annotation (`@Constraint`) that the validator rejects when it first meets it: one of the
 * required attributes `message`, `groups` or `payload` is missing, or a `validatedBy` class is not a
 * `ConstraintValidator` for this annotation.
 */
class ConstraintDefinitionInspection : AbstractBaseUastLocalInspectionTool() {
  override fun isAvailableForFile(file: PsiFile): Boolean = !GhostMode.enabled && super.isAvailableForFile(file)
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    val module = ModuleUtilCore.findModuleForPsiElement(holder.file) ?: return PsiElementVisitor.EMPTY_VISITOR
    if (!ValidationGate.isValidationModule(module)) return PsiElementVisitor.EMPTY_VISITOR
    return UastHintedVisitorAdapter.create(holder.file.language, object : AbstractUastNonRecursiveVisitor() {
      override fun visitClass(node: UClass): Boolean {
        val annotationType = node.javaPsi
        if (!annotationType.isAnnotationType) return true
        val constraint = CONSTRAINT.firstNotNullOfOrNull { annotationType.getAnnotation(it) } ?: return true
        val anchor = node.uastAnchor?.sourcePsi ?: return true
        val missing = REQUIRED.filter { annotationType.findMethodsByName(it, false).isEmpty() }
        if (missing.isNotEmpty()) {
          holder.registerProblem(anchor, PhantomValidationBundle.message("definition.missing", annotationType.name ?: "", missing.joinToString(", ")))
        }
        for (value in AnnotationUtil.arrayAttributeValues(constraint.findDeclaredAttributeValue("validatedBy"))) {
          val literal = value as? PsiClassObjectAccessExpression ?: continue
          val validator = PsiUtil.resolveClassInClassTypeOnly(literal.operand.type) ?: continue
          val problem = problem(validator, annotationType) ?: continue
          holder.registerProblem(target(value, anchor), problem)
        }
        return true
      }

      /** The class literal when it is in this file (Java); the declaration otherwise (Kotlin light annotations). */
      private fun target(value: PsiElement, anchor: PsiElement): PsiElement =
        if (value.isPhysical && value.containingFile == holder.file && value.textLength > 0) value else anchor
    }, arrayOf(UClass::class.java), true)
  }

  private fun problem(validator: PsiClass, annotationType: PsiClass): String? {
    val base = VALIDATOR.firstOrNull { InheritanceUtil.isInheritor(validator, it) }
               ?: return PhantomValidationBundle.message("definition.not.validator", validator.name ?: "")
    val type = PsiUtil.substituteTypeParameter(JavaPsiFacade.getElementFactory(validator.project).createType(validator), base, 0, false) ?: return null
    val validated = PsiUtil.resolveClassInClassTypeOnly(type) ?: return null
    if (validated.isEquivalentTo(annotationType) || validated.qualifiedName == annotationType.qualifiedName) return null
    return PhantomValidationBundle.message("definition.other.annotation", validator.name ?: "", validated.name ?: "", annotationType.name ?: "")
  }

  private companion object {
    val CONSTRAINT = listOf("jakarta.validation.Constraint", "javax.validation.Constraint")
    val VALIDATOR = listOf("jakarta.validation.ConstraintValidator", "javax.validation.ConstraintValidator")
    val REQUIRED = listOf("message", "groups", "payload")
  }
}
