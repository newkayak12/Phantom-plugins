// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.validation.core

import com.intellij.codeInsight.AnnotationUtil
import com.intellij.codeInsight.MetaAnnotationUtil
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInspection.IntentionWrapper
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.lang.jvm.JvmModifiersOwner
import com.intellij.lang.jvm.actions.annotationRequest
import com.intellij.lang.jvm.actions.createAddAnnotationActions
import com.intellij.openapi.project.Project
import com.intellij.psi.CommonClassNames
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiType
import com.intellij.psi.util.InheritanceUtil
import com.intellij.psi.util.PsiUtil
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UField
import org.jetbrains.uast.toUElement

/**
 * Kotlin puts an annotation on a constructor property's parameter when the annotation may target parameters, which every
 * built-in constraint does. `@Valid` on a request body then validates the object's fields and getters and never sees the
 * constraint. `@field:` (or `@get:`) puts it where the validator reads it.
 */
class KotlinConstraintTargetInspection : ValidationInspectionBase() {
  private val target = Regex("""^@\s*(\w+)\s*:""")

  override fun check(node: UClass, holder: ProblemsHolder) {
    if (node.sourcePsi?.language?.id != "kotlin") return
    for (field in node.fields) {
      if (!field.isKotlinConstructorProperty()) continue
      val source = field.sourcePsi ?: continue
      if (source.node.getChildren(null).none { it.text == "val" || it.text == "var" }) continue
      val modifiers = source.children.firstOrNull { it.javaClass.simpleName.endsWith("ModifierList") } ?: continue
      for (entry in modifiers.children.filter { it.javaClass.simpleName == "KtAnnotationEntry" }) {
        if (target.containsMatchIn(entry.text)) continue
        val annotation = entry.toUElement() as? UAnnotation ?: continue
        val annotationClass = annotation.resolve() ?: continue
        val valid = annotation.qualifiedName in Constraints.VALID
        if (!valid && !Constraints.isConstraintClass(annotationClass)) continue
        if (!targetsParameters(annotationClass)) continue
        holder.registerProblem(entry, PhantomValidationBundle.message("target.param", annotationClass.name ?: ""), FieldTargetFix())
      }
    }
  }

  /** Without a declared target a Java annotation applies to parameters too; a Kotlin one lists VALUE_PARAMETER. */
  private fun targetsParameters(annotationClass: PsiClass): Boolean {
    val javaTarget = annotationClass.getAnnotation("java.lang.annotation.Target")
    // PARAMETER as a whole word: TYPE_PARAMETER is a different target
    if (javaTarget != null) return javaTarget.findAttributeValue("value")?.text?.let { PARAMETER.containsMatchIn(it) } == true
    val kotlinTarget = annotationClass.getAnnotation("kotlin.annotation.Target") ?: return true
    return kotlinTarget.text.contains("VALUE_PARAMETER")
  }

  private companion object {
    val PARAMETER = Regex("""\bPARAMETER\b""")
  }

  private class FieldTargetFix : LocalQuickFix {
    override fun getFamilyName(): String = PhantomValidationBundle.message("target.fix")

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
      val entry = descriptor.psiElement ?: return
      val document = PsiDocumentManager.getInstance(project).getDocument(entry.containingFile) ?: return
      val at = entry.textRange.startOffset + 1
      if (document.charsSequence.getOrNull(at - 1) != '@') return
      document.insertString(at, "field:")
      PsiDocumentManager.getInstance(project).commitDocument(document)
    }
  }
}

/**
 * A field of a validated class whose type (or element type) is a class of the project that declares constraints, but
 * without `@Valid`: validation stops at the field and the nested constraints are never checked.
 */
class MissingValidInspection : ValidationInspectionBase() {
  private val validMark = Regex("""@(\w+:)?(jakarta\.validation\.|javax\.validation\.)?Valid\b""")

  override fun check(node: UClass, holder: ProblemsHolder) {
    val psiClass = node.javaPsi
    if (psiClass.isEnum || psiClass.isInterface || psiClass.isAnnotationType) return
    // only classes that are validated themselves: one of their fields or getters carries a constraint
    if (!Constraints.hasConstrainedMembers(psiClass)) return
    val kotlinParams = node.fields.filter { it.isKotlinConstructorProperty() }.mapNotNull { it.sourcePsi }.toSet()
    for (field in node.fields) {
      if (field.isStatic || field.name.endsWith("\$delegate")) continue
      val nested = nestedClass(field.type) ?: continue
      if (nested == psiClass || !Constraints.isProjectSource(nested) || !Constraints.hasConstrainedMembers(nested)) continue
      if (hasValid(field, kotlinParams)) continue
      val anchor = field.uastAnchor?.sourcePsi ?: continue
      holder.registerProblem(anchor, PhantomValidationBundle.message("valid.missing", nested.name ?: ""), *addValidFix(field, holder))
    }
  }

  /** `@Valid` on the field itself, on its getter, or (Kotlin) written on the property in any form (`@Valid`, `@field:Valid`, `@get:Valid`). */
  private fun hasValid(field: UField, kotlinParams: Set<PsiElement>): Boolean {
    if (field.uAnnotations.any { Constraints.isValid(it) }) return true
    // container element form: List<@Valid Address>
    if (validMark.containsMatchIn(field.typeReference?.sourcePsi?.text.orEmpty())) return true
    val psiField = field.javaPsi as? com.intellij.psi.PsiField ?: return false
    if (AnnotationUtil.isAnnotated(psiField, Constraints.VALID, 0)) return true
    val source = field.sourcePsi
    if (source != null && source in kotlinParams || source?.javaClass?.simpleName == "KtProperty") {
      return kotlinAnnotations(source!!).any { Constraints.isValid(it) }
    }
    return false
  }

