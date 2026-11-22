// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.springboot.core

import com.intellij.codeInsight.AnnotationUtil
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.getUParentForIdentifier
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.Icon

/**
 * The project's `@Value` placeholders and `@ConfigurationProperties` prefixes, recomputed after a Java, Kotlin or Groovy
 * change: typing in `application.yml` asks for them on every key and must not rerun the search.
 */
object BootConfigUsages {
  class Usages(val values: Map<String, List<PsiElement>>, val prefixes: Set<String>)

  @TestOnly
  val computeCount: AtomicInteger = AtomicInteger()

  fun of(project: Project): Usages = CachedValuesManager.getManager(project).getCachedValue(project) {
    CachedValueProvider.Result.create(compute(project), *BootCacheDeps.jvm(project))
  }

  private fun compute(project: Project): Usages {
    computeCount.incrementAndGet()
    val facade = JavaPsiFacade.getInstance(project)
    val all = GlobalSearchScope.allScope(project)
    val scope = GlobalSearchScope.projectScope(project)
    val values = HashMap<String, MutableList<PsiElement>>()
    facade.findClass(ValuePlaceholderReferenceContributor.VALUE_ANNOTATION, all)?.let { annotation ->
      AnnotatedElementsSearch.searchElements(annotation, scope, PsiField::class.java, PsiMethod::class.java, PsiParameter::class.java).forEach { owner ->
        val value = owner.getAnnotation(ValuePlaceholderReferenceContributor.VALUE_ANNOTATION) ?: return@forEach
        val text = AnnotationUtil.getStringAttributeValue(value, "value") ?: return@forEach
        for (range in ValuePlaceholders.keyRanges(text)) {
          val key = ConfigNames.normalize(range.substring(text))
          if (key.isNotEmpty()) values.getOrPut(key) { ArrayList() }.add(value.navigationElement)
        }
      }
    }
    val prefixes = HashSet<String>()
    facade.findClass(SourceConfigProperties.CONFIGURATION_PROPERTIES, all)?.let { annotation ->
      AnnotatedElementsSearch.searchElements(annotation, scope, PsiClass::class.java, PsiMethod::class.java).forEach { owner ->
        SourceConfigProperties.prefixOf(owner)?.takeIf { it.isNotEmpty() }?.let(prefixes::add)
      }
    }
    return Usages(values, prefixes)
  }

  fun bound(prefixes: Set<String>, key: String): Boolean = prefixes.any { key == it || key.startsWith("$it.") }
}

/**
 * On a key in `application.yml` / `.properties` (and imported files): the `@Value("${key}")` sites that read it and
 * the `@ConfigurationProperties` property it binds to. Shown only for keys the project's code uses.
 */
class BootConfigKeyLineMarkerProvider : LineMarkerProviderDescriptor() {
  override fun getName(): String = PhantomSpringBootBundle.message("gutter.config.key.name")

  override fun getIcon(): Icon = AllIcons.Gutter.ImplementedMethod

  override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
    if (element.firstChild != null || element.textLength == 0) return null
    val file = element.containingFile ?: return null
    if (!BootConfigFiles.isConfigFile(file)) return null
    val key = keyStarts(file)[element.textRange.startOffset] ?: return null
    if (DumbService.isDumb(file.project)) return null
    val module = ModuleUtilCore.findModuleForPsiElement(file) ?: return null
    if (!SpringBootGate.isBootModule(module)) return null
    val usages = BootConfigUsages.of(file.project)
    val values = usages.values[key].orEmpty()
    val bound = BootConfigUsages.bound(usages.prefixes, key)
    if (values.isEmpty() && !bound) return null
    return NavigationGutterIconBuilder.create(AllIcons.Gutter.ImplementedMethod)
      .setTargets(NotNullLazyValue.lazy { values.filter { it.isValid } + if (bound) BootConfigNavigation.targets(module, key) else emptyList() })
      .setTooltipText(PhantomSpringBootBundle.message("gutter.config.key.tooltip", key))
      .setPopupTitle(PhantomSpringBootBundle.message("gutter.config.key.popup", key))
      .createLineMarkerInfo(element)
  }

  private fun keyStarts(file: PsiFile): Map<Int, String> = ConfigKeyOffsets.of(file)
}

/** On a `@ConfigurationProperties` class or `@Bean` method: the configuration keys set under its prefix. */
class BootConfigPropertiesLineMarkerProvider : LineMarkerProviderDescriptor() {
  override fun getName(): String = PhantomSpringBootBundle.message("gutter.config.properties.name")

  override fun getIcon(): Icon = AllIcons.FileTypes.Config

  override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
    if (element.firstChild != null) return null
    val owner = element.parent as? PsiNameIdentifierOwner ?: return null
    if (owner.nameIdentifier != element) return null
    // The gate is a cached lookup; the UAST conversion below is not, so it runs only in Spring Boot modules.
    val module = ModuleUtilCore.findModuleForPsiElement(element) ?: return null
    if (!SpringBootGate.isBootModule(module)) return null
    val declaration = when (val parent = getUParentForIdentifier(element)) {
      is UClass -> parent.javaPsi
      is UMethod -> parent.javaPsi
      else -> null
    } ?: return null
    val prefix = SourceConfigProperties.prefixOf(declaration)?.takeIf { it.isNotEmpty() } ?: return null
    val project = element.project
    val locations = ProjectConfigKeys.keys(project).filterKeys { ConfigNames.normalize(it).let { k -> k == prefix || k.startsWith("$prefix.") } }
    if (locations.isEmpty()) return null
    return NavigationGutterIconBuilder.create(AllIcons.FileTypes.Config)
      .setTargets(NotNullLazyValue.lazy {
        val manager = PsiManager.getInstance(project)
        locations.values.flatten().mapNotNull { location -> manager.findFile(location.file)?.findElementAt(location.offset) }
      })
      .setTooltipText(PhantomSpringBootBundle.message("gutter.config.properties.tooltip", prefix))
      .setPopupTitle(PhantomSpringBootBundle.message("gutter.config.properties.popup", prefix))
      .createLineMarkerInfo(element)
  }
}
