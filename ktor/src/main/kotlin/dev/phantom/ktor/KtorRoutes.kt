// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ktor

import com.intellij.icons.AllIcons
import com.intellij.java.library.JavaLibraryUtil
import com.intellij.microservices.endpoints.EndpointsFilter
import com.intellij.microservices.endpoints.EndpointsProvider
import com.intellij.microservices.endpoints.FrameworkPresentation
import com.intellij.microservices.endpoints.HTTP_SERVER_TYPE
import com.intellij.microservices.endpoints.ModuleEndpointsFilter
import com.intellij.microservices.endpoints.SearchScopeEndpointsFilter
import com.intellij.microservices.endpoints.presentation.HttpMethodPresentation
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ModificationTracker
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UFile
import org.jetbrains.uast.ULambdaExpression
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.evaluateString
import org.jetbrains.uast.getParentOfType
import org.jetbrains.uast.getUastParentOfType
import org.jetbrains.uast.toUElement
import org.jetbrains.uast.toUElementOfType
import org.jetbrains.uast.visitor.AbstractUastVisitor

/** One Ktor route handler: the full path, its HTTP method, and the call that declares it. */
class KtorRoute(val path: String, val method: String, val location: String, val module: Module?, val call: SmartPsiElementPointer<PsiElement>)

/**
 * Ktor routes from the routing DSL: `get("/x") { }`, `post { }` and the other verbs, under `route("/prefix") { }`.
 * Routes in extension functions (`fun Route.userRoutes() { get("/users") { } }`) take the prefixes of the places that
 * call the function. Only Kotlin files that mention Ktor are read; results are cached until the next PSI change.
 */
object KtorRoutes {
  private const val ROUTING_PACKAGE = "io.ktor.server.routing"
  private val MARKERS = listOf("$ROUTING_PACKAGE.Route", "$ROUTING_PACKAGE.RoutingKt", "$ROUTING_PACKAGE.RoutingBuilderKt")
  private val VERBS = mapOf("get" to "GET", "post" to "POST", "put" to "PUT", "delete" to "DELETE", "patch" to "PATCH", "head" to "HEAD", "options" to "OPTIONS")
  private const val MAX_DEPTH = 6

  fun isKtorProject(project: Project): Boolean {
    if (project.isDefault || project.isDisposed) return false
    if (MARKERS.any { JavaLibraryUtil.hasLibraryClass(project, it) }) return true
    return ApplicationManager.getApplication().isUnitTestMode &&
           MARKERS.any { JavaPsiFacade.getInstance(project).findClass(it, GlobalSearchScope.allScope(project)) != null }
  }

  fun all(project: Project): List<KtorRoute> = CachedValuesManager.getManager(project).getCachedValue(project) {
    CachedValueProvider.Result.create(find(project), PsiModificationTracker.getInstance(project))
  }

  private fun find(project: Project): List<KtorRoute> {
    if (!isKtorProject(project)) return emptyList()
    val result = ArrayList<KtorRoute>()
    val psiManager = PsiManager.getInstance(project)
    PsiSearchHelper.getInstance(project).processAllFilesWithWord("ktor", GlobalSearchScope.projectScope(project), { psiFile ->
      if (psiFile.language.id == "kotlin") {
        psiManager.findFile(psiFile.virtualFile)?.toUElementOfType<UFile>()?.accept(object : AbstractUastVisitor() {
          override fun visitCallExpression(node: UCallExpression): Boolean {
            routesOf(node)?.let { result += it }
            return false
          }
        })
      }
      true
    }, true)
    return result.sortedWith(compareBy({ it.path }, { it.method }))
  }

  /** The routes a verb call declares: one per prefix chain that reaches it. */
  private fun routesOf(call: UCallExpression): List<KtorRoute>? {
    val method = VERBS[call.methodName] ?: return null
    if (!isRouting(call)) return null
    val own = call.valueArguments.firstOrNull { it !is ULambdaExpression }?.evaluateString() ?: if (call.valueArguments.any { it !is ULambdaExpression }) return null else ""
    val psi = call.sourcePsi ?: return null
    val pointer = SmartPointerManager.createPointer(psi)
    val module = ModuleUtilCore.findModuleForPsiElement(psi)
    val location = call.getParentOfType(UMethod::class.java)?.name ?: ""
    return prefixes(call, 0).map { prefix -> KtorRoute(join(prefix + own), method, location, module, pointer) }
  }

