// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.jpa.core

import com.intellij.codeInspection.AbstractBaseUastLocalInspectionTool
import com.intellij.codeInspection.LocalInspectionToolSession
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.InheritanceUtil
import com.intellij.psi.util.PsiUtil
import com.intellij.uast.UastHintedVisitorAdapter
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UField
import org.jetbrains.uast.evaluateString
import org.jetbrains.uast.visitor.AbstractUastNonRecursiveVisitor
import java.util.concurrent.ConcurrentHashMap

/** Spring Data JDBC aggregates: the domain types of repositories, in projects with spring-data-jdbc and no JPA. */
internal object JdbcAggregates {
  const val AGGREGATE_REFERENCE: String = "org.springframework.data.jdbc.core.mapping.AggregateReference"
  const val MAPPED_COLLECTION: String = "org.springframework.data.relational.core.mapping.MappedCollection"
  const val SEQUENCE: String = "org.springframework.data.relational.core.mapping.Sequence"
  private const val EMBEDDED = "org.springframework.data.relational.core.mapping.Embedded"
  private val TRANSIENT = listOf("org.springframework.data.annotation.Transient", "java.beans.Transient")

  private val JDBC_PROJECT = Key.create<CachedValue<ConcurrentHashMap<GlobalSearchScope, Boolean>>>("phantom.jpa.jdbc.project")

  /**
   * Asked by the inspection for every file it highlights, so the class lookups are kept per resolve scope until JVM
   * code or roots change.
   */
  fun isJdbcProject(project: Project, scope: GlobalSearchScope): Boolean {
    val byScope = CachedValuesManager.getManager(project).getCachedValue(project, JDBC_PROJECT, {
      CachedValueProvider.Result.create(ConcurrentHashMap<GlobalSearchScope, Boolean>(), *JpaCacheDeps.code(project))
    }, false)
    return byScope.getOrPut(scope) {
      val facade = JavaPsiFacade.getInstance(project)
      facade.findClass(AGGREGATE_REFERENCE, scope) != null && JpaMapping.fqns("Entity").none { facade.findClass(it, scope) != null }
    }
  }

  /** Aggregate roots of the project: the entity type argument of every Spring Data repository in project sources. */
  fun roots(project: Project): Set<PsiClass> = CachedValuesManager.getManager(project).getCachedValue(project) {
    val facade = JavaPsiFacade.getInstance(project)
    val base = facade.findClass(DerivedQuery.REPOSITORY, GlobalSearchScope.allScope(project))
    val result = LinkedHashSet<PsiClass>()
    if (base != null) {
      for (repository in ClassInheritorsSearch.search(base, GlobalSearchScope.projectScope(project), true)) {
        if (!repository.isInterface) continue
        DerivedQuery.entityOf(repository)?.takeIf { it !is PsiTypeParameter && it.qualifiedName?.startsWith("java.") == false }?.let(result::add)
      }
    }
    // JVM code and roots only: the whole PsiModificationTracker also moved on every SQL, YAML or XML keystroke
    CachedValueProvider.Result.create(result, *JpaCacheDeps.code(project))
  }

  /**
   * Entity class → the aggregate roots that contain it directly, built once from all roots: the inspection asks for
   * every class it visits, which used to recompute the children of every root each time.
   */
  fun owners(project: Project): Map<PsiClass, List<PsiClass>> = CachedValuesManager.getManager(project).getCachedValue(project) {
    val result = LinkedHashMap<PsiClass, MutableList<PsiClass>>()
    for (root in roots(project)) {
      for (child in children(root).values.distinct()) result.getOrPut(child) { ArrayList() }.add(root)
    }
    CachedValueProvider.Result.create(result as Map<PsiClass, List<PsiClass>>, *JpaCacheDeps.code(project))
  }

  /** Entity classes a root contains directly: property types and element types, without embedded values and references by id. */
  fun children(root: PsiClass): Map<PsiField, PsiClass> {
    val result = LinkedHashMap<PsiField, PsiClass>()
    for (field in root.allFields) {
      if (field.hasModifierProperty(PsiModifier.STATIC) || field.hasModifierProperty(PsiModifier.TRANSIENT)) continue
      if (TRANSIENT.any { field.hasAnnotation(it) } || field.hasAnnotation(EMBEDDED) || field.modifierList?.annotations?.any { it.qualifiedName?.startsWith("$EMBEDDED.") == true } == true) continue
      val target = entityClass(field.type) ?: continue
      result[field] = target
    }
    return result
  }

  private fun entityClass(type: PsiType): PsiClass? {
    val psiClass = PsiUtil.resolveClassInClassTypeOnly(type) ?: return null
    val element = when {
      psiClass.qualifiedName == AGGREGATE_REFERENCE -> return null
      InheritanceUtil.isInheritor(psiClass, false, CommonClassNames.JAVA_UTIL_COLLECTION) || psiClass.qualifiedName == CommonClassNames.JAVA_UTIL_COLLECTION ||
      psiClass.qualifiedName in setOf(CommonClassNames.JAVA_UTIL_LIST, CommonClassNames.JAVA_UTIL_SET) ->
        (type as? PsiClassType)?.parameters?.firstOrNull()?.let(PsiUtil::resolveClassInClassTypeOnly)
      InheritanceUtil.isInheritor(psiClass, false, CommonClassNames.JAVA_UTIL_MAP) || psiClass.qualifiedName == CommonClassNames.JAVA_UTIL_MAP ->
        (type as? PsiClassType)?.parameters?.getOrNull(1)?.let(PsiUtil::resolveClassInClassTypeOnly)
      else -> psiClass
    } ?: return null
    // only the project's own classes with an identity of their own are entities; values and library types are columns
    if (element.isEnum || element.isInterface || element is PsiTypeParameter || element.containingFile?.virtualFile == null) return null
    val fqn = element.qualifiedName ?: return null
    if (fqn.startsWith("java.") || fqn.startsWith("kotlin.") || fqn.startsWith("javax.")) return null
    if (!element.isPhysical || element.manager.isInProject(element).not()) return null
    return element
  }

