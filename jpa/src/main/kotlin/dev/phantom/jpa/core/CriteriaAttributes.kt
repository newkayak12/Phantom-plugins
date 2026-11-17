// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.jpa.core

import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInspection.AbstractBaseUastLocalInspectionTool
import com.intellij.codeInspection.LocalInspectionToolSession
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.icons.AllIcons
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.patterns.uast.injectionHostUExpression
import com.intellij.psi.*
import com.intellij.psi.util.PsiUtil
import com.intellij.uast.UastHintedVisitorAdapter
import org.jetbrains.uast.*
import org.jetbrains.uast.visitor.AbstractUastNonRecursiveVisitor

/**
 * String attribute names in the Criteria API (`root.get("name")`, `root.join("orders")`, `fetch(...)`), resolved
 * against the entity the path stands for: the `X` of `Path<X>` for `get`, of `From<Z, X>` for joins and fetches.
 */
object CriteriaAttributes {
  private val PACKAGES = listOf("jakarta.persistence.criteria", "javax.persistence.criteria")
  val GET_METHODS: Set<String> = setOf("get")
  val JOIN_METHODS: Set<String> = setOf("join", "joinSet", "joinList", "joinMap", "joinCollection", "fetch")
  val METHODS: Array<String> = (GET_METHODS + JOIN_METHODS).toTypedArray()

  /** The persistent class whose attribute the call names, or null when the call is not a Criteria path call or the type is open. */
  fun ownerOf(call: UCallExpression): PsiClass? {
    val name = call.methodName ?: return null
    val method = call.resolve() ?: return null
    val pkg = method.containingClass?.qualifiedName?.substringBeforeLast('.') ?: return null
    if (pkg !in PACKAGES) return null
    if (method.parameterList.parameters.firstOrNull()?.type?.equalsToText(CommonClassNames.JAVA_LANG_STRING) != true) return null
    val receiver = call.receiverType ?: return null
    val type = when (name) {
      in GET_METHODS -> PsiUtil.substituteTypeParameter(receiver, "$pkg.Path", 0, false)
      in JOIN_METHODS -> PsiUtil.substituteTypeParameter(receiver, "$pkg.FetchParent", 1, false)
      else -> null
    } ?: return null
    val owner = PsiUtil.resolveClassInClassTypeOnly(if (type is PsiWildcardType) type.bound ?: return null else type) ?: return null
    return owner.takeIf { JpaModel.isPersistent(it) }
  }

  fun callOf(literal: UExpression): UCallExpression? {
    val call = literal.uastParent as? UCallExpression ?: return null
    return call.takeIf { call.getArgumentForParameter(0)?.sourcePsi == literal.sourcePsi && call.methodName in METHODS }
  }
}

class CriteriaAttributeInspection : AbstractBaseUastLocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    val module = ModuleUtilCore.findModuleForPsiElement(holder.file) ?: return PsiElementVisitor.EMPTY_VISITOR
    if (!JpaGate.isJpaModule(module)) return PsiElementVisitor.EMPTY_VISITOR
    return UastHintedVisitorAdapter.create(holder.file.language, object : AbstractUastNonRecursiveVisitor() {
      override fun visitCallExpression(node: UCallExpression): Boolean {
        if (node.methodName !in CriteriaAttributes.METHODS) return true
        val argument = node.getArgumentForParameter(0) ?: return true
        val name = argument.evaluateString() ?: return true
        val owner = CriteriaAttributes.ownerOf(node) ?: return true
        if (JpaModel.attribute(owner, name) != null) return true
        val anchor = argument.sourcePsi ?: return true
        holder.registerProblem(anchor, PhantomJpaBundle.message("criteria.unknown.attribute", name, owner.name ?: ""), ProblemHighlightType.GENERIC_ERROR_OR_WARNING)
        return true
      }
    }, arrayOf(UCallExpression::class.java), true)
  }
}

/** Ctrl+B and completion for the attribute names; unresolved names are left to the inspection. */
class CriteriaAttributeReferenceContributor : PsiReferenceContributor() {
  override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
    registrar.registerUastReferenceProvider(
      injectionHostUExpression(),
      uastInjectionHostReferenceProvider { literal, host ->
        val call = CriteriaAttributes.callOf(literal) ?: return@uastInjectionHostReferenceProvider PsiReference.EMPTY_ARRAY
        val owner = CriteriaAttributes.ownerOf(call) ?: return@uastInjectionHostReferenceProvider PsiReference.EMPTY_ARRAY
        arrayOf(CriteriaAttributeReference(host, owner))
      },
      PsiReferenceRegistrar.DEFAULT_PRIORITY,
    )
  }
}

class CriteriaAttributeReference(host: PsiLanguageInjectionHost, private val owner: PsiClass) :
  PsiReferenceBase<PsiElement>(host, ElementManipulators.getValueTextRange(host), true) {
  override fun resolve(): PsiElement? = JpaModel.attribute(owner, value)?.navigationElement

  override fun getVariants(): Array<Any> = JpaModel.attributes(owner).map {
    LookupElementBuilder.create(it.name).withIcon(AllIcons.Nodes.Field).withTypeText(it.type.presentableText, true)
  }.toTypedArray()
}
