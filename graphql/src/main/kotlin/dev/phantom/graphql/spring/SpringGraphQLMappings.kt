// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.graphql.spring

import com.intellij.codeInsight.AnnotationUtil
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiType
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiUtil

/** A schema coordinate: the GraphQL type and field a Spring GraphQL handler method answers. */
data class SchemaCoordinate(val typeName: String, val fieldName: String) {
  override fun toString(): String = "$typeName.$fieldName"
}

/**
 * Spring GraphQL's annotated controllers (`org.springframework.graphql.data.method.annotation`), resolved the way
 * `AnnotatedControllerConfigurer` does: the field is the annotation's name/field/value or the method name, the type is
 * Query/Mutation/Subscription for the shortcut annotations, otherwise `typeName`, the class-level @SchemaMapping, or the
 * type of the source parameter (the element type of the list for @BatchMapping).
 */
object SpringGraphQLMappings {
  private const val PACKAGE = "org.springframework.graphql.data.method.annotation"
  const val QUERY = "$PACKAGE.QueryMapping"
  const val MUTATION = "$PACKAGE.MutationMapping"
  const val SUBSCRIPTION = "$PACKAGE.SubscriptionMapping"
  const val SCHEMA = "$PACKAGE.SchemaMapping"
  const val BATCH = "$PACKAGE.BatchMapping"

  val ANNOTATIONS = listOf(QUERY, MUTATION, SUBSCRIPTION, SCHEMA, BATCH)

  private val ROOT_TYPES = mapOf(QUERY to "Query", MUTATION to "Mutation", SUBSCRIPTION to "Subscription")

  /** Whether Spring GraphQL is on the project's classpath; everything else here is skipped when it is not. */
  fun isAvailable(project: Project): Boolean = CachedValuesManager.getManager(project).getCachedValue(project) {
    val found = JavaPsiFacade.getInstance(project).findClass(SCHEMA, GlobalSearchScope.allScope(project)) != null
    CachedValueProvider.Result.create(found, PsiModificationTracker.getInstance(project))
  }

  /** Handler methods of the project by the coordinate they map, recomputed after PSI changes. */
  fun handlers(project: Project): Map<SchemaCoordinate, List<PsiMethod>> = CachedValuesManager.getManager(project).getCachedValue(project) {
    CachedValueProvider.Result.create(collectHandlers(project), PsiModificationTracker.getInstance(project))
  }

  private fun collectHandlers(project: Project): Map<SchemaCoordinate, List<PsiMethod>> {
    if (!isAvailable(project)) return emptyMap()
    val facade = JavaPsiFacade.getInstance(project)
    val all = GlobalSearchScope.allScope(project)
    val result = LinkedHashMap<SchemaCoordinate, MutableList<PsiMethod>>()
    for (annotation in ANNOTATIONS) {
      val annotationClass = facade.findClass(annotation, all) ?: continue
      AnnotatedElementsSearch.searchPsiMethods(annotationClass, GlobalSearchScope.projectScope(project)).forEach { method ->
        coordinate(method, annotation)?.let { result.getOrPut(it) { mutableListOf() } += method }
      }
    }
    return result
  }

  /** The coordinates [method] maps, one per mapping annotation it carries. */
  fun coordinates(method: PsiMethod): List<SchemaCoordinate> = ANNOTATIONS.mapNotNull { coordinate(method, it) }

  private fun coordinate(method: PsiMethod, annotationName: String): SchemaCoordinate? {
    val annotation = method.getAnnotation(annotationName) ?: return null
    val fieldAttribute = if (annotationName in ROOT_TYPES) "name" else "field"
    val field = attribute(annotation, fieldAttribute) ?: attribute(annotation, "value") ?: method.name
    val type = ROOT_TYPES[annotationName]
      ?: attribute(annotation, "typeName")
      ?: method.containingClass?.getAnnotation(SCHEMA)?.let { attribute(it, "typeName") }
      ?: sourceTypeName(method, batch = annotationName == BATCH)
      ?: return null
    return SchemaCoordinate(type, field)
  }

  private fun attribute(annotation: PsiAnnotation, name: String): String? =
    AnnotationUtil.getDeclaredStringAttributeValue(annotation, name)?.takeIf { it.isNotBlank() }

  /** The simple name of the source object's class: the first parameter, or for a batch its collection's element type. */
  private fun sourceTypeName(method: PsiMethod, batch: Boolean): String? {
    val first: PsiType = method.parameterList.parameters.firstOrNull()?.type ?: return null
    val source = if (batch) (first as? PsiClassType)?.parameters?.firstOrNull() else first
    return source?.let { PsiUtil.resolveClassInType(it)?.name }
  }
}
