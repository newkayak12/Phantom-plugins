// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring.core.ide

import dev.phantom.spring.core.GhostMode
import com.intellij.codeInsight.AnnotationUtil
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import dev.phantom.spring.core.PhantomSpringBundle
import dev.phantom.spring.core.SpringProjectGate
import dev.phantom.spring.core.SpringStructureTracker
import org.jetbrains.annotations.NonNls
import org.jetbrains.uast.*
import javax.swing.Icon

/** Methods that read, write or evict a cache, by cache name; names on the method or, failing that, on `@CacheConfig`. */
object SpringCacheOperations {
  @NonNls private const val PACKAGE = "org.springframework.cache.annotation"
  @NonNls val OPERATIONS: List<String> = listOf("$PACKAGE.Cacheable", "$PACKAGE.CachePut", "$PACKAGE.CacheEvict")
  @NonNls private const val CACHING = "$PACKAGE.Caching"
  @NonNls private const val CACHE_CONFIG = "$PACKAGE.CacheConfig"

  class Operation(val method: PsiMethod, val kind: String, val caches: Set<String>)

  /** The operations one method declares, with the caches each names. */
  fun of(method: PsiMethod): List<Operation> {
    val project = method.project
    val annotations = OPERATIONS.mapNotNull { method.getAnnotation(it) }.toMutableList()
    method.getAnnotation(CACHING)?.let { caching ->
      for (attribute in listOf("cacheable", "put", "evict")) {
        AnnotationUtil.arrayAttributeValues(caching.findDeclaredAttributeValue(attribute)).filterIsInstance<PsiAnnotation>().forEach(annotations::add)
      }
    }
    if (annotations.isEmpty()) return emptyList()
    val defaults = method.containingClass?.getAnnotation(CACHE_CONFIG)?.let { names(project, it, listOf("cacheNames")) }.orEmpty()
    return annotations.mapNotNull { a ->
      val caches = names(project, a, listOf("value", "cacheNames")).ifEmpty { defaults }
      if (caches.isEmpty()) null else Operation(method, a.qualifiedName?.substringAfterLast('.') ?: return@mapNotNull null, caches)
    }
  }

  private fun names(project: Project, annotation: PsiAnnotation, attributes: List<String>): Set<String> {
    val helper = JavaPsiFacade.getInstance(project).constantEvaluationHelper
    return attributes.flatMap { attribute ->
      AnnotationUtil.arrayAttributeValues(annotation.findDeclaredAttributeValue(attribute)).mapNotNull { helper.computeConstantExpression(it) as? String }
    }.filter { it.isNotBlank() }.toSet()
  }

  /** Every cache operation in project sources, cached until declarations change ([SpringStructureTracker]). */
  fun all(project: Project): List<Operation> = CachedValuesManager.getManager(project).getCachedValue(project) {
    val facade = JavaPsiFacade.getInstance(project)
    val all = GlobalSearchScope.allScope(project)
    val scope = GlobalSearchScope.projectScope(project)
    val methods = LinkedHashSet<PsiMethod>()
    for (fqn in OPERATIONS + CACHING) {
      val annotation = facade.findClass(fqn, all) ?: continue
      AnnotatedElementsSearch.searchPsiMethods(annotation, scope).forEach { methods += it }
    }
    CachedValueProvider.Result.create(methods.flatMap(::of), *SpringStructureTracker.deps(project))
  }
}

/** On a method that uses a cache: the other methods that read, put into or evict the same caches. */
class SpringCacheLineMarkerProvider : LineMarkerProviderDescriptor() {
  override fun getName(): String = PhantomSpringBundle.message("cache.line.marker.name")

  override fun getIcon(): Icon = AllIcons.Nodes.DataTables

