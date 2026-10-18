// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.springboot.core

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.GlobalSearchScopesCore
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.util.PsiUtil

/** Ctrl+B on a configuration key opens the property it binds to: a constructor parameter, field or setter. */
class BootConfigGotoDeclarationHandler : GotoDeclarationHandler {
  override fun getGotoDeclarationTargets(sourceElement: PsiElement?, offset: Int, editor: Editor?): Array<PsiElement>? {
    val file = sourceElement?.containingFile ?: return null
    if (editor == null || !BootConfigFiles.isConfigFile(file)) return null
    val module = ModuleUtilCore.findModuleForPsiElement(file) ?: return null
    if (!SpringBootGate.isBootModule(module)) return null
    val syntax = if (file.name.endsWith(".properties")) PropertiesConfigSyntax else YamlConfigSyntax
    val key = syntax.keyAt(editor.document.charsSequence, offset) ?: return null
    return BootConfigNavigation.targets(module, key).takeIf { it.isNotEmpty() }?.toTypedArray()
  }
}

object BootConfigNavigation {
  fun targets(module: Module, key: String): List<PsiElement> {
    val name = ConfigNames.normalize(key)
    fromSource(module, name)?.let { return listOf(it) }
    val property = ConfigMetadataService.getInstance(module.project).keysFor(module).property(name) ?: return emptyList()
    val sourceType = property.sourceType ?: return emptyList()
    val psiClass = JavaPsiFacade.getInstance(module.project)
                     .findClass(sourceType.replace('$', '.'), module.getModuleWithDependenciesAndLibrariesScope(false)) ?: return emptyList()
    val segment = property.name.substringAfterLast('.')
    val member = SourceConfigProperties.BindableProperty.of(psiClass).firstOrNull { ConfigNames.normalize(it.name) == segment }?.element
    return listOf((member ?: psiClass).navigationElement)
  }

  /** Walks from the `@ConfigurationProperties` class with the longest matching prefix down through nested properties. */
  private fun fromSource(module: Module, name: String): PsiElement? {
    val project = module.project
    val annotation = JavaPsiFacade.getInstance(project)
                       .findClass(SourceConfigProperties.CONFIGURATION_PROPERTIES, module.getModuleWithDependenciesAndLibrariesScope(false)) ?: return null
    val scope = GlobalSearchScope.moduleWithDependenciesScope(module).intersectWith(GlobalSearchScopesCore.projectProductionScope(project))
    var best: Pair<String, PsiClass>? = null
    var bestOwner: PsiElement? = null
    AnnotatedElementsSearch.searchElements(annotation, scope, PsiClass::class.java, PsiMethod::class.java).forEach { owner ->
      val prefix = SourceConfigProperties.prefixOf(owner) ?: return@forEach
      // an empty prefix would claim every key, library ones included
      if (prefix.isEmpty() || name != prefix && !name.startsWith("$prefix.")) return@forEach
      if (best != null && best!!.first.length >= prefix.length) return@forEach
      val type = when (owner) {
        is PsiClass -> owner
        is PsiMethod -> PsiUtil.resolveClassInClassTypeOnly(owner.returnType)
        else -> null
      } ?: return@forEach
      best = prefix to type
      bestOwner = owner
    }
    val (prefix, root) = best ?: return null
    if (name == prefix) return bestOwner?.navigationElement
    var current: PsiClass = root
    var target: PsiElement = root
    for (segment in name.removePrefix(prefix).removePrefix(".").split('.')) {
      val property = SourceConfigProperties.BindableProperty.of(current).firstOrNull { ConfigNames.normalize(it.name) == segment }
                     ?: return target.navigationElement
      target = property.element
      current = PsiUtil.resolveClassInClassTypeOnly(property.type) ?: return target.navigationElement
    }
    return target.navigationElement
  }
}
