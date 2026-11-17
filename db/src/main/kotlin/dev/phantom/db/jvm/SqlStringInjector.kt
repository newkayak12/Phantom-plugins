// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.jvm

import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.PsiMethod
import dev.phantom.db.core.SqlLanguage
import org.jetbrains.uast.*

/**
 * SQL highlighting and completion inside Java and Kotlin strings that are SQL: `@Query(nativeQuery = true)` and
 * `@NativeQuery` in Spring Data JPA, and the SQL argument of `JdbcTemplate`, `NamedParameterJdbcTemplate` and
 * `JdbcClient.sql(...)`. JPQL in a plain `@Query` is left alone. A string with `${...}` templates or built from
 * several pieces is not injected.
 */
class SqlStringInjector : MultiHostInjector {
  override fun elementsToInjectIn(): List<Class<out PsiElement>> = listOf(PsiLanguageInjectionHost::class.java)

  override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
    val host = context as? PsiLanguageInjectionHost ?: return
    if (!host.isValidHost) return
    // cheap text checks before UAST: most strings in a file are not SQL
    val text = host.text
    if (text.length < 8 || text.contains("\${") && !text.contains("\\\${")) return
    val expression = context.toUElement() as? UExpression ?: return
    if (!SqlStrings.isSql(expression)) return
    registrar.startInjecting(SqlLanguage).addPlace(null, null, host, ElementManipulators.getValueTextRange(host)).doneInjecting()
  }
}

object SqlStrings {
  private const val QUERY = "org.springframework.data.jpa.repository.Query"
  private const val NATIVE_QUERY = "org.springframework.data.jpa.repository.NativeQuery"
  private val JDBC_CLASSES = setOf(
    "org.springframework.jdbc.core.JdbcTemplate",
    "org.springframework.jdbc.core.JdbcOperations",
    "org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate",
    "org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations",
  )
  private val JDBC_METHODS = setOf("query", "queryForObject", "queryForList", "queryForMap", "queryForRowSet", "queryForStream",
                                   "update", "batchUpdate", "execute")
  private const val JDBC_CLIENT = "org.springframework.jdbc.core.simple.JdbcClient"

  fun isSql(expression: UExpression): Boolean {
    annotationOf(expression)?.let { (annotation, attribute) ->
      if (attribute != "value") return false
      // while Kotlin is still resolving (or the library is missing) the name may not resolve: fall back to the short name
      val name = annotation.qualifiedName ?: annotation.sourcePsi?.text?.removePrefix("@")?.substringBefore('(')?.trim()
      return when (name) {
        NATIVE_QUERY, "NativeQuery" -> true
        QUERY, "Query" -> annotation.findAttributeValue("nativeQuery")?.evaluate() == true
        else -> false
      }
    }
    val call = expression.uastParent as? UCallExpression ?: return false
    // UAST wrappers are not cached, so compare the PSI behind them
    if (call.valueArguments.firstOrNull()?.let { unwrap(it).sourcePsi } != expression.sourcePsi) return false
    val name = call.methodName ?: return false
    if (name !in JDBC_METHODS && name != "sql") return false
    val method = call.resolve() ?: return false
    val owner = method.containingClass?.qualifiedName ?: return false
    if (name == "sql") return owner == JDBC_CLIENT
    return owner in JDBC_CLASSES || isJdbcOwner(method)
  }

  /** A subclass of `JdbcTemplate` still declares the methods there. */
  private fun isJdbcOwner(method: PsiMethod): Boolean =
    method.findSuperMethods().any { it.containingClass?.qualifiedName in JDBC_CLASSES }

  private fun unwrap(e: UExpression): UExpression = if (e is UParenthesizedExpression) unwrap(e.expression) else e

  /** The annotation the string is a value of, and the attribute name: `value` for `@Query("...")`. */
  private fun annotationOf(expression: UExpression): Pair<UAnnotation, String>? {
    var current: UElement = expression
    var parent = current.uastParent
    // Kotlin wraps attribute values in a named-argument expression, Java in a name-value pair
    while (parent != null && parent !is UAnnotation) {
      if (parent is UCallExpression || parent is UMethod || parent is UClass) return null
      current = parent
      parent = parent.uastParent
    }
    val annotation = parent as? UAnnotation ?: return null
    val psi = current.sourcePsi
    val attribute = annotation.attributeValues.firstOrNull { it.sourcePsi == psi || it.expression.sourcePsi == expression.sourcePsi }
    return annotation to (attribute?.name ?: "value")
  }
}