  override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {

    if (GhostMode.enabled) return null
    if (element.firstChild != null) return null
    val owner = element.parent as? PsiNameIdentifierOwner ?: return null
    if (owner.nameIdentifier != element) return null
    // the project gate before the UAST conversion: outside Spring projects no leaf pays for it
    if (!SpringProjectGate.isSpringProject(element.project)) return null
    val method = getUParentForIdentifier(element) as? UMethod ?: return null
    // cheap check before resolving anything: one of the caching annotations by short name
    if (method.uAnnotations.none { it.qualifiedName?.startsWith("org.springframework.cache.annotation.Cach") == true }) return null
    val psiMethod = method.javaPsi
    val caches = SpringCacheOperations.of(psiMethod).flatMap { it.caches }.toSet().ifEmpty { return null }
    val label = caches.sorted().joinToString(", ")
    val project = element.project
    return NavigationGutterIconBuilder.create(AllIcons.Nodes.DataTables)
      .setTargets(NotNullLazyValue.lazy {
        SpringCacheOperations.all(project).filter { it.method != psiMethod && it.caches.any(caches::contains) }
          .map { it.method }.distinct().map { it.navigationElement }
      })
      .setTooltipText(PhantomSpringBundle.message("cache.gutter.tooltip", label))
      .setPopupTitle(PhantomSpringBundle.message("cache.gutter.tooltip", label))
      .setEmptyPopupText(PhantomSpringBundle.message("cache.gutter.empty", label))
      .createLineMarkerInfo(element)
  }
}

/** A clock on `@Scheduled` methods with the trigger as the tooltip: cron, fixed rate or fixed delay. */
class SpringScheduledLineMarkerProvider : LineMarkerProviderDescriptor() {
  override fun getName(): String = PhantomSpringBundle.message("scheduled.line.marker.name")

  override fun getIcon(): Icon = AllIcons.Vcs.History

  override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {

    if (GhostMode.enabled) return null
    if (element.firstChild != null) return null
    val owner = element.parent as? PsiNameIdentifierOwner ?: return null
    if (owner.nameIdentifier != element) return null
    if (!SpringProjectGate.isSpringProject(element.project)) return null
    val method = getUParentForIdentifier(element) as? UMethod ?: return null
    val scheduled = method.findAnnotation(SpringMethodContracts.SCHEDULED) ?: return null
    val trigger = describe(scheduled)
    val tooltip = PhantomSpringBundle.message("scheduled.gutter.tooltip", trigger)
    return LineMarkerInfo(element, element.textRange, AllIcons.Vcs.History, { tooltip }, null, GutterIconRenderer.Alignment.LEFT, { tooltip })
  }

  private fun describe(annotation: UAnnotation): String {
    val parts = ArrayList<String>()
    for ((kind, attributes) in SpringMethodContracts.TRIGGERS) {
      val value = SpringMethodContracts.attribute(annotation, attributes) ?: continue
      val text = value.evaluate()?.toString() ?: value.sourcePsi?.text ?: continue
      if (text.isEmpty() || text == "-1") continue
      parts += "$kind $text"
    }
    SpringMethodContracts.attribute(annotation, SpringMethodContracts.INITIAL_DELAY)?.evaluate()?.toString()
      ?.takeIf { it.isNotEmpty() && it != "-1" }?.let { parts += "initialDelay $it" }
    return parts.joinToString(", ").ifEmpty { "?" }
  }
}

/** On a class with `@Import`: the imported configurations, in the order listed. */
class SpringImportLineMarkerProvider : LineMarkerProviderDescriptor() {
  @NonNls private val import = "org.springframework.context.annotation.Import"

  override fun getName(): String = PhantomSpringBundle.message("import.line.marker.name")

  override fun getIcon(): Icon = AllIcons.Gutter.ImplementedMethod

  override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {

    if (GhostMode.enabled) return null
    if (element.firstChild != null) return null
    val owner = element.parent as? PsiNameIdentifierOwner ?: return null
    if (owner.nameIdentifier != element) return null
    if (!SpringProjectGate.isSpringProject(element.project)) return null
    val uClass = getUParentForIdentifier(element) as? UClass ?: return null
    val annotation = uClass.findAnnotation(import) ?: return null
    val classes = attributeItems(annotation, "value").mapNotNull { (it as? UClassLiteralExpression)?.type?.let { t -> (t as? PsiClassType)?.resolve() } }
    if (classes.isEmpty()) return null
    val names = classes.mapNotNull { it.name }.joinToString(", ")
    return NavigationGutterIconBuilder.create(AllIcons.Gutter.ImplementedMethod)
      .setTargets(NotNullLazyValue.lazy { classes.filter { it.isValid }.map { it.navigationElement } })
      .setTooltipText(PhantomSpringBundle.message("import.gutter.tooltip", names))
      .setPopupTitle(PhantomSpringBundle.message("import.gutter.tooltip", names))
      .createLineMarkerInfo(element)
  }
}
