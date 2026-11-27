// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.jpa.core

import com.intellij.codeInsight.AnnotationUtil
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.InheritanceUtil
import com.intellij.xml.util.XmlStringUtil
import org.jetbrains.annotations.TestOnly
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UField
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UParameter
import org.jetbrains.uast.getUParentForIdentifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.Icon

/** The JPQL a Spring Data derived query method stands for, written out for a tooltip. */
object DerivedJpql {
  private val ALL_IGNORE_CASE = Regex("All(IgnoreCase|IgnoringCase)(OrderBy|$)")
  private val IGNORE_CASE = Regex("(IgnoreCase|IgnoringCase)$")
  private val LIMIT = Regex("(First|Top)(\\d*)")

  /** `select u from User u where u.lastname = ?1 order by u.age desc`; null when a part has no JPQL form or does not resolve. */
  fun of(entity: PsiClass, methodName: String): String? {
    val parsed = DerivedQuery.parse(methodName) ?: return null
    val entityName = JpaModel.entityName(entity) ?: entity.name ?: return null
    val alias = entityName.first().lowercaseChar().toString()
    val subject = methodName.substring(0, parsed.subjectEnd)
    val verb = DerivedQuery.PREFIX.find(methodName)?.groupValues?.get(1) ?: return null
    val distinct = subject.contains("Distinct")
    val head = when (verb) {
      "count" -> "select count(${if (distinct) "distinct " else ""}$alias) from $entityName $alias"
      "exists" -> "select count($alias) > 0 from $entityName $alias"
      "delete", "remove" -> "delete from $entityName $alias"
      else -> "select ${if (distinct) "distinct " else ""}$alias from $entityName $alias"
    }
    val allIgnoreCase = ALL_IGNORE_CASE.containsMatchIn(methodName)
    val where = StringBuilder()
    var parameter = 1
    for ((i, part) in parsed.parts.withIndex()) {
      if (i > 0) {
        val previous = parsed.parts[i - 1]
        val between = methodName.substring(previous.offset + previous.source.length, part.offset)
        where.append(if (between.startsWith("Or")) " or " else " and ")
      }
      val members = DerivedQuery.resolvePath(entity, part.propertySource) ?: return null
      val path = pathOf(alias, members) ?: return null
      val ignoreCase = allIgnoreCase || IGNORE_CASE.containsMatchIn(part.source)
      where.append(condition(path, part.operator, parameter, ignoreCase) ?: return null)
      parameter += part.arguments
    }
    val order = parsed.orderBy.map { part ->
      val members = DerivedQuery.resolvePath(entity, part.propertySource) ?: return null
      (pathOf(alias, members) ?: return null) + if (part.operator == "Desc") " desc" else " asc"
    }
    val limit = LIMIT.find(subject)?.let { m -> " (first ${m.groupValues[2].ifEmpty { "1" }})" }.orEmpty()
    return buildString {
      append(head)
      if (where.isNotEmpty()) append(" where ").append(where)
      if (order.isNotEmpty()) append(" order by ").append(order.joinToString(", "))
      append(limit)
    }
  }

  private fun pathOf(alias: String, members: List<PsiMember>): String? {
    val names = members.map { it.name ?: return null }
    return alias + "." + names.joinToString(".")
  }

  private fun condition(path: String, operator: String?, n: Int, ignoreCase: Boolean): String? {
    val p = if (ignoreCase) "upper($path)" else path
    val v = if (ignoreCase) "upper(?$n)" else "?$n"
    return when (operator?.removePrefix("Is")?.ifEmpty { "Equals" }) {
      null, "Equals" -> "$p = $v"
      "Not" -> "$p <> $v"
      "Between" -> "$path between ?$n and ?${n + 1}"
      "NotBetween" -> "$path not between ?$n and ?${n + 1}"
      "LessThan", "Before" -> "$path < ?$n"
      "LessThanEqual" -> "$path <= ?$n"
      "GreaterThan", "After" -> "$path > ?$n"
      "GreaterThanEqual" -> "$path >= ?$n"
      "Like" -> "$p like $v"
      "NotLike" -> "$p not like $v"
      "StartingWith", "StartsWith" -> "$p like $v (…%)"
      "EndingWith", "EndsWith" -> "$p like $v (%…)"
      "Containing", "Contains" -> "$p like $v (%…%)"
      "NotContaining", "NotContains" -> "$p not like $v (%…%)"
      "Null" -> "$path is null"
      "NotNull" -> "$path is not null"
      "Empty" -> "$path is empty"
      "NotEmpty" -> "$path is not empty"
      "In" -> "$path in ?$n"
      "NotIn" -> "$path not in ?$n"
      "True" -> "$path = true"
      "False" -> "$path = false"
      else -> null
    }
  }
}

