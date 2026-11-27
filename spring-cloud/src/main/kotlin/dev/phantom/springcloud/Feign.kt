// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.springcloud

import com.intellij.codeInsight.AnnotationUtil
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiVariable
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker

/** An HTTP endpoint: method null means any method (a @RequestMapping without `method`). */
data class Endpoint(val method: String?, val path: String) {
  fun matches(other: Endpoint): Boolean = path == other.path && (method == null || other.method == null || method == other.method)
}

/** Matches @FeignClient interface methods with Spring MVC controller methods of the same project by HTTP method and path. */
object Feign {
  const val FEIGN_CLIENT = "org.springframework.cloud.openfeign.FeignClient"
  private const val WEB = "org.springframework.web.bind.annotation."
  private val CONTROLLERS = listOf("${WEB}RestController", "org.springframework.stereotype.Controller")
  private val MAPPINGS = mapOf(
    "${WEB}RequestMapping" to null,
    "${WEB}GetMapping" to "GET",
    "${WEB}PostMapping" to "POST",
    "${WEB}PutMapping" to "PUT",
    "${WEB}DeleteMapping" to "DELETE",
    "${WEB}PatchMapping" to "PATCH",
  )
  private val VARIABLE = Regex("\\{[^/]*}")

  fun isFeignClient(c: PsiClass?): Boolean = c != null && c.isInterface && c.hasAnnotation(FEIGN_CLIENT)

  fun isController(c: PsiClass?): Boolean = c != null && !c.isInterface && CONTROLLERS.any { c.hasAnnotation(it) }

  /** `/api//users/{id}/` and `api/users/{userId:\\d+}` are both `/api/users/{}`. */
  fun normalize(path: String): String = "/" + path.split('/').filter { it.isNotEmpty() }.joinToString("/") { it.replace(VARIABLE, "{}") }

  /** Endpoints of a Feign client method; the client's `path` attribute is the prefix. */
  fun feignEndpoints(method: PsiMethod): List<Endpoint> {
    val client = method.containingClass ?: return emptyList()
    val prefixes = client.getAnnotation(FEIGN_CLIENT)?.let { strings(it, "path") }.orEmpty().ifEmpty { listOf("") }
    // Feign's SpringMvcContract also reads a class-level @RequestMapping, though Spring Cloud warns against it.
    val classPrefixes = classPrefixes(client).filter { it.isNotEmpty() }.ifEmpty { listOf("") }
    return endpoints(method, prefixes.flatMap { p -> classPrefixes.map { "$p/$it" } })
  }

  /** Endpoints of a controller method; mappings may be declared on an interface the controller implements. */
  fun controllerEndpoints(method: PsiMethod): List<Endpoint> {
    val controller = method.containingClass ?: return emptyList()
    return endpoints(method, classPrefixes(controller))
  }

  fun controllersFor(method: PsiMethod): List<PsiMethod> {
    val wanted = feignEndpoints(method)
    if (wanted.isEmpty()) return emptyList()
    return cached(method.project, CONTROLLERS, ::isController, ::controllerEndpoints)
      .filter { (_, endpoints) -> endpoints.any { e -> wanted.any { it.matches(e) } } }.map { it.first }
  }

  fun feignClientsFor(method: PsiMethod): List<PsiMethod> {
    val wanted = controllerEndpoints(method)
    if (wanted.isEmpty()) return emptyList()
    return cached(method.project, listOf(FEIGN_CLIENT), ::isFeignClient, ::feignEndpoints)
      .filter { (_, endpoints) -> endpoints.any { e -> wanted.any { it.matches(e) } } }.map { it.first }
  }

  private fun endpoints(method: PsiMethod, prefixes: List<String>): List<Endpoint> {
    fun mapping(m: PsiMethod): Pair<PsiAnnotation, String?>? =
      MAPPINGS.entries.firstNotNullOfOrNull { (name, http) -> m.getAnnotation(name)?.let { it to http } }
    val (annotation, fixed) = mapping(method) ?: method.findSuperMethods().firstNotNullOfOrNull { mapping(it) } ?: return emptyList()
    val methods = fixed?.let { listOf(it) }
      ?: AnnotationUtil.arrayAttributeValues(annotation.findDeclaredAttributeValue("method"))
        .mapNotNull { (it as? PsiReferenceExpression)?.referenceName }.ifEmpty { listOf(null) }
    val paths = strings(annotation, "value", "path").ifEmpty { listOf("") }
    return prefixes.flatMap { prefix -> paths.flatMap { path -> methods.map { Endpoint(it, normalize("$prefix/$path")) } } }
  }

  private fun classPrefixes(c: PsiClass): List<String> {
    val annotation = AnnotationUtil.findAnnotationInHierarchy(c, setOf("${WEB}RequestMapping")) ?: return listOf("")
    return strings(annotation, "value", "path").ifEmpty { listOf("") }
  }

  /**
   * Methods with endpoints of the classes carrying one of [annotations] in the project, recomputed after any PSI change.
   * Empty without the annotation classes on the class path, so projects without Spring Cloud or Spring MVC pay nothing.
   */
  private fun cached(
    project: Project,
    annotations: List<String>,
    accept: (PsiClass) -> Boolean,
    endpoints: (PsiMethod) -> List<Endpoint>,
  ): List<Pair<PsiMethod, List<Endpoint>>> {
    val key = annotations.joinToString()
    val all = CachedValuesManager.getManager(project).getCachedValue(project) {
      CachedValueProvider.Result.create(HashMap<String, List<Pair<PsiMethod, List<Endpoint>>>>(), PsiModificationTracker.getInstance(project))
    }
    synchronized(all) { all[key]?.let { return it } }
    val facade = JavaPsiFacade.getInstance(project)
    val scope = GlobalSearchScope.projectScope(project)
    val result = annotations.mapNotNull { facade.findClass(it, GlobalSearchScope.allScope(project)) }
      .flatMap { AnnotatedElementsSearch.searchPsiClasses(it, scope).findAll() }
      .distinct().filter(accept)
      .flatMap { c -> c.methods.map { it to endpoints(it) } }
      .filter { it.second.isNotEmpty() }
    synchronized(all) { all[key] = result }
    return result
  }
}

private fun strings(annotation: PsiAnnotation, vararg names: String): List<String> {
  val helper = JavaPsiFacade.getInstance(annotation.project).constantEvaluationHelper
  for (name in names) {
    val value = annotation.findDeclaredAttributeValue(name) ?: continue
    return AnnotationUtil.arrayAttributeValues(value).mapNotNull { v ->
      // computeConstantExpression gives null for some resolved String constants (seen in light tests); evaluate the initializer then.
      val constant = helper.computeConstantExpression(v)
        ?: ((v as? PsiReferenceExpression)?.resolve() as? PsiVariable)?.initializer?.let { helper.computeConstantExpression(it) }
      constant as? String
    }
  }
  return emptyList()
}
