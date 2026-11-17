// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.jpa.core

import com.intellij.codeInsight.AnnotationUtil
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.psi.*
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.InheritanceUtil
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.xml.util.XmlStringUtil
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UField
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UParameter
import org.jetbrains.uast.getUParentForIdentifier
import javax.swing.Icon

/** The JPQL a Spring Data derived query method stands for, written out for a tooltip. */
object DerivedJpql {
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
    val allIgnoreCase = Regex("All(IgnoreCase|IgnoringCase)(OrderBy|$)").containsMatchIn(methodName)
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
      val ignoreCase = allIgnoreCase || Regex("(IgnoreCase|IgnoringCase)$").containsMatchIn(part.source)
      where.append(condition(path, part.operator, parameter, ignoreCase) ?: return null)
      parameter += part.arguments
    }
    val order = parsed.orderBy.map { part ->
      val members = DerivedQuery.resolvePath(entity, part.propertySource) ?: return null
      (pathOf(alias, members) ?: return null) + if (part.operator == "Desc") " desc" else " asc"
    }
    val limit = Regex("(First|Top)(\\d*)").find(subject)?.let { m -> " (first ${m.groupValues[2].ifEmpty { "1" }})" }.orEmpty()
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
 */
class DerivedQueryLineMarkerProvider : LineMarkerProviderDescriptor() {
  override fun getName(): String = PhantomJpaBundle.message("gutter.derived.name")

  override fun getIcon(): Icon = AllIcons.Gutter.ImplementedMethod

  override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
    val (method, entity) = repositoryMethod(element) ?: return null
    if (!DerivedQuery.isDerivedQueryMethod(method)) return null
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

/** On a repository method with `@EntityGraph(attributePaths = ...)`: the attributes the graph fetches. */
class EntityGraphLineMarkerProvider : LineMarkerProviderDescriptor() {
  override fun getName(): String = PhantomJpaBundle.message("gutter.graph.name")

  override fun getIcon(): Icon = AllIcons.Nodes.DataTables

  override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
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
 * Computed per entity on first need and kept until the next PSI change.
 */
class EntityAttributeUsagesLineMarkerProvider : LineMarkerProviderDescriptor() {
  override fun getName(): String = PhantomJpaBundle.message("gutter.attribute.name")

  override fun getIcon(): Icon = AllIcons.Gutter.ImplementingMethod

  override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
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

  companion object {
    private val QUERY = listOf("org.springframework.data.jpa.repository.Query", "org.springframework.data.jpa.repository.NativeQuery")

    /** Attribute name of [entity] → repository methods that query it. */
    fun usages(entity: PsiClass): Map<String, List<PsiMethod>> = CachedValuesManager.getCachedValue(entity) {
      CachedValueProvider.Result.create(compute(entity), PsiModificationTracker.MODIFICATION_COUNT)
    }

    private fun compute(entity: PsiClass): Map<String, List<PsiMethod>> {
      val result = LinkedHashMap<String, MutableSet<PsiMethod>>()
      val entityName = JpaModel.entityName(entity) ?: entity.name ?: return emptyMap()
      for (repository in JpaRepositoryLineMarkerProvider.repositoriesOf(entity)) {
        for (method in repository.methods) {
          if (DerivedQuery.isDerivedQueryMethod(method)) {
            val parsed = DerivedQuery.parse(method.name) ?: continue
            for (part in parsed.parts + parsed.orderBy) {
              val head = DerivedQuery.resolvePath(entity, part.propertySource)?.firstOrNull()?.name ?: continue
              result.getOrPut(head) { LinkedHashSet() }.add(method)
            }
            continue
          }
          val query = QUERY.firstNotNullOfOrNull { method.getAnnotation(it) } ?: continue
          if (AnnotationUtil.getBooleanAttributeValue(query, "nativeQuery") == true) continue
          val text = AnnotationUtil.getStringAttributeValue(query, "value") ?: continue
          // `from User u` / `join u.orders o`: only aliases of this entity count
          val aliases = Regex("(?i)\\b(?:from|join)\\s+(?:\\w+\\.)*${Regex.escape(entityName)}\\s+(?:as\\s+)?(\\w+)").findAll(text)
            .map { it.groupValues[1] }.filter { it.lowercase() !in KEYWORDS }.toSet()
          for (alias in aliases) {
            for (m in Regex("(?<![\\w.])${Regex.escape(alias)}\\.(\\w+)").findAll(text)) {
              val attribute = m.groupValues[1]
              if (JpaModel.attribute(entity, attribute) != null) result.getOrPut(attribute) { LinkedHashSet() }.add(method)
            }
          }
        }
      }
      return result.mapValues { it.value.sortedBy { m -> m.name } }
    }

    private val KEYWORDS = setOf("where", "join", "left", "inner", "order", "group", "on", "fetch", "having")
  }
}