  private fun isRouting(call: UCallExpression): Boolean {
    val resolved = call.resolve() ?: return false
    val owner = resolved.containingClass?.qualifiedName ?: return false
    return owner.startsWith("$ROUTING_PACKAGE.")
  }

  /** Path segments above [element]: enclosing `route("…")` blocks, and the callers of an enclosing `Route.` extension. */
  private fun prefixes(element: UElement, depth: Int): List<List<String>> {
    if (depth > MAX_DEPTH) return listOf(emptyList())
    var current: UElement? = element.uastParent
    val segments = ArrayList<String>()
    while (current != null) {
      if (current is ULambdaExpression) {
        val owner = current.uastParent as? UCallExpression
        if (owner != null && owner.methodName == "route" && isRouting(owner)) {
          val path = owner.valueArguments.firstOrNull { it !is ULambdaExpression }?.evaluateString()
          if (path != null) segments.add(0, path)
        }
        if (owner != null && owner.methodName == "routing") return listOf(segments)
      }
      if (current is UMethod) {
        val callers = callersOf(current)
        if (callers.isEmpty()) return listOf(segments)
        return callers.flatMap { caller -> prefixes(caller, depth + 1).map { it + segments } }
      }
      current = current.uastParent
    }
    return listOf(segments)
  }

  private fun callersOf(method: UMethod): List<UCallExpression> {
    val psi = method.sourcePsi ?: return emptyList()
    val scope = GlobalSearchScope.projectScope(psi.project)
    return ReferencesSearch.search(psi, scope).findAll().mapNotNull { it.element.getUastParentOfType(UCallExpression::class.java) }
  }

  /** Ktor joins segments with single slashes; `{id}` and `*` stay as they are. */
  fun join(segments: List<String>): String =
    "/" + segments.flatMap { it.split('/') }.filter { it.isNotEmpty() }.joinToString("/")
}

class KtorEndpointsGroup(val module: Module?, val routes: List<KtorRoute>)

/** Ktor routes for the platform's endpoints API, which Phantom's Endpoints tool window reads. */
class KtorEndpointsProvider : EndpointsProvider<KtorEndpointsGroup, KtorRoute> {
  override val endpointType = HTTP_SERVER_TYPE

  override val presentation = FrameworkPresentation("Ktor", "Ktor", AllIcons.Webreferences.Server)

  override fun getStatus(project: Project): EndpointsProvider.Status = when {
    !KtorRoutes.isKtorProject(project) -> EndpointsProvider.Status.UNAVAILABLE
    DumbService.isDumb(project) || KtorRoutes.all(project).isEmpty() -> EndpointsProvider.Status.AVAILABLE
    else -> EndpointsProvider.Status.HAS_ENDPOINTS
  }

  override fun getEndpointGroups(project: Project, filter: EndpointsFilter): Iterable<KtorEndpointsGroup> {
    if (DumbService.isDumb(project)) return emptyList()
    return KtorRoutes.all(project).filter { route ->
      when (filter) {
        is ModuleEndpointsFilter -> route.module == filter.module
        is SearchScopeEndpointsFilter -> route.call.virtualFile?.let { filter.contentSearchScope.contains(it) } ?: false
        else -> true
      }
    }.groupBy { it.module }.map { (module, routes) -> KtorEndpointsGroup(module, routes) }
  }

  override fun getEndpoints(group: KtorEndpointsGroup): Iterable<KtorRoute> = group.routes

  override fun isValidEndpoint(group: KtorEndpointsGroup, endpoint: KtorRoute): Boolean = endpoint.call.element?.isValid == true

  override fun getEndpointPresentation(group: KtorEndpointsGroup, endpoint: KtorRoute): ItemPresentation =
    HttpMethodPresentation(endpoint.path, listOf(endpoint.method), endpoint.location, AllIcons.Webreferences.Server)

  override fun getModificationTracker(project: Project): ModificationTracker = PsiModificationTracker.getInstance(project)

  override fun getNavigationElement(group: KtorEndpointsGroup, endpoint: KtorRoute): PsiElement? = endpoint.call.element

  override fun getDocumentationElement(group: KtorEndpointsGroup, endpoint: KtorRoute): PsiElement? = endpoint.call.element
}
