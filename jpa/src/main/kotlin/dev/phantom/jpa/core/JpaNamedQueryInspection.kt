// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.jpa.core

import com.intellij.codeInsight.AnnotationUtil
import com.intellij.codeInspection.AbstractBaseUastLocalInspectionTool
import com.intellij.codeInspection.LocalInspectionToolSession
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.uast.UastHintedVisitorAdapter
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.evaluateString
import org.jetbrains.uast.visitor.AbstractUastNonRecursiveVisitor

/**
 * `entityManager.createNamedQuery("Order.byCustomer")` whose name no `@NamedQuery` / `@NamedNativeQuery` of the
 * module's entities declares: the call fails with an IllegalArgumentException. Skipped when the project has an
 * `orm.xml`, which can declare named queries too.
 */
class JpaNamedQueryInspection : AbstractBaseUastLocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    val module = ModuleUtilCore.findModuleForPsiElement(holder.file) ?: return PsiElementVisitor.EMPTY_VISITOR
    if (!JpaGate.isJpaModule(module)) return PsiElementVisitor.EMPTY_VISITOR
    val names by lazy { namedQueries(module) }
    return UastHintedVisitorAdapter.create(holder.file.language, object : AbstractUastNonRecursiveVisitor() {
      override fun visitCallExpression(node: UCallExpression): Boolean {
        if (node.methodName != "createNamedQuery") return true
        val argument = node.valueArguments.firstOrNull() ?: return true
        val name = argument.evaluateString()?.takeIf { it.isNotBlank() } ?: return true
        val owner = node.resolve()?.containingClass?.qualifiedName ?: return true
        if (owner !in ENTITY_MANAGERS) return true
        val known = names ?: return true
        if (name in known) return true
        holder.registerProblem(argument.sourcePsi ?: return true, PhantomJpaBundle.message("named.query.unknown", name))
        return true
      }
    }, arrayOf(UCallExpression::class.java), true)
  }

  /** Names of the named queries on the module's classes, or null when an orm.xml may declare more. */
  private fun namedQueries(module: Module): Set<String>? {
    val project = module.project
    if (FilenameIndex.getVirtualFilesByName("orm.xml", GlobalSearchScope.projectScope(project)).isNotEmpty()) return null
    val scope = module.getModuleWithDependenciesAndLibrariesScope(false)
    val facade = JavaPsiFacade.getInstance(project)
    val names = HashSet<String>()
    for (fqn in SINGLE + CONTAINERS) {
      val annotationClass = facade.findClass(fqn, GlobalSearchScope.allScope(project)) ?: continue
      for (owner in AnnotatedElementsSearch.searchPsiClasses(annotationClass, scope).findAll()) {
        for (annotation in owner.annotations) collect(annotation, names)
      }
    }
    return names
  }

  private fun collect(annotation: PsiAnnotation, names: MutableSet<String>) {
    val fqn = annotation.qualifiedName ?: return
    when (fqn) {
      in SINGLE -> AnnotationUtil.getStringAttributeValue(annotation, "name")?.let(names::add)
      in CONTAINERS -> AnnotationUtil.arrayAttributeValues(annotation.findDeclaredAttributeValue("value"))
        .filterIsInstance<PsiAnnotation>().forEach { collect(it, names) }
    }
  }

  private companion object {
    val ENTITY_MANAGERS = setOf("jakarta.persistence.EntityManager", "javax.persistence.EntityManager")
    val SINGLE = listOf("NamedQuery", "NamedNativeQuery").flatMap { listOf("jakarta.persistence.$it", "javax.persistence.$it", "org.hibernate.annotations.$it") }
    val CONTAINERS = listOf("NamedQueries", "NamedNativeQueries").flatMap { listOf("jakarta.persistence.$it", "javax.persistence.$it", "org.hibernate.annotations.$it") }
  }
}