/** The repository interface and its entity for a method declared in a Spring Data repository, or null. */
private fun repositoryMethod(element: PsiElement): Pair<PsiMethod, PsiClass>? {
  if (element.firstChild != null) return null
  val owner = element.parent as? PsiNameIdentifierOwner ?: return null
  if (owner.nameIdentifier != element) return null
  val method = (getUParentForIdentifier(element) as? UMethod)?.javaPsi ?: return null
  val repository = method.containingClass?.takeIf { it.isInterface } ?: return null
  if (!InheritanceUtil.isInheritor(repository, DerivedQuery.REPOSITORY)) return null
  val module = ModuleUtilCore.findModuleForPsiElement(element) ?: return null
  if (!JpaGate.isJpaModule(module) || DumbService.isDumb(element.project)) return null
  val entity = DerivedQuery.entityOf(repository)?.takeIf { JpaModel.isEntity(it) } ?: return null
  return method to entity
}

/**
 * On a derived query method (`findByLastnameOrderByAgeDesc`): the JPQL it stands for as the tooltip, navigating to
 * the entity attributes it reads. Methods whose name does not resolve get nothing (the derived query check reports them).
 * Collected in the slow pass: resolving the repository's entity and the property paths must not hold up the first
 * highlighting of the file.
 */
class DerivedQueryLineMarkerProvider : LineMarkerProviderDescriptor() {
  override fun getName(): String = PhantomJpaBundle.message("gutter.derived.name")

  override fun getIcon(): Icon = AllIcons.Gutter.ImplementedMethod

  override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

  override fun collectSlowLineMarkers(elements: MutableList<out PsiElement>, result: MutableCollection<in LineMarkerInfo<*>>) {
    for (element in elements) {
      ProgressManager.checkCanceled()
      marker(element)?.let(result::add)
    }
  }

  private fun marker(element: PsiElement): LineMarkerInfo<*>? {
    val (method, entity) = repositoryMethod(element) ?: return null
    if (!DerivedQuery.isDerivedQueryMethod(method) || DerivedQuery.hasNamedQuery(entity, method)) return null
    val jpql = DerivedJpql.of(entity, method.name) ?: return null
    val parsed = DerivedQuery.parse(method.name) ?: return null
    val members = (parsed.parts + parsed.orderBy).flatMap { DerivedQuery.resolvePath(entity, it.propertySource).orEmpty() }.distinct()
    return NavigationGutterIconBuilder.create(AllIcons.Gutter.ImplementedMethod)
      .setTargets(NotNullLazyValue.lazy { members.filter { it.isValid }.map { it.navigationElement } })
      .setTooltipText(XmlStringUtil.escapeString(PhantomJpaBundle.message("gutter.derived.tooltip", jpql)))
      .setPopupTitle(PhantomJpaBundle.message("gutter.derived.popup", method.name))
      .createLineMarkerInfo(element)
  }
}

/** On a repository method with `@EntityGraph(attributePaths = ...)`: the attributes the graph fetches. Slow pass, like the derived query marker. */
class EntityGraphLineMarkerProvider : LineMarkerProviderDescriptor() {
  override fun getName(): String = PhantomJpaBundle.message("gutter.graph.name")

  override fun getIcon(): Icon = AllIcons.Nodes.DataTables

  override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

  override fun collectSlowLineMarkers(elements: MutableList<out PsiElement>, result: MutableCollection<in LineMarkerInfo<*>>) {
    for (element in elements) {
      ProgressManager.checkCanceled()
      marker(element)?.let(result::add)
    }
  }

