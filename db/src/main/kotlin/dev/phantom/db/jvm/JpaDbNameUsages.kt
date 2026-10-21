// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.jvm

import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.Processor
import dev.phantom.db.core.SqlDbMappingReference
import dev.phantom.db.core.SqlDbMappings
import dev.phantom.db.core.SqlDbTarget
import dev.phantom.db.core.SqlNames
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.getParentOfType
import org.jetbrains.uast.toUElement

/** A JPA mapping's name of a table or column: the string in `@Table(name = "…")`, `@Column(name = "…")`, `@JoinColumn(name = "…")`. */
class JpaDbNameReference(element: PsiElement, range: TextRange, private val target: SqlDbTarget) :
  PsiReferenceBase<PsiElement>(element, range, true), SqlDbMappingReference {
  override fun resolve(): PsiElement = target
}

class JpaDbMappings : SqlDbMappings {
  override fun isAvailable(project: Project): Boolean = JpaSchemaModel.isJpaProject(project)
}

/**
 * Usages of a database table or column in JPA mappings: `@Table(name)` naming the table, and `@Column(name)` /
 * `@JoinColumn(name)` naming the column on an entity mapped to the column's table. Names compare case-insensitively.
 */
class JpaDbNameSearcher : QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters>(true) {
  override fun processQuery(parameters: ReferencesSearch.SearchParameters, consumer: Processor<in PsiReference>) {
    val target = parameters.elementToSearch as? SqlDbTarget ?: return
    val scope = parameters.effectiveSearchScope as? GlobalSearchScope ?: return
    val project = parameters.project
    if (!JpaSchemaModel.isJpaProject(project)) return
    val seen = HashSet<PsiElement>()
    PsiSearchHelper.getInstance(project).processElementsWithWord({ element, _ ->
      val host = element as? PsiLanguageInjectionHost
      if (host == null || !seen.add(host)) return@processElementsWithWord true
      val ref = reference(host, target) ?: return@processElementsWithWord true
      consumer.process(ref)
    }, scope, target.name, UsageSearchContext.IN_STRINGS, false)
  }

  private fun reference(host: PsiLanguageInjectionHost, target: SqlDbTarget): PsiReference? {
    val expression = host.toUElement() as? UExpression ?: return null
    val value = expression.evaluate() as? String ?: return null
    val (annotation, attribute) = annotationOf(expression) ?: return null
    if (attribute != "name") return null
    val name = annotation.qualifiedName ?: return null
    val column = target.column
    val matches = when {
      column == null -> name in TABLE && SqlNames.same(value, target.table.name)
      name in COLUMN -> SqlNames.same(value, column.name) && entityTable(annotation)?.let { SqlNames.same(it, target.table.name) } == true
      else -> false
    }
    if (!matches) return null
    return JpaDbNameReference(host, ElementManipulators.getValueTextRange(host), target)
  }

  /** The table the entity declaring [annotation] maps to, as the configured naming makes it. */
  private fun entityTable(annotation: UAnnotation): String? {
    val psiClass = annotation.getParentOfType<UClass>()?.javaPsi as? PsiClass ?: return null
    if (!JpaSchemaModel.isEntity(psiClass)) return null
    val naming = JpaSchemaModel.naming(psiClass.project) ?: return null
    return JpaSchemaModel.table(psiClass, naming).name
  }

  /** The annotation the string is a value of, and the attribute name. */
  private fun annotationOf(expression: UExpression): Pair<UAnnotation, String>? {
    var current: UElement = expression
    var parent = current.uastParent
    while (parent != null && parent !is UAnnotation) {
      if (parent is UCallExpression || parent is UMethod || parent is UClass) return null
      current = parent
      parent = parent.uastParent
    }
    val annotation = parent as? UAnnotation ?: return null
    val psi = current.sourcePsi
    val attribute = annotation.attributeValues.firstOrNull { it.sourcePsi == psi || it.expression.sourcePsi == expression.sourcePsi }
    return annotation to (attribute?.name ?: "value")
  }

  private companion object {
    val TABLE = setOf("jakarta.persistence.Table", "javax.persistence.Table")
    val COLUMN = setOf("jakarta.persistence.Column", "javax.persistence.Column", "jakarta.persistence.JoinColumn", "javax.persistence.JoinColumn")
  }
}
