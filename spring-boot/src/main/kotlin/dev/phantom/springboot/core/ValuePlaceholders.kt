// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.springboot.core

import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.uast.injectionHostUExpression
import com.intellij.psi.*
import com.intellij.psi.registerUastReferenceProvider
import com.intellij.psi.uastInjectionHostReferenceProvider

/**
 * `${key}` placeholders in `@Value("...")` (Java and Kotlin): completion of the keys the project's configuration files
 * set and of Spring Boot's documented keys, and Ctrl+B to where the key is set, or to the `@ConfigurationProperties`
 * property it binds to.
 */
class ValuePlaceholderReferenceContributor : PsiReferenceContributor() {
  override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
    registrar.registerUastReferenceProvider(
      injectionHostUExpression().annotationParam(VALUE_ANNOTATION, "value"),
      uastInjectionHostReferenceProvider { _, host -> ValuePlaceholders.references(host) },
      PsiReferenceRegistrar.DEFAULT_PRIORITY,
    )
  }

  companion object {
    const val VALUE_ANNOTATION: String = "org.springframework.beans.factory.annotation.Value"
  }
}

object ValuePlaceholders {
  /** Key ranges in [text]: `${a.b:default}` → the range of `a.b`. A key still being typed runs to the end. */
  fun keyRanges(text: String): List<TextRange> {
    val out = ArrayList<TextRange>()
    var i = text.indexOf("\${")
    while (i >= 0) {
      val start = i + 2
      var end = start
      while (end < text.length && text[end] !in "}:\"'\n$") end++
      out.add(TextRange(start, end))
      i = text.indexOf("\${", end)
    }
    return out
  }

  fun references(host: PsiElement): Array<PsiReference> {
    val module = ModuleUtilCore.findModuleForPsiElement(host) ?: return PsiReference.EMPTY_ARRAY
    if (!SpringBootGate.isBootModule(module)) return PsiReference.EMPTY_ARRAY
    return keyRanges(host.text).map { PlaceholderReference(host, it) }.toTypedArray()
  }
}

class PlaceholderReference(host: PsiElement, range: TextRange) : PsiPolyVariantReferenceBase<PsiElement>(host, range, true) {
  val key: String get() = value.trim()

  override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
    val project = element.project
    val psiManager = PsiManager.getInstance(project)
    val inFiles = ProjectConfigKeys.find(project, key).mapNotNull { location ->
      psiManager.findFile(location.file)?.let { it.findElementAt(location.offset) ?: it }
    }
    if (inFiles.isNotEmpty()) return PsiElementResolveResult.createResults(inFiles)
    val module = ModuleUtilCore.findModuleForPsiElement(element) ?: return ResolveResult.EMPTY_ARRAY
    return PsiElementResolveResult.createResults(BootConfigNavigation.targets(module, key))
  }

  override fun getVariants(): Array<Any> {
    val project = element.project
    val out = ArrayList<Any>()
    val own = ProjectConfigKeys.keys(project)
    for ((name, locations) in own) {
      out.add(LookupElementBuilder.create(name).bold().withIcon(AllIcons.Nodes.Property)
                .withTailText(locations.first().value.takeIf { it.isNotEmpty() }?.let { " = " + it.take(40) } ?: "", true)
                .withTypeText(locations.first().file.name, true))
    }
    val module = ModuleUtilCore.findModuleForPsiElement(element) ?: return out.toTypedArray()
    val metadata = ConfigMetadataService.getInstance(project).keysFor(module)
    for (property in metadata.leavesUnder("", Int.MAX_VALUE)) {
      if (property.name in own) continue
      out.add(LookupElementBuilder.create(property.name).withIcon(AllIcons.Nodes.Property)
                .withTypeText(property.type?.substringAfterLast('.'), true)
                .withStrikeoutness(property.deprecation != null))
    }
    return out.toTypedArray()
  }
}