  private fun marker(element: PsiElement): LineMarkerInfo<*>? {
    val (method, entity) = repositoryMethod(element) ?: return null
    val annotation = method.getAnnotation("org.springframework.data.jpa.repository.EntityGraph") ?: return null
    val helper = JavaPsiFacade.getInstance(method.project).constantEvaluationHelper
    val paths = AnnotationUtil.arrayAttributeValues(annotation.findAttributeValue("attributePaths"))
      .mapNotNull { helper.computeConstantExpression(it) as? String }.filter { it.isNotBlank() }
    if (paths.isEmpty()) return null
    val members = paths.mapNotNull { path ->
      var owner: PsiClass? = entity
      var last: PsiMember? = null
      for (segment in path.split('.')) {
        last = JpaModel.attribute(owner ?: return@mapNotNull null, segment) ?: return@mapNotNull null
        owner = JpaModel.valueClass(last)
      }
      last
    }.distinct()
    if (members.isEmpty()) return null
    return NavigationGutterIconBuilder.create(AllIcons.Nodes.DataTables)
      .setTargets(NotNullLazyValue.lazy { members.filter { it.isValid }.map { it.navigationElement } })
      .setTooltipText(PhantomJpaBundle.message("gutter.graph.tooltip", paths.joinToString(", ")))
      .setPopupTitle(PhantomJpaBundle.message("gutter.graph.popup", method.name))
      .createLineMarkerInfo(element)
  }
}

/**
 * On an entity attribute: the repository methods that query it, by derived name or in `@Query` JPQL (`alias.attribute`).
 * The repositories are scanned once per change of Java, Kotlin or Groovy code and shared by all entities; each entity's
 * usages are then resolved from that scan on first need. Collected in the slow pass, so the scan never holds up the
 * first highlighting of the file.
 */
class EntityAttributeUsagesLineMarkerProvider : LineMarkerProviderDescriptor() {
  override fun getName(): String = PhantomJpaBundle.message("gutter.attribute.name")

  override fun getIcon(): Icon = AllIcons.Gutter.ImplementingMethod

  override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

  override fun collectSlowLineMarkers(elements: MutableList<out PsiElement>, result: MutableCollection<in LineMarkerInfo<*>>) {
    for (element in elements) {
      ProgressManager.checkCanceled()
      marker(element)?.let(result::add)
    }
  }

  private fun marker(element: PsiElement): LineMarkerInfo<*>? {
    if (element.firstChild != null) return null
    val owner = element.parent as? PsiNameIdentifierOwner ?: return null
    if (owner.nameIdentifier != element) return null
    val (name, entity) = when (val parent = getUParentForIdentifier(element)) {
      is UField -> (parent.name to (parent.javaPsi as? PsiField)?.containingClass)
      // a Kotlin primary constructor property
      is UParameter -> (parent.name to ((parent.uastParent as? UMethod)?.takeIf { it.isConstructor })?.javaPsi?.containingClass)
      else -> return null
    }
    if (entity == null || !JpaModel.isEntity(entity)) return null
    if (JpaModel.attribute(entity, name) == null) return null
    val module = ModuleUtilCore.findModuleForPsiElement(element) ?: return null
    if (!JpaGate.isJpaModule(module) || DumbService.isDumb(element.project)) return null
    val methods = usages(entity)[name].orEmpty()
    if (methods.isEmpty()) return null
    return NavigationGutterIconBuilder.create(AllIcons.Gutter.ImplementingMethod)
      .setTargets(NotNullLazyValue.lazy { methods.filter { it.isValid }.map { it.navigationElement } })
      .setTooltipText(PhantomJpaBundle.message("gutter.attribute.tooltip", methods.size, name))
      .setPopupTitle(PhantomJpaBundle.message("gutter.attribute.popup", name))
      .createLineMarkerInfo(element)
  }

  /** A repository interface with its entity, and its methods that can query attributes. */
  private class ScannedRepository(val entity: PsiClass, val methods: List<ScannedMethod>)

  /** A derived query method with its property paths ([derived]), or a JPQL `@Query` method with its text ([jpql]). */
  private class ScannedMethod(val method: PsiMethod, val derived: List<String>?, val jpql: String?)

