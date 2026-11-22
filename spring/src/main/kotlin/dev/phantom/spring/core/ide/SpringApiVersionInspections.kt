// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring.core.ide

import com.intellij.codeInspection.AbstractBaseUastLocalInspectionTool
import com.intellij.codeInspection.LocalInspectionToolSession
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.java.library.JavaLibraryUtil
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.uast.UastHintedVisitorAdapter
import dev.phantom.spring.core.PhantomSpringBundle
import dev.phantom.spring.core.SpringCacheDeps
import dev.phantom.spring.core.SpringConfigKeys
import dev.phantom.spring.core.SpringMvcEndpoints
import org.jetbrains.annotations.NonNls
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.evaluateString
import org.jetbrains.uast.visitor.AbstractUastNonRecursiveVisitor

/**
 * API versioning of Spring Framework 7: `@RequestMapping(version = ...)` and its shortcuts need a versioning strategy
 * (`configureApiVersioning` of a `WebMvcConfigurer`/`WebFluxConfigurer`, or Spring Boot's `spring.mvc.apiversion.*` /
 * `spring.webflux.apiversion.*`), and the default parser reads `major[.minor[.patch]]`, optionally `+` for "this
 * version and later". Everything here is off unless the versioning API is on the classpath.
 */
object SpringApiVersioning {
  @NonNls private val CONFIGURERS = listOf("org.springframework.web.servlet.config.annotation.ApiVersionConfigurer",
                                           "org.springframework.web.reactive.config.ApiVersionConfigurer")
  @NonNls private const val PARSER = "org.springframework.web.accept.ApiVersionParser"
  @NonNls private const val STRATEGY = "org.springframework.web.accept.ApiVersionStrategy"
  @NonNls private val PROPERTIES = listOf("spring.mvc.apiversion", "spring.webflux.apiversion")
  private val SEMANTIC = Regex("""[vV]?\d+(\.\d+){0,2}\+?""")

  fun isAvailable(project: Project): Boolean {
    if (project.isDefault || project.isDisposed) return false
    if (CONFIGURERS.any { JavaLibraryUtil.hasLibraryClass(project, it) }) return true
    return ApplicationManager.getApplication().isUnitTestMode &&
           CONFIGURERS.any { JavaPsiFacade.getInstance(project).findClass(it, GlobalSearchScope.allScope(project)) != null }
  }

  /** The `version` the mapping declares, as a string literal. */
  fun versionOf(annotation: UAnnotation): Pair<String, com.intellij.psi.PsiElement>? {
    if (annotation.qualifiedName !in SpringMvcEndpoints.MAPPING_ANNOTATIONS) return null
    val value = annotation.findDeclaredAttributeValue("version") ?: return null
    val text = value.evaluateString() ?: return null
    return text to (value.sourcePsi ?: return null)
  }

  fun isSemantic(version: String): Boolean = SEMANTIC.matches(version.trim())

  /** Project code that configures versioning: uses a configurer, implements a strategy, or sets the Boot properties. */
  fun isConfigured(project: Project): Boolean =
    configuredInCode(project) || SpringConfigKeys.get(project).derived("apiVersion") { keys -> PROPERTIES.any(keys::contains) }

  // searched on code and library changes only; the properties come from the config keys, read on config changes
  private fun configuredInCode(project: Project): Boolean = CachedValuesManager.getManager(project).getCachedValue(project) {
    val facade = JavaPsiFacade.getInstance(project)
    val all = GlobalSearchScope.allScope(project)
    val scope = GlobalSearchScope.projectScope(project)
    // Spring's own sources (attached or, in tests, stubbed) mention the configurer everywhere; only the project's code counts
    fun own(element: com.intellij.psi.PsiElement) = (element.containingFile as? com.intellij.psi.PsiClassOwner)?.packageName?.startsWith("org.springframework") != true
    val configured = CONFIGURERS.any { fqn -> facade.findClass(fqn, all)?.let { c -> ReferencesSearch.search(c, scope).findAll().any { own(it.element) } } == true } ||
                     facade.findClass(STRATEGY, all)?.let { c -> ClassInheritorsSearch.search(c, scope, true).findAll().any(::own) } == true
    CachedValueProvider.Result.create(configured, SpringCacheDeps.jvm(project), ProjectRootManager.getInstance(project))
  }

  /** A project-defined parser may read any format. */
  fun hasCustomParser(project: Project): Boolean = CachedValuesManager.getManager(project).getCachedValue(project) {
    val parser = JavaPsiFacade.getInstance(project).findClass(PARSER, GlobalSearchScope.allScope(project))
    val custom = parser != null && ClassInheritorsSearch.search(parser, GlobalSearchScope.projectScope(project), true).findFirst() != null
    CachedValueProvider.Result.create(custom, PsiModificationTracker.MODIFICATION_COUNT)
  }
}

/** A mapping with `version` in a project with no API versioning configured: the context fails at startup. */
class SpringApiVersionNotConfiguredInspection : AbstractBaseUastLocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    if (!SpringApiVersioning.isAvailable(holder.project)) return PsiElementVisitor.EMPTY_VISITOR
    return UastHintedVisitorAdapter.create(holder.file.language, object : AbstractUastNonRecursiveVisitor() {
      override fun visitAnnotation(node: UAnnotation): Boolean {
        SpringApiVersioning.versionOf(node) ?: return true
        if (SpringApiVersioning.isConfigured(holder.project)) return true
        // on the annotation's name, so a syntax error on the version value stays visible next to it
        val anchor = node.uastAnchor?.sourcePsi ?: node.sourcePsi ?: return true
        holder.registerProblem(anchor, PhantomSpringBundle.message("inspection.api.version.unconfigured.message"))
        return true
      }
    }, arrayOf(UAnnotation::class.java), true)
  }
}

/** A `version` the default semantic parser cannot read, such as `1.x` or `v1-beta`. */
class SpringApiVersionSyntaxInspection : AbstractBaseUastLocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    if (!SpringApiVersioning.isAvailable(holder.project)) return PsiElementVisitor.EMPTY_VISITOR
    return UastHintedVisitorAdapter.create(holder.file.language, object : AbstractUastNonRecursiveVisitor() {
      override fun visitAnnotation(node: UAnnotation): Boolean {
        val (version, anchor) = SpringApiVersioning.versionOf(node) ?: return true
        if (version.contains("\${") || SpringApiVersioning.isSemantic(version)) return true
        if (SpringApiVersioning.hasCustomParser(holder.project)) return true
        holder.registerProblem(anchor, PhantomSpringBundle.message("inspection.api.version.syntax.message", version))
        return true
      }
    }, arrayOf(UAnnotation::class.java), true)
  }
}
