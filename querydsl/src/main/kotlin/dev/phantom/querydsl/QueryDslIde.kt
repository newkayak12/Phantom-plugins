// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.querydsl

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.openapi.editor.Editor
import com.intellij.psi.JavaElementVisitor
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiField
import com.intellij.psi.PsiReference
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.Processor

/** Go to Declaration on a Q class path (`order.customer.name`, `QOrder.order`) goes to the entity property or entity. */
class QueryDslGotoDeclaration : GotoDeclarationHandler {
  override fun getGotoDeclarationTargets(sourceElement: PsiElement?, offset: Int, editor: Editor): Array<PsiElement>? {
    if (sourceElement == null) return null
    val field = TargetElementUtil.findReference(editor, offset)?.resolve() as? PsiField ?: return null
    return QClasses.targetOf(field)?.let { arrayOf(it) }
  }
}

/** Find Usages of an entity field (or entity) also finds its QueryDSL paths: `order.name`, `QOrder.order`. */
class QueryDslUsagesSearcher : QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters>(true) {
  override fun processQuery(parameters: ReferencesSearch.SearchParameters, consumer: Processor<in PsiReference>) {
    val targets = when (val element = parameters.elementToSearch) {
      is PsiField -> QClasses.qFieldsOf(element)
      is PsiClass -> if (QClasses.isQClass(element)) emptyList() else QClasses.qClassOf(element)?.let(QClasses::instancesOf).orEmpty()
      else -> emptyList()
    }
    for (target in targets) {
      if (!ReferencesSearch.search(target, parameters.effectiveSearchScope).forEach(consumer)) return
    }
  }
}

/** Q classes older than their entity: a property the Q class lacks, or one it still has. Rebuilding regenerates them. */
class StaleQClassInspection : AbstractBaseJavaLocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor = object : JavaElementVisitor() {
    override fun visitClass(entity: PsiClass) {
      if (QClasses.isQClass(entity)) return
      val q = QClasses.qClassOf(entity) ?: return
      val inQ = QClasses.qProperties(q).map { it.name }.toSet()
      for (field in entity.fields) {
        if (!QClasses.isProperty(field) || field.name in inQ) continue
        holder.registerProblem(field.nameIdentifier, PhantomQueryDslBundle.message("stale.missing", q.name!!, field.name), ProblemHighlightType.WARNING)
      }
      val inEntity = entity.allFields.map { it.name }.toSet()
      val gone = QClasses.qProperties(q).map { it.name }.filter { it !in inEntity && entity.findMethodsByName("get" + it.replaceFirstChar(Char::uppercase), true).isEmpty() }
      if (gone.isNotEmpty()) {
        holder.registerProblem(entity.nameIdentifier ?: return, PhantomQueryDslBundle.message("stale.gone", q.name!!, gone.joinToString(", ")), ProblemHighlightType.WARNING)
      }
    }
  }
}
