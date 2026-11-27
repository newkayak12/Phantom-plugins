// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.springboot.core

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.psi.*
import com.intellij.psi.util.PsiTypesUtil
import com.intellij.xml.util.XmlStringUtil
import org.jetbrains.uast.*
import javax.swing.Icon

/** Shared plumbing of the gutters on Spring Boot annotations in Java and Kotlin. */
internal object BootAnnotations {
  /** The class or method whose name is [element], in a Spring Boot module; null otherwise. */
  fun declarationOf(element: PsiElement): Pair<UDeclaration, Module>? {
    if (element.firstChild != null) return null
    val owner = element.parent as? PsiNameIdentifierOwner ?: return null
    if (owner.nameIdentifier != element) return null
    // The gate is a cached lookup; the UAST conversion is not, so it runs only in Spring Boot modules.
    val module = ModuleUtilCore.findModuleForPsiElement(element) ?: return null
    if (!SpringBootGate.isBootModule(module) || DumbService.isDumb(element.project)) return null
    val declaration = getUParentForIdentifier(element) as? UDeclaration ?: return null
    if (declaration !is UClass && declaration !is UMethod) return null
    return declaration to module
  }

  /** Strings of an attribute: a literal, a constant, or an array of them (`{"a", "b"}`, `["a", "b"]`, Kotlin varargs). */
  fun strings(value: UExpression?): List<String> = when (value) {
    null -> emptyList()
    is UCallExpression -> value.valueArguments.flatMap { strings(it) }
    else -> listOfNotNull(value.evaluateString())
  }

  /** Classes named by class literals in an attribute (`X.class`, `X::class`, arrays of them). */
  fun classes(value: UExpression?): List<PsiClass> = when (value) {
    null -> emptyList()
    is UCallExpression -> value.valueArguments.flatMap { classes(it) }
    is UClassLiteralExpression -> listOfNotNull(PsiTypesUtil.getPsiClass(value.type))
    else -> emptyList()
  }

  /** The location to quote: one in a base file (`application.yml`) before a profile file's. */
  fun preferred(locations: List<ConfigKeyLocation>): ConfigKeyLocation? =
    locations.firstOrNull { ProfileFiles.nameOf(it.file.name)?.profile == null } ?: locations.firstOrNull()

  fun elementsAt(project: Project, locations: List<ConfigKeyLocation>): List<PsiElement> {
    val manager = PsiManager.getInstance(project)
    return locations.mapNotNull { manager.findFile(it.file)?.findElementAt(it.offset) }
  }

  /** Locations of [key] and of the keys under it, relaxed binding. */
  fun locationsUnder(project: Project, key: String): List<ConfigKeyLocation> = ProjectConfigKeys.under(project, key)
}

/**
 * On a class or `@Bean` method with `@ConditionalOnProperty`: the keys the condition reads, with the value the
 * configuration sets or a note that it is not set (and what `matchIfMissing` makes of that).
 */
class BootConditionalPropertyLineMarkerProvider : LineMarkerProviderDescriptor() {
  override fun getName(): String = PhantomSpringBootBundle.message("gutter.conditional.name")

  override fun getIcon(): Icon = AllIcons.FileTypes.Config

  override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {

    if (GhostMode.enabled) return null
    val (declaration, _) = BootAnnotations.declarationOf(element) ?: return null
    val annotation = declaration.findAnnotation(CONDITIONAL_ON_PROPERTY) ?: return null
    val prefix = BootAnnotations.strings(annotation.findDeclaredAttributeValue("prefix")).firstOrNull().orEmpty().trim().trimEnd('.')
    val names = BootAnnotations.strings(annotation.findDeclaredAttributeValue("name")) + BootAnnotations.strings(annotation.findDeclaredAttributeValue("value"))
    if (names.isEmpty()) return null
    val matchIfMissing = annotation.findDeclaredAttributeValue("matchIfMissing")?.evaluate() == true
    val project = element.project
    val keys = names.map { if (prefix.isEmpty()) it else "$prefix.$it" }
    val found = keys.associateWith { ProjectConfigKeys.find(project, it) }
    val lines = keys.map { key ->
      val at = found[key].orEmpty()
      if (at.isEmpty()) PhantomSpringBootBundle.message("gutter.conditional.missing", key, if (matchIfMissing) "matches" else "does not match")
      else BootAnnotations.preferred(at)!!.let { PhantomSpringBootBundle.message("gutter.conditional.set", key, it.value, it.file.name) }
    }
    return NavigationGutterIconBuilder.create(AllIcons.FileTypes.Config)
      .setTargets(NotNullLazyValue.lazy { BootAnnotations.elementsAt(project, found.values.flatten()) })
      .setTooltipText(XmlStringUtil.escapeString(lines.joinToString("\n")).replace("\n", "<br>"))
      .setPopupTitle(PhantomSpringBootBundle.message("gutter.conditional.popup"))
      .setEmptyPopupText(PhantomSpringBootBundle.message("gutter.conditional.empty"))
      .createLineMarkerInfo(element)
  }

  companion object {
    const val CONDITIONAL_ON_PROPERTY: String = "org.springframework.boot.autoconfigure.condition.ConditionalOnProperty"
  }
}

/** On a class with `@EnableConfigurationProperties(X::class)`: the properties classes it registers. */
class BootEnableConfigPropertiesLineMarkerProvider : LineMarkerProviderDescriptor() {
  override fun getName(): String = PhantomSpringBootBundle.message("gutter.enable.name")

  override fun getIcon(): Icon = AllIcons.Gutter.ImplementedMethod