  fun isIndexed(type: PsiType): Boolean {
    val psiClass = PsiUtil.resolveClassInClassTypeOnly(type) ?: return false
    if (type is PsiArrayType) return true
    return psiClass.qualifiedName in setOf(CommonClassNames.JAVA_UTIL_LIST, CommonClassNames.JAVA_UTIL_MAP) ||
           InheritanceUtil.isInheritor(psiClass, false, CommonClassNames.JAVA_UTIL_LIST) || InheritanceUtil.isInheritor(psiClass, false, CommonClassNames.JAVA_UTIL_MAP)
  }
}

/**
 * Aggregate boundaries Spring Data JDBC enforces by saving and deleting whole aggregates:
 * - an aggregate root that holds another aggregate root as a property (it would be inserted and deleted with this
 *   aggregate): reference it by id or `AggregateReference`
 * - an entity contained in several aggregates (each aggregate writes its own copy and deletes it on update)
 */
class JdbcAggregateBoundaryInspection : AbstractBaseUastLocalInspectionTool() {
  override fun isAvailableForFile(file: PsiFile): Boolean = !GhostMode.enabled && super.isAvailableForFile(file)
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    if (!JdbcAggregates.isJdbcProject(holder.project, holder.file.resolveScope)) return PsiElementVisitor.EMPTY_VISITOR
    return UastHintedVisitorAdapter.create(holder.file.language, object : AbstractUastNonRecursiveVisitor() {
      override fun visitClass(node: UClass): Boolean {
        val psiClass = node.javaPsi
        val roots = JdbcAggregates.roots(holder.project)
        if (psiClass in roots) {
          for ((field, target) in JdbcAggregates.children(psiClass)) {
            if (field.containingClass != psiClass || target !in roots || target == psiClass) continue
            val anchor = (field.navigationElement as? PsiNameIdentifierOwner)?.nameIdentifier ?: field.nameIdentifier
            if (anchor.containingFile != holder.file) continue
            holder.registerProblem(anchor, PhantomJpaBundle.message("jdbc.root.in.root", target.name ?: "", psiClass.name ?: ""))
          }
          return true
        }
        val owners = JdbcAggregates.owners(holder.project)[psiClass].orEmpty()
        if (owners.size < 2) return true
        val anchor = node.uastAnchor?.sourcePsi ?: return true
        holder.registerProblem(anchor, PhantomJpaBundle.message("jdbc.several.aggregates", psiClass.name ?: "", owners.mapNotNull { it.name }.sorted().joinToString()))
        return true
      }
    }, arrayOf(UClass::class.java), true)
  }
}

/**
 * Mapping annotations Spring Data JDBC reads differently than written:
 * - `@Sequence` without a sequence name (nothing to select the next value from)
 * - `@MappedCollection(keyColumn = …)` on a `Set` or other unordered collection: only `List` and `Map` have a key column
 */
class JdbcMappingInspection : AbstractBaseUastLocalInspectionTool() {
  override fun isAvailableForFile(file: PsiFile): Boolean = !GhostMode.enabled && super.isAvailableForFile(file)
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    if (JavaPsiFacade.getInstance(holder.project).findClass(JdbcAggregates.AGGREGATE_REFERENCE, holder.file.resolveScope) == null) return PsiElementVisitor.EMPTY_VISITOR
    return UastHintedVisitorAdapter.create(holder.file.language, object : AbstractUastNonRecursiveVisitor() {
      override fun visitField(node: UField): Boolean {
        node.findAnnotation(JdbcAggregates.SEQUENCE)?.let { checkSequence(it) }
        node.findAnnotation(JdbcAggregates.MAPPED_COLLECTION)?.let { checkKeyColumn(it, node.type) }
        return true
      }

      private fun checkSequence(annotation: UAnnotation) {
        val name = listOf("value", "sequence").firstNotNullOfOrNull { annotation.findDeclaredAttributeValue(it)?.evaluateString() }
        if (!name.isNullOrBlank()) return
        val anchor = annotation.sourcePsi ?: return
        holder.registerProblem(anchor, PhantomJpaBundle.message("jdbc.sequence.empty"), ProblemHighlightType.GENERIC_ERROR)
      }

      private fun checkKeyColumn(annotation: UAnnotation, type: PsiType) {
        val keyColumn = annotation.findDeclaredAttributeValue("keyColumn") ?: return
        if (keyColumn.evaluateString().isNullOrEmpty()) return
        if (PsiUtil.resolveClassInClassTypeOnly(type) == null || JdbcAggregates.isIndexed(type)) return
        val anchor = keyColumn.sourcePsi ?: return
        holder.registerProblem(anchor, PhantomJpaBundle.message("jdbc.key.column.unordered", type.presentableText), ProblemHighlightType.WARNING)
      }
    }, arrayOf(UField::class.java), true)
  }
}
