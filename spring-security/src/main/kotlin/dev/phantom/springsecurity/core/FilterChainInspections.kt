// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.springsecurity.core

import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.JavaElementVisitor
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiMethod

private fun PsiElementVisitor.onlyInSecurity(holder: ProblemsHolder): PsiElementVisitor =
  if (SecurityGate.isSecurityElement(holder.file)) this else PsiElementVisitor.EMPTY_VISITOR

/** Spring Security's AbstractRequestMatcherRegistry throws "Can't configure requestMatchers after anyRequest" at startup. */
class RuleAfterAnyRequestInspection : AbstractBaseJavaLocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor = object : JavaElementVisitor() {
    override fun visitMethod(method: PsiMethod) {
      val rules = FilterChains.of(method)?.rules ?: return
      val anyRequest = rules.indexOfFirst { it.isAnyRequest }
      if (anyRequest < 0) return
      rules.drop(anyRequest + 1).filterNot { it.isAnyRequest }.forEach {
        holder.registerProblem(it.matcher.methodExpression.referenceNameElement ?: it.matcher, PhantomSpringSecurityBundle.message("after.any.problem"))
      }
    }
  }.onlyInSecurity(holder)
}

/**
 * WebSecurity rejects a chain ordered after one that matches any request ("A filter chain that matches any request
 * has already been configured"). Only strictly lower orders are reported: equal orders depend on bean registration.
 */
class ShadowedFilterChainInspection : AbstractBaseJavaLocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor = object : JavaElementVisitor() {
    override fun visitMethod(method: PsiMethod) {
      val chain = FilterChains.of(method) ?: return
      val shadowing = FilterChains.all(method.project).firstOrNull {
        it.method != method && it.matchesEveryRequest && it.effectiveOrder < chain.effectiveOrder
      } ?: return
      val order = shadowing.order?.toString() ?: PhantomSpringSecurityBundle.message("marker.order.default")
      holder.registerProblem(method.nameIdentifier ?: return, PhantomSpringSecurityBundle.message("shadowed.problem", shadowing.method.name, order))
    }
  }.onlyInSecurity(holder)
}