  companion object {
    private val QUERY = listOf("org.springframework.data.jpa.repository.Query", "org.springframework.data.jpa.repository.NativeQuery")
    private val REPOSITORIES = Key.create<CachedValue<Map<String, List<ScannedRepository>>>>("phantom.jpa.repositoriesByEntity")
    private val KEYWORDS = setOf("where", "join", "left", "inner", "order", "group", "on", "fetch", "having")
    // Built from entity names and aliases, which repeat across queries and scans: compiled once per name.
    private val ALIAS_PATTERNS = ConcurrentHashMap<String, Regex>()
    private val ATTRIBUTE_PATTERNS = ConcurrentHashMap<String, Regex>()

    @TestOnly
    val repositoryScans: AtomicInteger = AtomicInteger()

    /** Attribute name of [entity] → repository methods that query it. */
    fun usages(entity: PsiClass): Map<String, List<PsiMethod>> = CachedValuesManager.getCachedValue(entity) {
      CachedValueProvider.Result.create(compute(entity), *JpaCacheDeps.code(entity.project))
    }

    /**
     * Repository interfaces in the project by the qualified name of their entity, with their methods already split:
     * one inheritor search for all entities instead of one per entity.
     */
    private fun repositoriesByEntity(project: Project): Map<String, List<ScannedRepository>> =
      CachedValuesManager.getManager(project).getCachedValue(project, REPOSITORIES, {
        CachedValueProvider.Result.create(scan(project), *JpaCacheDeps.code(project))
      }, false)

    private fun scan(project: Project): Map<String, List<ScannedRepository>> {
      repositoryScans.incrementAndGet()
      val base = JavaPsiFacade.getInstance(project).findClass(DerivedQuery.REPOSITORY, GlobalSearchScope.allScope(project)) ?: return emptyMap()
      val result = LinkedHashMap<String, MutableList<ScannedRepository>>()
      for (repository in ClassInheritorsSearch.search(base, GlobalSearchScope.projectScope(project), true).findAll().sortedBy { it.name }) {
        ProgressManager.checkCanceled()
        if (!repository.isInterface) continue
        val entity = DerivedQuery.entityOf(repository) ?: continue
        val key = entity.qualifiedName ?: entity.name ?: continue
        val methods = repository.methods.mapNotNull { method ->
          if (DerivedQuery.isDerivedQueryMethod(method)) {
            val parsed = DerivedQuery.parse(method.name)
            return@mapNotNull ScannedMethod(method, parsed?.let { (it.parts + it.orderBy).map { part -> part.propertySource } }.orEmpty(), null)
          }
          val query = QUERY.firstNotNullOfOrNull { method.getAnnotation(it) } ?: return@mapNotNull null
          if (AnnotationUtil.getBooleanAttributeValue(query, "nativeQuery") == true) return@mapNotNull null
          val text = AnnotationUtil.getStringAttributeValue(query, "value") ?: return@mapNotNull null
          ScannedMethod(method, null, text)
        }
        result.getOrPut(key) { ArrayList() }.add(ScannedRepository(entity, methods))
      }
      return result
    }

    private fun compute(entity: PsiClass): Map<String, List<PsiMethod>> {
      val result = LinkedHashMap<String, MutableSet<PsiMethod>>()
      val entityName = JpaModel.entityName(entity) ?: entity.name ?: return emptyMap()
      val key = entity.qualifiedName ?: entity.name ?: return emptyMap()
      for (repository in repositoriesByEntity(entity.project)[key].orEmpty()) {
        if (!repository.entity.isEquivalentTo(entity)) continue
        for (scanned in repository.methods) {
          val method = scanned.method
          if (scanned.derived != null) {
            for (source in scanned.derived) {
              val head = DerivedQuery.resolvePath(entity, source)?.firstOrNull()?.name ?: continue
              result.getOrPut(head) { LinkedHashSet() }.add(method)
            }
            continue
          }
          val text = scanned.jpql ?: continue
          // `from User u` / `join u.orders o`: only aliases of this entity count
          val aliases = aliasPattern(entityName).findAll(text).map { it.groupValues[1] }.filter { it.lowercase() !in KEYWORDS }.toSet()
          for (alias in aliases) {
            for (m in attributePattern(alias).findAll(text)) {
              val attribute = m.groupValues[1]
              if (JpaModel.attribute(entity, attribute) != null) result.getOrPut(attribute) { LinkedHashSet() }.add(method)
            }
          }
        }
      }
      return result.mapValues { it.value.sortedBy { m -> m.name } }
    }

    private fun aliasPattern(entityName: String): Regex = ALIAS_PATTERNS.computeIfAbsent(entityName) {
      Regex("(?i)\\b(?:from|join)\\s+(?:\\w+\\.)*${Regex.escape(it)}\\s+(?:as\\s+)?(\\w+)")
    }

    private fun attributePattern(alias: String): Regex = ATTRIBUTE_PATTERNS.computeIfAbsent(alias) {
      Regex("(?<![\\w.])${Regex.escape(it)}\\.(\\w+)")
    }
  }
}
