// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.springsecurity.core

import com.intellij.DynamicBundle
import com.intellij.codeInsight.AnnotationUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.java.library.JavaLibraryUtil
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.PropertyKey

@NonNls
private const val BUNDLE = "messages.PhantomSpringSecurityBundle"

object PhantomSpringSecurityBundle : DynamicBundle(BUNDLE) {
  @Nls
  fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String = getMessage(key, *params)
}

/** Spring Security's web module is on the module's classpath. */
object SecurityGate {
  const val FILTER_CHAIN = "org.springframework.security.web.SecurityFilterChain"

  fun isSecurityModule(module: Module): Boolean {
    if (module.isDisposed) return false
    if (JavaLibraryUtil.hasLibraryClass(module, FILTER_CHAIN)) return true
    // Light test fixtures declare the classes as source stubs instead of a library.
    return ApplicationManager.getApplication().isUnitTestMode &&
           JavaPsiFacade.getInstance(module.project).findClass(FILTER_CHAIN, module.getModuleWithDependenciesAndLibrariesScope(false)) != null
  }

  fun isSecurityElement(element: PsiElement): Boolean = ModuleUtilCore.findModuleForPsiElement(element)?.let(::isSecurityModule) == true
}

/**
 * One authorizeHttpRequests rule: the matcher call (`requestMatchers(...)` or `anyRequest()`) and the access call after it.
 * [httpMethod] is the HttpMethod argument when there is one; [patterns] are the path arguments as written or evaluated.
 */
data class AccessRule(val matcher: PsiMethodCallExpression, val httpMethod: String?, val patterns: List<String>, val access: String) {
  val isAnyRequest: Boolean get() = matcher.methodExpression.referenceName == ANY_REQUEST
}

/** A SecurityFilterChain bean method; [order] is its @Order value, null when Spring uses the lowest precedence. */
data class FilterChain(val method: PsiMethod, val order: Int?, val securityMatchers: List<String>, val rules: List<AccessRule>) {
  val effectiveOrder: Int get() = order ?: Int.MAX_VALUE
  val matchesEveryRequest: Boolean get() = securityMatchers.isEmpty()
}

internal const val ANY_REQUEST = "anyRequest"
private val REQUEST_MATCHERS = setOf("requestMatchers", "antMatchers", "mvcMatchers", "regexMatchers")
private val ACCESS = setOf(
  "permitAll", "denyAll", "authenticated", "fullyAuthenticated", "anonymous", "rememberMe",
  "hasRole", "hasAnyRole", "hasAuthority", "hasAnyAuthority", "hasIpAddress", "access",
)
private const val BEAN = "org.springframework.context.annotation.Bean"
private const val ORDER = "org.springframework.core.annotation.Order"

/**
 * Reads SecurityFilterChain bean methods from their Java source, the way HttpSecurity is usually configured:
 * `securityMatcher(...)` and the lambda passed to `authorizeHttpRequests`. Builders assembled in other methods are not followed.
 */
object FilterChains {
  fun isFilterChainBean(method: PsiMethod): Boolean =
    method.returnType?.canonicalText == SecurityGate.FILTER_CHAIN && AnnotationUtil.isAnnotated(method, BEAN, 0)

  fun of(method: PsiMethod): FilterChain? {
    if (!isFilterChainBean(method)) return null
    return CachedValuesManager.getCachedValue(method) {
      CachedValueProvider.Result.create(parse(method), PsiModificationTracker.getInstance(method.project))
    }
  }

  /** The project's chains in the order Spring Security tries them: by @Order, then by name for equal orders. */
  fun all(project: Project): List<FilterChain> = CachedValuesManager.getManager(project).getCachedValue(project) {
    val bean = JavaPsiFacade.getInstance(project).findClass(BEAN, GlobalSearchScope.allScope(project))
    val chains = if (bean == null) emptyList() else {
      AnnotatedElementsSearch.searchPsiMethods(bean, GlobalSearchScope.projectScope(project)).findAll().mapNotNull(::of)
        .sortedWith(compareBy<FilterChain> { it.effectiveOrder }.thenBy { it.method.name })
    }
    CachedValueProvider.Result.create(chains, PsiModificationTracker.getInstance(project))
  }

  private fun parse(method: PsiMethod): FilterChain {
    val calls = PsiTreeUtil.findChildrenOfType(method.body, PsiMethodCallExpression::class.java).sortedBy { it.nameOffset }
    val securityMatchers = calls.filter { it.methodExpression.referenceName == "securityMatcher" }
      .flatMap { call -> call.argumentList.expressions.map(::text) }
    val rules = calls.mapNotNull(::rule).sortedBy { it.matcher.nameOffset }
    return FilterChain(method, order(method), securityMatchers, rules)
  }

  /** [call] is an access call such as `hasRole("ADMIN")` whose qualifier is a matcher call. */
  private fun rule(call: PsiMethodCallExpression): AccessRule? {
    val name = call.methodExpression.referenceName ?: return null
    if (name !in ACCESS) return null
    val matcher = call.methodExpression.qualifierExpression as? PsiMethodCallExpression ?: return null
    val matcherName = matcher.methodExpression.referenceName
    if (matcherName != ANY_REQUEST && matcherName !in REQUEST_MATCHERS) return null
    val args = matcher.argumentList.expressions.toList()
    val httpMethod = args.firstOrNull()?.let(::httpMethod)
    val patterns = (if (httpMethod != null) args.drop(1) else args).map(::text)
    val access = name + if (call.argumentList.isEmpty) "()" else call.argumentList.text
    return AccessRule(matcher, httpMethod, patterns, access)
  }

  /** A call chain starts where its first qualifier starts, so calls in one chain are ordered by their method names. */
  private val PsiMethodCallExpression.nameOffset: Int get() = methodExpression.referenceNameElement?.textOffset ?: textOffset

  private fun httpMethod(expression: PsiExpression): String? {
    val reference = expression as? PsiReferenceExpression ?: return null
    val qualifier = reference.qualifierExpression as? PsiReferenceExpression
    return reference.referenceName.takeIf { qualifier?.referenceName == "HttpMethod" }
  }

  private fun text(expression: PsiExpression): String =
    JavaPsiFacade.getInstance(expression.project).constantEvaluationHelper.computeConstantExpression(expression) as? String
    ?: expression.text

  private fun order(method: PsiMethod): Int? {
    val value = AnnotationUtil.findAnnotation(method, ORDER)?.findAttributeValue("value") ?: return null
    return JavaPsiFacade.getInstance(method.project).constantEvaluationHelper.computeConstantExpression(value) as? Int
  }
}
