// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring.core

import com.intellij.codeInsight.MetaAnnotationUtil
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiEnumConstant
import com.intellij.psi.PsiMethod
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import org.jetbrains.uast.UAnnotated
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UResolvable
import org.jetbrains.uast.UastCallKind
import org.jetbrains.uast.evaluateString
import org.jetbrains.uast.toUElement

/** One HTTP handler: a controller method and the URL and HTTP methods it answers. */
class SpringEndpoint(
  /** The full path, `/` joined from the class and method mappings, e.g. `/api/users/{id}`. */
  val path: String,
  /** `GET`, `POST`...; empty when the mapping accepts every method (`@RequestMapping` without `method`). */
  val methods: List<String>,
  val controllerName: String,
  val handlerName: String,
  val module: Module?,
  val handler: SmartPsiElementPointer<PsiMethod>,
)

/**
 * Finds Spring MVC handlers: methods annotated with `@RequestMapping` or one of its `@GetMapping`-style shortcuts, in
 * classes that are `@Controller`s (directly or through a meta annotation such as `@RestController`). Mappings declared
 * on an interface count when a controller implements it. Reads annotations through UAST, so Java and Kotlin
 * controllers are found the same way. Results are cached until declarations change ([SpringStructureTracker]).
 */
object SpringMvcEndpoints {
  private const val PACKAGE = "org.springframework.web.bind.annotation"
  const val REQUEST_MAPPING: String = "$PACKAGE.RequestMapping"
  private const val CONTROLLER = "org.springframework.stereotype.Controller"

  /** Shortcut annotation to the HTTP method it implies. */
  private val SHORTCUTS = mapOf(
    "$PACKAGE.GetMapping" to "GET",
    "$PACKAGE.PostMapping" to "POST",
    "$PACKAGE.PutMapping" to "PUT",
    "$PACKAGE.DeleteMapping" to "DELETE",
    "$PACKAGE.PatchMapping" to "PATCH",
  )
  val MAPPING_ANNOTATIONS: List<String> = listOf(REQUEST_MAPPING) + SHORTCUTS.keys

  fun all(project: Project): List<SpringEndpoint> =
    CachedValuesManager.getManager(project).getCachedValue(project) {
      // Mappings, controllers and the constants their paths use are declarations: typing in a method body or in a
      // non-JVM file keeps the list, where PsiModificationTracker reran the project-wide search on every keystroke.
      CachedValueProvider.Result.create(find(project), *SpringStructureTracker.deps(project))
    }

  private fun find(project: Project): List<SpringEndpoint> {
    if (!SpringProjectGate.isSpringProject(project)) return emptyList()
    val facade = JavaPsiFacade.getInstance(project)
    val libraries = GlobalSearchScope.allScope(project)
    val sources = GlobalSearchScope.projectScope(project)
    val result = ArrayList<SpringEndpoint>()
    val seen = HashSet<PsiMethod>()
    for (annotation in MAPPING_ANNOTATIONS) {
      val annotationClass = facade.findClass(annotation, libraries) ?: continue
      AnnotatedElementsSearch.searchPsiMethods(annotationClass, sources).forEach { method ->
        if (seen.add(method)) result += endpointsOf(method)
        true
      }
    }
    return result.sortedWith(compareBy({ it.path }, { it.methods.firstOrNull() ?: "" }))
  }

