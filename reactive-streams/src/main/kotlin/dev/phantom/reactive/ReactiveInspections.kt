// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.reactive

import com.intellij.DynamicBundle
import com.intellij.codeInspection.AbstractBaseUastLocalInspectionTool
import com.intellij.codeInspection.LocalInspectionToolSession
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.java.library.JavaLibraryUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiType
import com.intellij.psi.util.InheritanceUtil
import com.intellij.uast.UastHintedVisitorAdapter
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey
import org.jetbrains.uast.UBlockExpression
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.ULambdaExpression
import org.jetbrains.uast.UParenthesizedExpression
import org.jetbrains.uast.UQualifiedReferenceExpression
import org.jetbrains.uast.UastCallKind
import org.jetbrains.uast.visitor.AbstractUastNonRecursiveVisitor

private const val BUNDLE = "messages.PhantomReactiveBundle"

object PhantomReactiveBundle : DynamicBundle(BUNDLE) {
  @Nls
  fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String = getMessage(key, *params)
}

/** Reactor and RxJava types: a value of one of these does nothing until something subscribes to it. */
internal object ReactiveTypes {
  private val LAZY = listOf(
    "org.reactivestreams.Publisher",
    "io.reactivex.rxjava3.core.Observable", "io.reactivex.rxjava3.core.Single", "io.reactivex.rxjava3.core.Maybe",
    "io.reactivex.rxjava3.core.Completable", "io.reactivex.rxjava3.core.Flowable",
    "io.reactivex.Observable", "io.reactivex.Single", "io.reactivex.Maybe", "io.reactivex.Completable", "io.reactivex.Flowable",
  )
  private val MARKERS = listOf("org.reactivestreams.Publisher", "io.reactivex.rxjava3.core.Observable", "io.reactivex.Observable")

  fun isReactiveModule(module: Module): Boolean {
    if (module.isDisposed) return false
    if (MARKERS.any { JavaLibraryUtil.hasLibraryClass(module, it) }) return true
    return ApplicationManager.getApplication().isUnitTestMode &&
           MARKERS.any { JavaPsiFacade.getInstance(module.project).findClass(it, module.getModuleWithDependenciesAndLibrariesScope(false)) != null }
  }

  fun isLazy(type: PsiType?): Boolean {
    val psiClass = (type as? PsiClassType)?.resolve() ?: return false
    return LAZY.any { InheritanceUtil.isInheritor(psiClass, false, it) || psiClass.qualifiedName == it }
  }
}

/** Calls the visitors look at: method calls, the selector of `a.b()` chains, not constructors. */
private fun UCallExpression.isMethodCall(): Boolean = kind == UastCallKind.METHOD_CALL

/** The outermost expression of a call chain this call belongs to: `mono.map { }.filter { }` for its last call. */
private fun UCallExpression.chainTop(): UExpression {
  var current: UExpression = this
  while (true) {
    val parent = current.uastParent
    current = when {
      parent is UQualifiedReferenceExpression && parent.selector == current -> parent
      parent is UParenthesizedExpression -> parent
      else -> return current
    }
  }
}

/**
 * A Mono, Flux, Publisher or RxJava source whose value is dropped: `repository.save(user)` on a reactive repository
 * written as a statement does not save anything, because nothing subscribes.
 */
class UnusedPublisherInspection : AbstractBaseUastLocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    val module = ModuleUtilCore.findModuleForPsiElement(holder.file) ?: return PsiElementVisitor.EMPTY_VISITOR
    if (!ReactiveTypes.isReactiveModule(module)) return PsiElementVisitor.EMPTY_VISITOR
    return UastHintedVisitorAdapter.create(holder.file.language, object : AbstractUastNonRecursiveVisitor() {
      override fun visitCallExpression(node: UCallExpression): Boolean {
        if (!node.isMethodCall()) return true
        val top = node.chainTop()
        // only the last call of a chain decides, and only when the whole chain is a statement
        if (top != node && (top as? UQualifiedReferenceExpression)?.selector != node) return true
        if (!isDropped(top) || !ReactiveTypes.isLazy(node.returnType)) return true
        val anchor = node.methodIdentifier?.sourcePsi ?: node.sourcePsi ?: return true
        holder.registerProblem(anchor, PhantomReactiveBundle.message("unused.publisher", node.methodName ?: ""))
        return true
      }
    }, arrayOf(UCallExpression::class.java), true)
  }

  /** A statement in a block; the last expression of a lambda body is the lambda's value, so it is used. */
  private fun isDropped(expression: UExpression): Boolean {
    val block = expression.uastParent as? UBlockExpression ?: return false
    if (block.uastParent is ULambdaExpression && block.expressions.lastOrNull() == expression) return false
    return true
  }
}

/**
 * `block()`, `blockFirst()`, `blockLast()` and `Thread.sleep()` inside an operator of a reactive chain: the lambda runs
 * on a scheduler thread, and in WebFlux on an event loop, where blocking stalls every request it serves.
 */
class BlockingInReactiveInspection : AbstractBaseUastLocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    val module = ModuleUtilCore.findModuleForPsiElement(holder.file) ?: return PsiElementVisitor.EMPTY_VISITOR
    if (!ReactiveTypes.isReactiveModule(module)) return PsiElementVisitor.EMPTY_VISITOR
    return UastHintedVisitorAdapter.create(holder.file.language, object : AbstractUastNonRecursiveVisitor() {
      override fun visitCallExpression(node: UCallExpression): Boolean {
        if (!node.isMethodCall() || !isBlocking(node)) return true
        if (!insideReactiveOperator(node)) return true
        val anchor = node.methodIdentifier?.sourcePsi ?: node.sourcePsi ?: return true
        holder.registerProblem(anchor, PhantomReactiveBundle.message("blocking.in.operator", node.methodName ?: ""))
        return true
      }
    }, arrayOf(UCallExpression::class.java), true)
  }

  private fun isBlocking(call: UCallExpression): Boolean {
    val name = call.methodName ?: return false
    if (name == "sleep") return call.resolve()?.containingClass?.qualifiedName == "java.lang.Thread"
    if (name !in BLOCKING) return false
    return ReactiveTypes.isLazy(call.receiverType)
  }

  /** The nearest enclosing lambda is an argument of a call made on a reactive value (`flux.map { … }`). */
  private fun insideReactiveOperator(call: UCallExpression): Boolean {
    var element: UElement? = call.uastParent
    while (element != null) {
      if (element is ULambdaExpression) {
        val operator = element.uastParent as? UCallExpression ?: return false
        return ReactiveTypes.isLazy(operator.receiverType) || ReactiveTypes.isLazy(operator.returnType) && operator.receiver != null
      }
      if (element is org.jetbrains.uast.UMethod) return false
      element = element.uastParent
    }
    return false
  }

  private companion object {
    val BLOCKING = setOf("block", "blockFirst", "blockLast", "blockOptional", "blockingGet", "blockingFirst", "blockingLast", "blockingAwait")
  }
}
