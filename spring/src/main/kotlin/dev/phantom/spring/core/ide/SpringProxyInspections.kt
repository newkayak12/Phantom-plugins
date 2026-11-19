// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring.core.ide

import com.intellij.codeInspection.*
import com.intellij.lang.java.JavaLanguage
import com.intellij.psi.*
import com.intellij.psi.util.InheritanceUtil
import com.intellij.uast.UastHintedVisitorAdapter
import dev.phantom.spring.core.*
import org.jetbrains.uast.*
import org.jetbrains.uast.visitor.AbstractUastNonRecursiveVisitor

/**
 * A call from a bean into its own `@Transactional`, `@Async`, `@Cacheable`… method goes to the object, not to the
 * proxy, so the annotation does nothing for that call. A transactional caller calling a plain-propagation
 * `@Transactional` method is fine (it joins the same transaction) and is not reported.
 */
class SpringSelfInvocationInspection : AbstractBaseUastLocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    if (!SpringProjectGate.isSpringProject(holder.project)) return PsiElementVisitor.EMPTY_VISITOR
    return UastHintedVisitorAdapter.create(holder.file.language, object : AbstractUastNonRecursiveVisitor() {
      override fun visitCallExpression(node: UCallExpression): Boolean {
        if (node.kind != UastCallKind.METHOD_CALL) return true
        val receiver = node.receiver
        if (receiver != null && receiver !is UThisExpression) return true
        val callee = node.resolve() ?: return true
        if (SpringProxies.methodAdvice(callee) == null) return true
        val callerMethod = node.getParentOfType<UMethod>(true) ?: return true
        val callerClass = callerMethod.javaPsi.containingClass ?: return true
        // a call inside an anonymous or local class has a different `this`
        if (node.getParentOfType<UClass>(true)?.javaPsi != callerClass) return true
        val calleeClass = callee.containingClass ?: return true
        if (!InheritanceUtil.isInheritorOrSelf(callerClass, calleeClass, true)) return true
        if (!SpringBeans.isBeanClass(callerClass)) return true
        val advice = SpringProxies.selfCallLosesAdvice(callee, callerMethod.javaPsi) ?: return true
        val anchor = node.methodIdentifier?.sourcePsi ?: node.sourcePsi ?: return true
        holder.registerProblem(anchor, PhantomSpringBundle.message("inspection.self.invocation.message", SpringProxies.shortName(advice), callee.name))
        return true
      }
    }, arrayOf(UCallExpression::class.java), true)
  }
}

/**
 * Proxy annotations on methods the proxy cannot override: private and static methods everywhere, and final ones in
 * Java. Kotlin's final-by-default classes are checked by the Kotlin-only inspection, which knows the compiler plugins.
 */
class SpringProxyMethodInspection : AbstractBaseUastLocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    if (!SpringProjectGate.isSpringProject(holder.project)) return PsiElementVisitor.EMPTY_VISITOR
    val java = holder.file.language.isKindOf(JavaLanguage.INSTANCE)
    return UastHintedVisitorAdapter.create(holder.file.language, object : AbstractUastNonRecursiveVisitor() {
      override fun visitMethod(node: UMethod): Boolean {
        val method = node.javaPsi
        if (method.isConstructor) return true
        val advice = SpringProxies.methodAdvice(method) ?: return true
        val psiClass = method.containingClass ?: return true
        if (psiClass.isInterface) return true
        val reason = when {
          method.hasModifierProperty(PsiModifier.PRIVATE) -> "private"
          SpringProxies.isStatic(method) -> "static"
          java && (method.hasModifierProperty(PsiModifier.FINAL) || psiClass.hasModifierProperty(PsiModifier.FINAL)) -> "final"
          else -> return true
        }
        val anchor = node.uastAnchor?.sourcePsi ?: return true
        holder.registerProblem(anchor, PhantomSpringBundle.message("inspection.proxy.method.message", SpringProxies.shortName(advice), reason))
        return true
      }
    }, arrayOf(UMethod::class.java), true)
  }
}