  /** The endpoints a mapped method declares, or none when no controller serves it. */
  fun endpointsOf(method: PsiMethod): List<SpringEndpoint> {
    val owner = method.containingClass ?: return emptyList()
    val controllers = controllersServing(owner)
    if (controllers.isEmpty()) return emptyList()
    val uMethod = method.toUElement() as? UMethod ?: return emptyList()
    val (mappingName, mapping) = MAPPING_ANNOTATIONS.firstNotNullOfOrNull { name -> uMethod.findAnnotation(name)?.let { name to it } }
                                 ?: return emptyList()
    val methodPaths = paths(mapping)
    val httpMethods = SHORTCUTS[mappingName]?.let(::listOf) ?: requestMethods(mapping)
    val pointer = SmartPointerManager.getInstance(method.project).createSmartPsiElementPointer(method)
    return controllers.flatMap { controller ->
      val classPaths = (controller.toUElement() as? UAnnotated)?.findAnnotation(REQUEST_MAPPING)?.let(::paths) ?: listOf("")
      val ownerPaths = if (controller == owner) emptyList() else (owner.toUElement() as? UAnnotated)?.findAnnotation(REQUEST_MAPPING)?.let(::paths).orEmpty()
      val prefixes = (classPaths.ifEmpty { listOf("") }).flatMap { c -> ownerPaths.ifEmpty { listOf("") }.map { join(c, it) } }
      prefixes.flatMap { prefix -> methodPaths.map { join(prefix, it) } }.distinct().map { path ->
        SpringEndpoint(path, httpMethods, controller.name ?: "?", method.name, ModuleUtilCore.findModuleForPsiElement(controller), pointer)
      }
    }
  }

  /** The class itself when it is a controller, or the controllers implementing it when it is an interface. */
  private fun controllersServing(owner: PsiClass): List<PsiClass> {
    if (isController(owner)) return listOf(owner)
    if (!owner.isInterface) return emptyList()
    return ClassInheritorsSearch.search(owner, GlobalSearchScope.projectScope(owner.project), true)
      .findAll().filter { !it.isInterface && isController(it) }
  }

  fun isController(psiClass: PsiClass): Boolean = MetaAnnotationUtil.isMetaAnnotated(psiClass, listOf(CONTROLLER))

  /** `value` or `path`; an empty mapping means the class or root path. */
  private fun paths(annotation: UAnnotation): List<String> {
    val values = sequenceOf("value", "path").flatMap { name -> annotation.findDeclaredAttributeValue(name)?.strings().orEmpty() }.toList()
    return values.ifEmpty { listOf("") }
  }

  private fun requestMethods(annotation: UAnnotation): List<String> =
    annotation.findDeclaredAttributeValue("method")?.let { value -> flatten(value).mapNotNull { constantName(it) } }.orEmpty()

  private fun UExpression.strings(): List<String> = flatten(this).mapNotNull { it.evaluateString() ?: it.evaluate() as? String }

  /** Array initializers (`{"a", "b"}`, `["a", "b"]`, `arrayOf(...)`) to their elements; anything else to itself. */
  private fun flatten(expression: UExpression): List<UExpression> =
    if (expression is UCallExpression && (expression.kind == UastCallKind.NESTED_ARRAY_INITIALIZER ||
                                          expression.kind == UastCallKind.NEW_ARRAY_WITH_INITIALIZER ||
                                          expression.methodName == "arrayOf"))
      expression.valueArguments.flatMap(::flatten)
    else listOf(expression)

  private fun constantName(expression: UExpression): String? =
    ((expression as? UResolvable)?.resolve() as? PsiEnumConstant)?.name
    ?: expression.asSourceString().substringAfterLast('.').takeIf { it.isNotBlank() && it.all(Char::isLetter) && it.uppercase() == it }

  /** `/users/{id:[0-9]+}` reads as `/users/{id}`: path variables without their regular expressions. */
  fun readablePath(path: String): String {
    val out = StringBuilder()
    var i = 0
    while (i < path.length) {
      val c = path[i]
      if (c == '{') {
        // Copy the variable name, skip `:regex` up to the matching brace (the regex may hold `{n}` itself)
        var depth = 1
        var j = i + 1
        val name = StringBuilder()
        var inRegex = false
        while (j < path.length && depth > 0) {
          when (path[j]) {
            '{' -> depth++
            '}' -> depth--
            ':' -> if (depth == 1) inRegex = true
          }
          if (depth > 0 && !inRegex) name.append(path[j])
          j++
        }
        out.append('{').append(name).append('}')
        i = j
      }
      else {
        out.append(c)
        i++
      }
    }
    return out.toString()
  }

  /** Joins two mapping segments the way Spring does: one `/` between them, always a leading `/`. */
  fun join(prefix: String, path: String): String {
    val joined = listOf(prefix, path).map { it.trim().trim('/') }.filter { it.isNotEmpty() }.joinToString("/")
    return "/$joined"
  }
}
