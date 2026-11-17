// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.jpa.core

import com.intellij.codeInsight.AnnotationUtil
import com.intellij.codeInsight.intention.AddAnnotationModCommandAction
import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.LocalQuickFix.from
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.psi.JavaElementVisitor
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiField

/**
 * Lombok on Java entities:
 * - `@Data`, `@EqualsAndHashCode` or `@ToString` that read a lazy association, so equals/hashCode/toString load it
 *   (or fail outside a session) and two-way associations recurse
 * - `@AllArgsConstructor` without `@NoArgsConstructor`: the class then has no constructor Hibernate can call
 * - `@Builder` without both `@NoArgsConstructor` and `@AllArgsConstructor`: Hibernate needs the first, and the builder
 *   needs the second once the first exists
 */
class LombokEntityInspection : AbstractBaseJavaLocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
    val module = ModuleUtilCore.findModuleForPsiElement(holder.file) ?: return PsiElementVisitor.EMPTY_VISITOR
    if (!JpaGate.isJpaModule(module)) return PsiElementVisitor.EMPTY_VISITOR
    return object : JavaElementVisitor() {
      override fun visitClass(aClass: PsiClass) {
        if (!JpaModel.isEntity(aClass)) return
        val lazy = aClass.fields.filter { isLazyAssociation(it) }
        aClass.getAnnotation(DATA)?.let { data ->
          val shown = lazy.filter { !excluded(it, TO_STRING_EXCLUDE, null) || !excluded(it, EQUALS_EXCLUDE, null) }
          if (shown.isNotEmpty()) holder.registerProblem(data, PhantomJpaBundle.message("lombok.lazy.read", "@Data", names(shown)))
        }
        for ((annotationName, exclude) in listOf(EQUALS to EQUALS_EXCLUDE, TO_STRING to TO_STRING_EXCLUDE)) {
          val annotation = aClass.getAnnotation(annotationName) ?: continue
          val shown = lazy.filter { !excluded(it, exclude, annotation) }
          if (shown.isNotEmpty()) {
            holder.registerProblem(annotation, PhantomJpaBundle.message("lombok.lazy.read", "@" + annotationName.substringAfterLast('.'), names(shown)))
          }
        }
        aClass.getAnnotation(ALL_ARGS)?.let { allArgs ->
          if (aClass.getAnnotation(BUILDER) == null && aClass.getAnnotation(NO_ARGS) == null && !hasWrittenNoArgConstructor(aClass)) {
            holder.registerProblem(allArgs, PhantomJpaBundle.message("lombok.all.args"), from(AddAnnotationModCommandAction(NO_ARGS, aClass))!!)
          }
        }
        val builder = aClass.getAnnotation(BUILDER) ?: return
        val missing = listOf(NO_ARGS, ALL_ARGS).filter { aClass.getAnnotation(it) == null }
        if (missing.isEmpty()) return
        val fixes = missing.map { from(AddAnnotationModCommandAction(it, aClass))!! }.toTypedArray<LocalQuickFix>()
        holder.registerProblem(builder, PhantomJpaBundle.message("lombok.builder.constructors", missing.joinToString(" and ") { "@" + it.substringAfterLast('.') }), *fixes)
      }
    }
  }

  private fun hasWrittenNoArgConstructor(aClass: PsiClass): Boolean =
    aClass.constructors.any { it.parameterList.isEmpty && it !is com.intellij.psi.impl.light.LightElement }

  private fun names(fields: List<PsiField>): String = fields.joinToString(", ") { it.name }

  private fun isLazyAssociation(field: PsiField): Boolean {
    for (kind in JpaMapping.RELATIONS) {
      val annotation = JpaMapping.fqns(kind).firstNotNullOfOrNull { field.getAnnotation(it) } ?: continue
      val fetch = annotation.findDeclaredAttributeValue("fetch")?.text ?: return kind == "OneToMany" || kind == "ManyToMany"
      return fetch.contains("LAZY")
    }
    return false
  }

  /** Excluded field by field (`@ToString.Exclude`), by the class annotation's `exclude`, or by `onlyExplicitlyIncluded`. */
  private fun excluded(field: PsiField, fieldExclude: String, classAnnotation: PsiAnnotation?): Boolean {
    if (field.hasAnnotation(fieldExclude)) return true
    if (classAnnotation == null) return false
    if (AnnotationUtil.getBooleanAttributeValue(classAnnotation, "onlyExplicitlyIncluded") == true) return true
    val value = classAnnotation.findDeclaredAttributeValue("exclude") ?: return false
    val items = (value as? com.intellij.psi.PsiArrayInitializerMemberValue)?.initializers?.toList() ?: listOf(value)
    return items.any { (it as? com.intellij.psi.PsiLiteralExpression)?.value == field.name }
  }

  private companion object {
    const val DATA = "lombok.Data"
    const val EQUALS = "lombok.EqualsAndHashCode"
    const val TO_STRING = "lombok.ToString"
    const val EQUALS_EXCLUDE = "lombok.EqualsAndHashCode.Exclude"
    const val TO_STRING_EXCLUDE = "lombok.ToString.Exclude"
    const val BUILDER = "lombok.Builder"
    const val NO_ARGS = "lombok.NoArgsConstructor"
    const val ALL_ARGS = "lombok.AllArgsConstructor"
  }
}
