// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
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
import com.intellij.psi.LambdaUtil
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypes
import com.intellij.psi.util.InheritanceUtil
import com.intellij.uast.UastHintedVisitorAdapter
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey
import org.jetbrains.uast.UBlockExpression
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.ULambdaExpression
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UObjectLiteralExpression
import org.jetbrains.uast.UParenthesizedExpression
import org.jetbrains.uast.UQualifiedReferenceExpression
import org.jetbrains.uast.UReturnExpression
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

  /**
   * A statement in a block. The last expression of a lambda body (or an expression lambda's body) is the lambda's value,
   * so it is used, unless the lambda returns nothing: `users.forEach(u -> repository.save(u))` drops it.
   */
  private fun isDropped(expression: UExpression): Boolean {
    when (val parent = expression.uastParent) {
      is ULambdaExpression -> return returnsNothing(parent)
      // the implicit return UAST puts around an expression lambda's body or a Kotlin lambda's last expression
      is UReturnExpression -> {
        if (parent.sourcePsi != null) return false
        val lambda = parent.uastParent as? ULambdaExpression ?: parent.uastParent?.uastParent as? ULambdaExpression ?: return false
        return returnsNothing(lambda)
      }
      is UBlockExpression -> {
        val lambda = parent.uastParent as? ULambdaExpression
        return lambda == null || parent.expressions.lastOrNull() != expression || returnsNothing(lambda)
      }
      else -> return false
    }
  }

  /** The lambda is a `void` functional interface (Consumer, Runnable) or a Kotlin function type returning Unit. */
  private fun returnsNothing(lambda: ULambdaExpression): Boolean {
    val type = lambda.functionalInterfaceType ?: lambda.getExpressionType() ?: return false
    LambdaUtil.getFunctionalInterfaceReturnType(type)?.let { return it == PsiTypes.voidType() || it.canonicalText == "kotlin.Unit" }
    // Kotlin function types (kotlin.jvm.functions.FunctionN) take the return type as their last type argument
    val classType = type as? PsiClassType ?: return false
    return classType.className.startsWith("Function") && classType.parameters.lastOrNull()?.canonicalText?.removePrefix("? extends ") == "kotlin.Unit"
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
    if (name == "get" || name == "join") {
      val receiver = (call.receiverType as? PsiClassType)?.resolve() ?: return false
      return InheritanceUtil.isInheritor(receiver, "java.util.concurrent.Future") || receiver.qualifiedName == "java.util.concurrent.Future"
    }
    if (name !in BLOCKING) return false
    return ReactiveTypes.isLazy(call.receiverType)
  }

  /**
   * The nearest enclosing lambda, or method of an anonymous class (`flux.map(new Function<>() { … })`), is an argument of
   * a call made on a reactive value (`flux.map { … }`).
   */
  private fun insideReactiveOperator(call: UCallExpression): Boolean {
    var element: UElement? = call.uastParent
    while (element != null) {
      if (element is ULambdaExpression) return isOperator(element.uastParent)
      if (element is UMethod) {
        val literal = element.uastParent?.uastParent as? UObjectLiteralExpression ?: return false
        return isOperator(literal.uastParent)
      }
      element = element.uastParent
    }
    return false
  }

  private fun isOperator(element: UElement?): Boolean {
    val operator = element as? UCallExpression ?: return false
    return ReactiveTypes.isLazy(operator.receiverType) || ReactiveTypes.isLazy(operator.returnType) && operator.receiver != null
  }

  private companion object {
    val BLOCKING = setOf(
      "block", "blockFirst", "blockLast", "blockOptional", "toIterable", "toStream",
      "blockingGet", "blockingFirst", "blockingLast", "blockingAwait", "blockingSingle", "blockingIterable", "blockingStream",
      "blockingForEach", "blockingSubscribe", "blockingNext", "blockingLatest", "blockingMostRecent",
    )
  }
}
