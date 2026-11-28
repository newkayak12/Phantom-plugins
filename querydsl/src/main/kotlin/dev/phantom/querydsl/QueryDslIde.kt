// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.querydsl

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.codeInspection.AbstractBaseUastLocalInspectionTool
import com.intellij.codeInspection.LocalInspectionToolSession
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiField
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiReference
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.uast.UastHintedVisitorAdapter
import com.intellij.util.Processor
import org.jetbrains.uast.UClass
import org.jetbrains.uast.visitor.AbstractUastNonRecursiveVisitor

/** Go to Declaration on a Q class path (`order.customer.name`, `QOrder.order`) goes to the entity property or entity. */
class QueryDslGotoDeclaration : GotoDeclarationHandler {
  override fun getGotoDeclarationTargets(sourceElement: PsiElement?, offset: Int, editor: Editor): Array<PsiElement>? {
    if (sourceElement == null) return null
    val field = TargetElementUtil.findReference(editor, offset)?.resolve() as? PsiField ?: return null
    return QClasses.targetOf(field)?.let { arrayOf(it) }
  }
}

/**
 * Find Usages of an entity field (or entity) also finds its QueryDSL paths: `order.name`, `QOrder.order`. Only finding
 * the Q class fields takes a read action; the nested reference searches take their own, so a long search does not hold
 * one read action throughout.
 */
class QueryDslUsagesSearcher : QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters>(false) {
  override fun processQuery(parameters: ReferencesSearch.SearchParameters, consumer: Processor<in PsiReference>) {
    val targets = ReadAction.compute<List<PsiField>, RuntimeException> {
      when (val element = parameters.elementToSearch) {
        is PsiField -> if (element.isValid) QClasses.qFieldsOf(element) else emptyList()
        is PsiClass -> if (!element.isValid || QClasses.isQClass(element) || !QClasses.isQueryType(element)) emptyList()
                       else QClasses.qClassOf(element)?.let(QClasses::instancesOf).orEmpty()
        else -> emptyList()
      }
    }
    for (target in targets) {
      if (!ReferencesSearch.search(target, parameters.effectiveSearchScope).forEach(consumer)) return
    }
  }
}

/**
 * Q classes older than their entity (Java or Kotlin): a property the Q class lacks, or one it still has. Rebuilding
 * regenerates them. Only entities and other mapped classes of modules with QueryDSL are checked.
 */
class StaleQClassInspection : AbstractBaseUastLocalInspectionTool() {
  override fun isAvailableForFile(file: PsiFile): Boolean = !GhostMode.enabled && super.isAvailableForFile(file)
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor =
    UastHintedVisitorAdapter.create(holder.file.language, object : AbstractUastNonRecursiveVisitor() {
      override fun visitClass(node: UClass): Boolean {
        check(node, holder)
        return true
      }
    }, arrayOf(UClass::class.java), true)

  private fun check(node: UClass, holder: ProblemsHolder) {
    val entity = node.javaPsi
    if (QClasses.isQClass(entity) || !QClasses.isQueryType(entity)) return
    val q = QClasses.qClassOf(entity) ?: return
    val inQ = QClasses.qProperties(q).map { it.name }.toSet()
    for (field in node.fields) {
      val psiField = field.javaPsi as? PsiField ?: continue
      if (!QClasses.isProperty(psiField) || psiField.name in inQ) continue
      val anchor = field.uastAnchor?.sourcePsi ?: continue
      holder.registerProblem(anchor, PhantomQueryDslBundle.message("stale.missing", q.name!!, psiField.name), ProblemHighlightType.WARNING)
    }
    checkGone(node, q, holder)
  }

  /** Paths the Q class still has for a property the entity no longer declares as a field or getter. */
  private fun checkGone(node: UClass, q: PsiClass, holder: ProblemsHolder) {
    val entity = node.javaPsi
    val inEntity = entity.allFields.map { it.name }.toSet()
    val gone = QClasses.qProperties(q).map { it.name }.filter { it !in inEntity && QClasses.getterOf(entity, it) == null }
    if (gone.isEmpty()) return
    val anchor = node.uastAnchor?.sourcePsi ?: return
    holder.registerProblem(anchor, PhantomQueryDslBundle.message("stale.gone", q.name!!, gone.joinToString(", ")), ProblemHighlightType.WARNING)
  }
}
