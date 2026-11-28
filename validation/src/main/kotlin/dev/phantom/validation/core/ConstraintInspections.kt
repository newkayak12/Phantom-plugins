// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.validation.core

import com.intellij.codeInspection.AbstractBaseUastLocalInspectionTool
import com.intellij.codeInspection.LocalInspectionToolSession
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypes
import com.intellij.uast.UastHintedVisitorAdapter
import org.jetbrains.uast.UAnnotated
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UField
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UParameter
import org.jetbrains.uast.visitor.AbstractUastNonRecursiveVisitor
import java.math.BigDecimal

/** A field, getter or parameter, with the type its constraints validate. */
internal class Constrained(val owner: UAnnotated, val type: PsiType?, val constraints: List<UAnnotation>)

/**
 * The constrained elements of a class: fields, getters (return value) and method parameters. A Kotlin constructor
 * property shows up both as a field and as a constructor parameter; each annotation is reported once, wherever its
 * use-site target puts it.
 */
internal fun constrainedElements(node: UClass): List<Constrained> {
  val seen = HashSet<PsiElement>()
  val result = ArrayList<Constrained>()
  fun add(owner: UAnnotated, type: PsiType?) {
    val constraints = Constraints.constraints(owner).filter { a -> a.sourcePsi?.let(seen::add) ?: true }
    if (constraints.isNotEmpty()) result += Constrained(owner, type, constraints)
  }
  for (field in node.fields) add(field, field.type)
  for (method in node.methods) {
    if (method.returnType != null && method.returnType != PsiTypes.voidType()) add(method, method.returnType)
    for (parameter in method.uastParameters) add(parameter, parameter.type)
  }
  return result
}

/** Shared gate: the file's module has Bean Validation; one visitor per class. */
abstract class ValidationInspectionBase : AbstractBaseUastLocalInspectionTool() {
  override fun isAvailableForFile(file: PsiFile): Boolean = !GhostMode.enabled && super.isAvailableForFile(file)
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    val module = ModuleUtilCore.findModuleForPsiElement(holder.file) ?: return PsiElementVisitor.EMPTY_VISITOR
    if (!ValidationGate.isValidationModule(module)) return PsiElementVisitor.EMPTY_VISITOR
    return UastHintedVisitorAdapter.create(holder.file.language, object : AbstractUastNonRecursiveVisitor() {
      override fun visitClass(node: UClass): Boolean {
        check(node, holder)
        return true
      }
    }, arrayOf(UClass::class.java), true)
  }

  protected abstract fun check(node: UClass, holder: ProblemsHolder)
}

/**
 * Bounds no value can satisfy: `@Min` above `@Max`, `@DecimalMin` above `@DecimalMax`, `@Size`/`@Length` with min above
 * max, and negative sizes (which the validator rejects with an exception when it initialises).
 */
class ConstraintBoundsInspection : ValidationInspectionBase() {
  override fun check(node: UClass, holder: ProblemsHolder) {
    for (element in constrainedElements(node)) {
      val byName = element.constraints.filter { Constraints.isBuiltIn(it.qualifiedName) }.associateBy { Constraints.simpleName(it.qualifiedName!!) }
      pair(holder, byName["Min"], byName["Max"], ::number)
      pair(holder, byName["DecimalMin"], byName["DecimalMax"], ::decimal)
      for (name in listOf("Size", "Length")) {
        val annotation = byName[name] ?: continue
        val min = number(annotation, "min")
        val max = number(annotation, "max")
        val anchor = annotation.sourcePsi ?: continue
        when {
          min != null && min < BigDecimal.ZERO -> holder.registerProblem(anchor, PhantomValidationBundle.message("bounds.negative", name, "min $min"))
          max != null && max < BigDecimal.ZERO -> holder.registerProblem(anchor, PhantomValidationBundle.message("bounds.negative", name, "max $max"))
          min != null && max != null && min > max -> holder.registerProblem(anchor, PhantomValidationBundle.message("bounds.size", name, min, max))
        }
      }
    }
  }

  private fun pair(holder: ProblemsHolder, low: UAnnotation?, high: UAnnotation?, read: (UAnnotation, String) -> BigDecimal?) {
    if (low == null || high == null) return
    val min = read(low, "value") ?: return
    val max = read(high, "value") ?: return
    if (min <= max) return
    val lowName = Constraints.simpleName(low.qualifiedName!!)
    val highName = Constraints.simpleName(high.qualifiedName!!)
    holder.registerProblem(low.sourcePsi ?: return, PhantomValidationBundle.message("bounds.min.max", lowName, min.toPlainString(), highName, max.toPlainString()))
  }

  private fun number(annotation: UAnnotation, attribute: String): BigDecimal? =
    (annotation.findDeclaredAttributeValue(attribute)?.evaluate() as? Number)?.let { BigDecimal(it.toString()) }

  private fun decimal(annotation: UAnnotation, attribute: String): BigDecimal? =
    (annotation.findDeclaredAttributeValue(attribute)?.evaluate() as? String)?.trim()?.let { runCatching { BigDecimal(it) }.getOrNull() }
}

/**
 * A built-in constraint on a type it has no validator for, such as `@NotBlank` on an `Int` or `@Past` on a `String`.
 * Validation then fails at runtime with `UnexpectedTypeException` instead of reporting a violation.
 */
class ConstraintTypeInspection : ValidationInspectionBase() {
  override fun check(node: UClass, holder: ProblemsHolder) {
    for (element in constrainedElements(node)) {
      val type = element.type ?: continue
      for (annotation in element.constraints) {
        val kind = Constraints.kind(annotation.qualifiedName) ?: continue
        if (Constraints.accepts(kind, type)) continue
        holder.registerProblem(annotation.sourcePsi ?: continue, PhantomValidationBundle.message(
          "type.mismatch", Constraints.simpleName(annotation.qualifiedName!!), Constraints.presentable(type), PhantomValidationBundle.message(kind.key)))
      }
    }
  }
}

internal fun UField.isKotlinConstructorProperty(): Boolean = sourcePsi?.javaClass?.simpleName == "KtParameter"
