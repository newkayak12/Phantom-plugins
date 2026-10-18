// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.jpa.core

import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.util.TextRange
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import org.jetbrains.uast.*

/**
 * Injects JPQL into `@Query`/`@NamedQuery` strings and `EntityManager.createQuery` arguments, including the common Kotlin style where the annotation names a
 * `const val` holding a template. Most strings are rejected by a text check before any UAST conversion.
 */
class JpqlQueryInjector : MultiHostInjector {
  override fun elementsToInjectIn(): List<Class<out PsiElement>> = listOf(PsiLanguageInjectionHost::class.java)

  override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
    val host = context as? PsiLanguageInjectionHost ?: return
    val language = host.language.id
    if (language != "JAVA" && language != "kotlin") return
    if (!host.isValidHost) return
    val text = host.text
    if (text.length < MIN_LENGTH || !QUERY_WORD.containsMatchIn(text)) return
    val module = ModuleUtilCore.findModuleForPsiElement(host) ?: return
    if (!JpaGate.isJpaModule(module)) return

    val expression = host.toUElement() as? UExpression ?: return
    if (!isQuery(expression, host)) return
    val places = places(expression, host)
    if (places.isEmpty()) return
    registrar.startInjecting(JpqlLanguage)
    for (place in places) registrar.addPlace(place.prefix, null, host, place.range)
    registrar.doneInjecting()
  }

  private class Place(val prefix: String?, val range: TextRange)

  /** Literal parts of the string; each interpolation (`$CONDITION`) becomes a placeholder token. */
  private fun places(expression: UExpression, host: PsiLanguageInjectionHost): List<Place> {
    val polyadic = expression as? UPolyadicExpression
    if (polyadic == null || polyadic.operands.size <= 1) return listOf(Place(null, ElementManipulators.getValueTextRange(host)))
    val hostStart = host.textRange.startOffset
    val result = ArrayList<Place>()
    var pending: String? = null
    for (operand in polyadic.operands) {
      val psi = operand.sourcePsi
      if (operand is ULiteralExpression && psi != null && psi.textRange in host.textRange) {
        result.add(Place(pending, psi.textRange.shiftLeft(hostStart)))
        pending = null
      }
      else {
        pending = (pending ?: "") + " ${JpqlTokens.PLACEHOLDER_TEXT} "
      }
    }
    return result
  }

  private fun isQuery(expression: UExpression, host: PsiLanguageInjectionHost): Boolean {
    var parent = expression.uastParent
    while (parent is UParenthesizedExpression) parent = parent.uastParent
    return when (parent) {
      is UNamedExpression -> parent.name in QUERY_ATTRIBUTES && (parent.uastParent as? UAnnotation)?.let(::isQueryAnnotation) == true
      is UAnnotation -> isQueryAnnotation(parent)
      is UField, is ULocalVariable -> {
        val variable = parent as UVariable
        variable.uastInitializer?.sourcePsi == host && variable.name in queryConstantNames(host.containingFile)
      }
      is UCallExpression -> isQueryCall(parent, expression)
      else -> false
    }
  }

  /** `entityManager.createQuery("select ...")`: the first argument of EntityManager's string `createQuery`. */
  private fun isQueryCall(call: UCallExpression, argument: UExpression): Boolean {
    if (call.methodName != "createQuery") return false
    if (com.intellij.openapi.project.DumbService.isDumb(argument.sourcePsi?.project ?: return false)) return false
    if (call.valueArguments.firstOrNull()?.sourcePsi != argument.sourcePsi) return false
    val owner = call.resolve()?.containingClass?.qualifiedName ?: return false
    return owner in ENTITY_MANAGERS
  }

  private fun isQueryAnnotation(annotation: UAnnotation): Boolean {
    if (annotation.qualifiedName !in QUERY_ANNOTATIONS) return false
    return annotation.findDeclaredAttributeValue("nativeQuery")?.evaluate() != true
  }

  private companion object {
    const val MIN_LENGTH = 8
    val QUERY_WORD = Regex("""(?i)\b(select|from|update|delete)\b""")
    val ENTITY_MANAGERS = setOf("jakarta.persistence.EntityManager", "javax.persistence.EntityManager")
    val QUERY_ATTRIBUTES = setOf(null, "value", "query", "countQuery")
    val QUERY_ANNOTATIONS = setOf(
      "org.springframework.data.jpa.repository.Query",
      "jakarta.persistence.NamedQuery",
      "javax.persistence.NamedQuery",
    )

    /** `@Query(LOCK_ACTIVE_OCCUPANCY_SQL)`, `@Query(value = X, countQuery = Y)`: constant names used in the same file. */
    val CONSTANT_USE = Regex("""@(?:[\w.]+\.)?(?:Query|NamedQuery)\s*\(([^)]*)\)""")
    val NAME = Regex("""(?:^|[\s,(=])(?:[A-Za-z_]\w*\.)*([A-Za-z_]\w*)\s*(?=$|[,)])""")

    fun queryConstantNames(file: PsiFile): Set<String> = CachedValuesManager.getCachedValue(file) {
      val names = HashSet<String>()
      for (use in CONSTANT_USE.findAll(file.text)) {
        for (arg in use.groupValues[1].split(',')) {
          val value = arg.substringAfter('=').trim()
          if (value.isNotEmpty() && !value.startsWith('"')) NAME.find(" $value")?.groupValues?.get(1)?.let(names::add)
        }
      }
      CachedValueProvider.Result.create(names as Set<String>, file)
    }
  }
}
