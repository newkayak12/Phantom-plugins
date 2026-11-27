// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.jpa.core

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInspection.AbstractBaseUastLocalInspectionTool
import com.intellij.codeInspection.IntentionWrapper
import com.intellij.codeInspection.LocalInspectionToolSession
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.lang.jvm.JvmModifiersOwner
import com.intellij.lang.jvm.actions.annotationRequest
import com.intellij.lang.jvm.actions.createAddAnnotationActions
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiField
import com.intellij.uast.UastHintedVisitorAdapter
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.UField
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UReferenceExpression
import org.jetbrains.uast.evaluateString
import org.jetbrains.uast.toUElement
import org.jetbrains.uast.visitor.AbstractUastNonRecursiveVisitor

/**
 * `@Query("update …")` / `delete` / `insert` without `@Modifying`: Spring Data runs it as a select and the call fails
 * with "Query executed via 'getResultList()' or 'getSingleResult()' must be a 'select' query". Native queries too, also
 * written as `@NativeQuery`.
 */
class ModifyingQueryInspection : AbstractBaseUastLocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    val module = ModuleUtilCore.findModuleForPsiElement(holder.file) ?: return PsiElementVisitor.EMPTY_VISITOR
    if (!JpaGate.isJpaModule(module)) return PsiElementVisitor.EMPTY_VISITOR
    return UastHintedVisitorAdapter.create(holder.file.language, Visitor(holder), arrayOf(UMethod::class.java), true)
  }

  private class Visitor(private val holder: ProblemsHolder) : AbstractUastNonRecursiveVisitor() {
    override fun visitMethod(node: UMethod): Boolean {
      val query = QUERY.firstNotNullOfOrNull { node.findAnnotation(it) } ?: return true
      if (node.findAnnotation(MODIFYING) != null) return true
      val value = query.findDeclaredAttributeValue("value") ?: return true
      val text = queryText(value) ?: return true
      val verb = MODIFYING_VERB.find(text)?.groupValues?.get(1)?.uppercase() ?: return true
      val anchor = query.uastAnchor?.sourcePsi ?: query.sourcePsi ?: return true
      holder.registerProblem(anchor, PhantomJpaBundle.message("modifying.missing", verb), *fixes(node))
      return true
    }

    /** The literal, or the constant it names (`@Query(CLAIM_SQL)` with a `const val`), possibly built from a template. */
    private fun queryText(value: UExpression): String? {
      value.evaluateString()?.let { return it }
      val field = (value as? UReferenceExpression)?.resolve() as? PsiField ?: return null
      (field.computeConstantValue() as? String)?.let { return it }
      val initializer = (field.toUElement() as? UField)?.uastInitializer ?: return null
      // A template with interpolations does not evaluate; its first literal still says what the statement does
      return initializer.evaluateString() ?: initializer.sourcePsi?.text?.trimStart('"', ' ', '\n')
    }

    private fun fixes(node: UMethod): Array<com.intellij.codeInspection.LocalQuickFix> {
      val owner = node.javaPsi as? JvmModifiersOwner ?: return emptyArray()
      val actions: List<IntentionAction> = createAddAnnotationActions(owner, annotationRequest(MODIFYING))
      return IntentionWrapper.wrapToQuickFixes(actions, holder.file).toTypedArray()
    }
  }

  private companion object {
    val QUERY = listOf("org.springframework.data.jpa.repository.Query", "org.springframework.data.jpa.repository.NativeQuery")
    const val MODIFYING = "org.springframework.data.jpa.repository.Modifying"
    val MODIFYING_VERB = Regex("""^\s*(update|delete|insert)\b""", RegexOption.IGNORE_CASE)
  }
}