  /** The annotations written on a Kotlin property or constructor property, whatever their use-site target, resolved through UAST (import aliases too). */
  private fun kotlinAnnotations(declaration: PsiElement): List<UAnnotation> {
    val modifiers = declaration.children.firstOrNull { it.javaClass.simpleName.endsWith("ModifierList") } ?: return emptyList()
    return modifiers.children.filter { it.javaClass.simpleName == "KtAnnotationEntry" }.mapNotNull { it.toUElement() as? UAnnotation }
  }

  /** The class a field holds: itself, or the element type of an array, Iterable, Optional, or a Map's values. */
  private fun nestedClass(type: PsiType): PsiClass? {
    if (type is PsiArrayType) return nestedClass(type.componentType)
    val classType = type as? PsiClassType ?: return null
    val psiClass = classType.resolve() ?: return null
    val qn = psiClass.qualifiedName ?: return null
    if (qn.startsWith("java.") || qn.startsWith("kotlin.") || psiClass.isEnum) {
      val arguments = classType.parameters
      return when {
        InheritanceUtil.isInheritor(psiClass, CommonClassNames.JAVA_LANG_ITERABLE) || qn == "java.util.Optional" ->
          arguments.firstOrNull()?.let { PsiUtil.resolveClassInClassTypeOnly(it) }
        InheritanceUtil.isInheritor(psiClass, CommonClassNames.JAVA_UTIL_MAP) -> arguments.getOrNull(1)?.let { PsiUtil.resolveClassInClassTypeOnly(it) }
        else -> null
      }?.takeUnless { it.qualifiedName?.startsWith("java.") == true || it.isEnum }
    }
    return psiClass
  }

  /** Java only: Kotlin constructor properties need `@field:Valid`, which the generic annotation request cannot express. */
  private fun addValidFix(field: UField, holder: ProblemsHolder): Array<LocalQuickFix> {
    if (field.isKotlinConstructorProperty()) return LocalQuickFix.EMPTY_ARRAY
    val owner = field.javaPsi as? JvmModifiersOwner ?: return LocalQuickFix.EMPTY_ARRAY
    val namespace = if (JavaPsiFacade.getInstance(holder.project).findClass("jakarta.validation.Valid", field.javaPsi!!.resolveScope) != null)
      "jakarta.validation" else "javax.validation"
    val actions: List<IntentionAction> = createAddAnnotationActions(owner, annotationRequest("$namespace.Valid"))
    return IntentionWrapper.wrapToQuickFixes(actions, holder.file).toTypedArray()
  }
}

/**
 * A Spring bean (a service, component or repository, also through a stereotype of the project's own) with constraints (or
 * `@Valid`) on method parameters or return values, but no `@Validated` on the class or its supertypes: Spring applies
 * method validation only to `@Validated` beans.
 * Controllers validate `@Valid` arguments by themselves. Since Spring Framework 6.1 they also apply constraints on
 * parameters (`@RequestParam @Min(1)`) without `@Validated`; with older versions those constraints are reported.
 */
class MissingValidatedInspection : ValidationInspectionBase() {
  private val stereotypes = listOf("org.springframework.stereotype.Service", "org.springframework.stereotype.Component", "org.springframework.stereotype.Repository")
  private val component = listOf("org.springframework.stereotype.Component")
  private val controllers = listOf("org.springframework.stereotype.Controller", "org.springframework.web.bind.annotation.RestController")
  private val validated = "org.springframework.validation.annotation.Validated"
  // Added in Spring Framework 6.1 together with built-in method validation for controllers.
  private val handlerMethodValidator = "org.springframework.web.method.annotation.HandlerMethodValidator"

  override fun check(node: UClass, holder: ProblemsHolder) {
    val psiClass = node.javaPsi
    if (psiClass.isInterface || psiClass.isAnnotationType) return
    val controller = AnnotationUtil.isAnnotated(psiClass, controllers, 0) || MetaAnnotationUtil.isMetaAnnotated(psiClass, controllers)
    if (!controller && !AnnotationUtil.isAnnotated(psiClass, stereotypes, 0) && !MetaAnnotationUtil.isMetaAnnotated(psiClass, component)) return
    val scope = psiClass.resolveScope
    val facade = JavaPsiFacade.getInstance(holder.project)
    if (facade.findClass(validated, scope) == null) return
    if (controller && facade.findClass(handlerMethodValidator, scope) != null) return
    if (AnnotationUtil.isAnnotated(psiClass, validated, AnnotationUtil.CHECK_HIERARCHY)) return
    // a controller's @Valid arguments are validated by Spring MVC's argument resolvers, @Validated or not
    val constrained = node.methods.any { method ->
      !method.isConstructor && !method.javaPsi.hasModifierProperty(PsiModifier.PRIVATE) &&
      (method.uastParameters.any { p -> p.uAnnotations.any { Constraints.isConstraint(it) || !controller && Constraints.isValid(it) } } ||
       method.uAnnotations.any { Constraints.isConstraint(it) })
    }
    if (!constrained) return
    val anchor = node.uastAnchor?.sourcePsi ?: return
    val owner = psiClass as? JvmModifiersOwner
    val fixes = if (owner == null) LocalQuickFix.EMPTY_ARRAY
    else IntentionWrapper.wrapToQuickFixes(createAddAnnotationActions(owner, annotationRequest(validated)), holder.file).toTypedArray()
    holder.registerProblem(anchor, PhantomValidationBundle.message("validated.missing", psiClass.name ?: ""), *fixes)
  }
}