  override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {

    if (GhostMode.enabled) return null
    val (declaration, _) = BootAnnotations.declarationOf(element) ?: return null
    if (declaration !is UClass) return null
    val annotation = declaration.findAnnotation(ENABLE_CONFIGURATION_PROPERTIES) ?: return null
    val classes = BootAnnotations.classes(annotation.findDeclaredAttributeValue("value")).distinct()
    if (classes.isEmpty()) return null
    return NavigationGutterIconBuilder.create(AllIcons.Gutter.ImplementedMethod)
      .setTargets(NotNullLazyValue.lazy { classes.filter { it.isValid }.map { it.navigationElement } })
      .setTooltipText(PhantomSpringBootBundle.message("gutter.enable.tooltip", classes.joinToString { it.name.orEmpty() }))
      .setPopupTitle(PhantomSpringBootBundle.message("gutter.enable.popup"))
      .createLineMarkerInfo(element)
  }

  companion object {
    const val ENABLE_CONFIGURATION_PROPERTIES: String = "org.springframework.boot.context.properties.EnableConfigurationProperties"
  }
}

/**
 * On a test class with `@SpringBootTest(properties = ...)` or `@TestPropertySource(properties = ...)`: the keys it
 * overrides that the configuration files also set, with their configured values.
 */
class BootTestPropertiesLineMarkerProvider : LineMarkerProviderDescriptor() {
  override fun getName(): String = PhantomSpringBootBundle.message("gutter.test.properties.name")

  override fun getIcon(): Icon = AllIcons.Gutter.OverridingMethod

  override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {

    if (GhostMode.enabled) return null
    val (declaration, _) = BootAnnotations.declarationOf(element) ?: return null
    if (declaration !is UClass) return null
    val entries = ANNOTATIONS.mapNotNull { declaration.findAnnotation(it) }
      .flatMap { BootAnnotations.strings(it.findDeclaredAttributeValue("properties")) }
      .mapNotNull { entry -> entry.indexOfFirst { it == '=' || it == ':' }.takeIf { it > 0 }?.let { entry.substring(0, it).trim() to entry.substring(it + 1).trim() } }
    if (entries.isEmpty()) return null
    val project = element.project
    val overridden = entries.mapNotNull { (key, value) -> ProjectConfigKeys.find(project, key).takeIf { it.isNotEmpty() }?.let { Triple(key, value, it) } }
    if (overridden.isEmpty()) return null
    val lines = overridden.map { (key, value, at) -> PhantomSpringBootBundle.message("gutter.test.properties.line", key, value, BootAnnotations.preferred(at)!!.value) }
    return NavigationGutterIconBuilder.create(AllIcons.Gutter.OverridingMethod)
      .setTargets(NotNullLazyValue.lazy { BootAnnotations.elementsAt(project, overridden.flatMap { it.third }) })
      .setTooltipText(XmlStringUtil.escapeString(lines.joinToString("\n")).replace("\n", "<br>"))
      .setPopupTitle(PhantomSpringBootBundle.message("gutter.test.properties.popup"))
      .createLineMarkerInfo(element)
  }

  companion object {
    private val ANNOTATIONS = listOf("org.springframework.boot.test.context.SpringBootTest", "org.springframework.test.context.TestPropertySource")
  }
}

/**
 * On a property of a `@ConfigurationProperties` class (a field, a Kotlin constructor property or a record component):
 * where the configuration sets its key, or the keys under it for a nested object. Shown only when set somewhere.
 */
class BootConfigPropertyFieldLineMarkerProvider : LineMarkerProviderDescriptor() {
  override fun getName(): String = PhantomSpringBootBundle.message("gutter.property.field.name")

  override fun getIcon(): Icon = AllIcons.FileTypes.Config

  override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {

    if (GhostMode.enabled) return null
    if (element.firstChild != null) return null
    val owner = element.parent as? PsiNameIdentifierOwner ?: return null
    if (owner.nameIdentifier != element) return null
    val module = ModuleUtilCore.findModuleForPsiElement(element) ?: return null
    if (!SpringBootGate.isBootModule(module) || DumbService.isDumb(element.project)) return null
    val (name, psiClass) = when (val parent = getUParentForIdentifier(element)) {
      is UField -> if (parent.isStatic) return null else parent.name to (parent.javaPsi as? PsiField)?.containingClass
      is UParameter -> parent.name to ((parent.uastParent as? UMethod)?.takeIf { it.isConstructor })?.javaPsi?.containingClass
      else -> return null
    }
    psiClass ?: return null
    val prefix = SourceConfigProperties.prefixOf(psiClass)?.takeIf { it.isNotEmpty() } ?: return null
    val key = "$prefix.${ConfigNames.normalize(name)}"
    val project = element.project
    val locations = BootAnnotations.locationsUnder(project, key)
    if (locations.isEmpty()) return null
    val exact = BootAnnotations.preferred(ProjectConfigKeys.find(project, key))
    val tooltip = if (exact != null) PhantomSpringBootBundle.message("gutter.property.field.value", key, exact.value, exact.file.name)
                  else PhantomSpringBootBundle.message("gutter.property.field.nested", key, locations.size)
    return NavigationGutterIconBuilder.create(AllIcons.FileTypes.Config)
      .setTargets(NotNullLazyValue.lazy { BootAnnotations.elementsAt(project, locations) })
      .setTooltipText(XmlStringUtil.escapeString(tooltip))
      .setPopupTitle(PhantomSpringBootBundle.message("gutter.property.field.popup", key))
      .createLineMarkerInfo(element)
  }
}
