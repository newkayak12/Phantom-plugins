// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.validation.core

import com.intellij.DynamicBundle
import com.intellij.codeInsight.AnnotationUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.CommonClassNames
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypeParameter
import com.intellij.psi.PsiTypes
import com.intellij.psi.util.InheritanceUtil
import com.intellij.psi.util.PsiUtil
import com.intellij.java.library.JavaLibraryUtil
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.PropertyKey
import org.jetbrains.uast.UAnnotated
import org.jetbrains.uast.UAnnotation

@NonNls
private const val BUNDLE = "messages.PhantomValidationBundle"

object PhantomValidationBundle : DynamicBundle(BUNDLE) {
  @Nls
  fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String = getMessage(key, *params)
}

/** Bean Validation is on the module's classpath (jakarta or javax). */
object ValidationGate {
  private val MARKERS = listOf("jakarta.validation.constraints.NotNull", "javax.validation.constraints.NotNull")

  fun isValidationModule(module: Module): Boolean {
    if (module.isDisposed) return false
    if (MARKERS.any { JavaLibraryUtil.hasLibraryClass(module, it) }) return true
    // Light test fixtures declare the annotations as source stubs instead of a library.
    return ApplicationManager.getApplication().isUnitTestMode &&
           MARKERS.any { JavaPsiFacade.getInstance(module.project).findClass(it, module.getModuleWithDependenciesAndLibrariesScope(false)) != null }
  }
}

/** What the built-in constraints accept, and how to recognise constraints and `@Valid`. */
object Constraints {
  private val PACKAGES = listOf("jakarta.validation.constraints.", "javax.validation.constraints.", "org.hibernate.validator.constraints.")
  private val CONSTRAINT_META = listOf("jakarta.validation.Constraint", "javax.validation.Constraint")
  val VALID: List<String> = listOf("jakarta.validation.Valid", "javax.validation.Valid")

  enum class Kind(val key: String) { TEXT("type.kind.text"), SIZED("type.kind.sized"), NUMBER("type.kind.number"), TEMPORAL("type.kind.temporal"), BOOLEAN("type.kind.boolean") }

  /** Built-in constraints by simple name, for the type check. Hibernate's `@Length` reads text only. */
  private val KINDS = mapOf(
    "NotBlank" to Kind.TEXT, "Email" to Kind.TEXT, "Pattern" to Kind.TEXT, "Length" to Kind.TEXT,
    "Size" to Kind.SIZED, "NotEmpty" to Kind.SIZED,
    "Min" to Kind.NUMBER, "Max" to Kind.NUMBER, "DecimalMin" to Kind.NUMBER, "DecimalMax" to Kind.NUMBER,
    "Positive" to Kind.NUMBER, "PositiveOrZero" to Kind.NUMBER, "Negative" to Kind.NUMBER, "NegativeOrZero" to Kind.NUMBER, "Digits" to Kind.NUMBER,
    "Past" to Kind.TEMPORAL, "PastOrPresent" to Kind.TEMPORAL, "Future" to Kind.TEMPORAL, "FutureOrPresent" to Kind.TEMPORAL,
    "AssertTrue" to Kind.BOOLEAN, "AssertFalse" to Kind.BOOLEAN,
  )

  fun isBuiltIn(qualifiedName: String?): Boolean = qualifiedName != null && PACKAGES.any { qualifiedName.startsWith(it) }

  fun simpleName(qualifiedName: String): String = qualifiedName.substringAfterLast('.')

  fun kind(qualifiedName: String?): Kind? = if (isBuiltIn(qualifiedName)) KINDS[simpleName(qualifiedName!!)] else null

  /** A built-in constraint, or an annotation class meta-annotated with `@Constraint` (custom and composed constraints). */
  fun isConstraint(annotation: UAnnotation): Boolean {
    val name = annotation.qualifiedName ?: return false
    if (isBuiltIn(name)) return name.substringAfterLast('.').let { it != "List" }
    val annotationClass = annotation.resolve() ?: return false
    return isConstraintClass(annotationClass)
  }

  fun isConstraintClass(annotationClass: PsiClass): Boolean =
    isBuiltIn(annotationClass.qualifiedName) || AnnotationUtil.isAnnotated(annotationClass, CONSTRAINT_META, 0)

  fun isValid(annotation: UAnnotation): Boolean = annotation.qualifiedName in VALID

  fun constraints(owner: UAnnotated): List<UAnnotation> = owner.uAnnotations.filter { isConstraint(it) }

  /** Whether a class from the project's own sources declares a constraint or `@Valid` on one of its fields or getters. */
  fun hasConstrainedMembers(psiClass: PsiClass): Boolean {
    val names = (CONSTRAINT_META + VALID).toSet()
    return (psiClass.allFields.asSequence() + psiClass.allMethods.asSequence().filter { it.parameterList.isEmpty }).any { member ->
      member.annotations.any { a ->
        val qn = a.qualifiedName
        qn in names || isBuiltIn(qn) || a.nameReferenceElement?.resolve().let { it is PsiClass && isConstraintClass(it) }
      }
    }
  }

  fun isProjectSource(element: PsiElement): Boolean {
    val file = element.containingFile?.virtualFile ?: return false
    return ProjectFileIndex.getInstance(element.project).isInSourceContent(file)
  }

  /**
   * Whether [type] is a value [kind] validates. Unknown, generic, Object and Optional types are accepted: the
   * validator may unwrap them or a custom validator may exist, so only certain mismatches are reported.
   */
  fun accepts(kind: Kind, type: PsiType?): Boolean {
    if (type == null) return true
    if (type is PsiPrimitiveType) {
      // Hibernate Validator also takes float and double, as Number
      return when (kind) {
        Kind.NUMBER -> type != PsiTypes.booleanType() && type != PsiTypes.voidType()
        Kind.BOOLEAN -> type == PsiTypes.booleanType()
        else -> false
      }
    }
    if (type is PsiArrayType) return kind == Kind.SIZED
    val classType = type as? PsiClassType ?: return true
    val psiClass = classType.resolve() ?: return true
    if (psiClass is PsiTypeParameter) return true
    val qn = psiClass.qualifiedName ?: return true
    if (qn == CommonClassNames.JAVA_LANG_OBJECT || qn.startsWith("java.util.Optional") || qn == "java.io.Serializable" || qn == "java.lang.Comparable") return true
    fun isA(base: String) = InheritanceUtil.isInheritor(psiClass, base)
    val text = isA("java.lang.CharSequence")
    return when (kind) {
      Kind.TEXT -> text
      Kind.SIZED -> text || isA(CommonClassNames.JAVA_UTIL_COLLECTION) || isA(CommonClassNames.JAVA_UTIL_MAP)
      Kind.NUMBER -> text || isA("java.lang.Number") || qn == "javax.money.MonetaryAmount" || isA("javax.money.MonetaryAmount")
      Kind.TEMPORAL -> isA("java.util.Date") || isA("java.util.Calendar") || isA("java.time.temporal.TemporalAccessor") ||
                       isA("java.time.chrono.ChronoLocalDate") || isA("java.time.chrono.ChronoLocalDateTime")
      Kind.BOOLEAN -> qn == CommonClassNames.JAVA_LANG_BOOLEAN
    }
  }

  fun presentable(type: PsiType): String = PsiUtil.resolveClassInClassTypeOnly(type)?.name ?: type.presentableText
}
