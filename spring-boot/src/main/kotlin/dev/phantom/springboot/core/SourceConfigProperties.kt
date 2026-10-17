// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.springboot.core

import com.intellij.codeInsight.AnnotationUtil
import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.psi.*
import com.intellij.psi.javadoc.PsiDocComment
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.GlobalSearchScopesCore
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PropertyUtilBase
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiUtil
import com.intellij.psi.util.PsiUtilCore
import org.jetbrains.uast.UField
import org.jetbrains.uast.UParameter
import org.jetbrains.uast.toUElementOfType

/**
 * Keys of the project's own `@ConfigurationProperties` classes, read from source. Projects often skip
 * spring-boot-configuration-processor (or have not built yet), and then their own keys would be missing from completion.
 * Computed on the first request after a Java or Kotlin change; nothing runs while the user only edits other files.
 */
object SourceConfigProperties {
  const val CONFIGURATION_PROPERTIES: String = "org.springframework.boot.context.properties.ConfigurationProperties"
  private const val DEPRECATED_PROPERTY = "org.springframework.boot.context.properties.DeprecatedConfigurationProperty"
  private const val MAX_DEPTH = 5

  fun metadata(module: Module): ParsedMetadata {
    val project = module.project
    return CachedValuesManager.getManager(project).getCachedValue(module) {
      val tracker = PsiModificationTracker.getInstance(project).forLanguages { it.id == "JAVA" || it.id == "kotlin" }
      CachedValueProvider.Result.create(compute(module), tracker, ProjectRootModificationTracker.getInstance(project))
    }
  }

  /** The `@ConfigurationProperties` prefix of a class or `@Bean` method, normalized; null when there is no annotation. */
  fun prefixOf(owner: PsiModifierListOwner): String? {
    val annotation = owner.getAnnotation(CONFIGURATION_PROPERTIES) ?: return null
    val prefix = AnnotationUtil.getStringAttributeValue(annotation, "prefix")?.takeIf { it.isNotBlank() }
                 ?: AnnotationUtil.getStringAttributeValue(annotation, "value").orEmpty()
    return ConfigNames.normalize(prefix)
  }

  private fun compute(module: Module): ParsedMetadata {
    val project = module.project
    val annotation = JavaPsiFacade.getInstance(project).findClass(CONFIGURATION_PROPERTIES, module.getModuleWithDependenciesAndLibrariesScope(false))
                     ?: return ParsedMetadata.EMPTY
    val scope = GlobalSearchScope.moduleWithDependenciesScope(module).intersectWith(GlobalSearchScopesCore.projectProductionScope(project))
    val collector = Collector()
    AnnotatedElementsSearch.searchElements(annotation, scope, PsiClass::class.java, PsiMethod::class.java).forEach { owner ->
      ProgressManager.checkCanceled()
      val prefix = prefixOf(owner) ?: return@forEach
      val type = when (owner) {
        is PsiClass -> owner
        is PsiMethod -> PsiUtil.resolveClassInClassTypeOnly(owner.returnType)
        else -> null
      } ?: return@forEach
      if (prefix.isNotEmpty()) collector.groups.putIfAbsent(prefix, description(type))
      collector.collect(type, prefix, 0, HashSet())
    }
    return ParsedMetadata(collector.properties.values.toList(), emptyMap(), collector.groups)
  }

  private class Collector {
    val properties = LinkedHashMap<String, ConfigProperty>()
    val groups = LinkedHashMap<String, String?>()

    fun collect(psiClass: PsiClass, prefix: String, depth: Int, visiting: MutableSet<PsiClass>) {
      if (!visiting.add(psiClass)) return
      for (p in BindableProperty.of(psiClass)) {
        val name = ConfigNames.join(prefix, ConfigNames.normalize(p.name))
        val nested = PsiUtil.resolveClassInClassTypeOnly(p.type)?.takeIf { depth < MAX_DEPTH && isNestedPojo(it) }
        if (nested != null) {
          groups.putIfAbsent(name, p.description ?: description(nested))
          collect(nested, name, depth + 1, visiting)
          continue
        }
        properties.putIfAbsent(name, ConfigProperty(name, p.type.canonicalText, p.description, p.defaultValue, psiClass.qualifiedName, p.deprecation))
      }
      visiting.remove(psiClass)
    }

  }

  /** A project class bound field by field (`retry.max-attempts`), as opposed to a value type, enum or collection. */
  private fun isNestedPojo(psiClass: PsiClass): Boolean {
    if (psiClass.isEnum || psiClass.isInterface || psiClass.isAnnotationType || psiClass is PsiTypeParameter) return false
    val file = PsiUtilCore.getVirtualFile(psiClass) ?: return false
    return ProjectFileIndex.getInstance(psiClass.project).isInSourceContent(file)
  }

  /** One bindable property: a constructor parameter (Kotlin data classes, records) or a JavaBean setter. */
  class BindableProperty(val name: String, val type: PsiType, val element: PsiElement, val description: String?,
                         val defaultValue: String?, val deprecation: Deprecation?) {
    companion object {
      fun of(psiClass: PsiClass): List<BindableProperty> {
        val result = LinkedHashMap<String, BindableProperty>()
        // Constructor binding: the constructor with the most parameters is Kotlin's primary one (the others are generated
        // for default arguments) and the only one in a record or an immutable Java class.
        val constructor = psiClass.constructors.filter { !it.hasModifierProperty(PsiModifier.PRIVATE) }.maxByOrNull { it.parameterList.parametersCount }
        constructor?.parameterList?.parameters?.forEach { parameter ->
          val name = parameter.name
          val field = psiClass.findFieldByName(name, false)
          result.putIfAbsent(name, BindableProperty(name, parameter.type, parameter.navigationElement, description(field),
                                                    defaultOf(parameter), deprecationOf(field, psiClass, name)))
        }
        for (method in psiClass.allMethods) {
          if (method.hasModifierProperty(PsiModifier.STATIC) || method.containingClass?.qualifiedName == CommonClassNames.JAVA_LANG_OBJECT) continue
          if (!PropertyUtilBase.isSimplePropertySetter(method)) continue
          val name = PropertyUtilBase.getPropertyNameBySetter(method)
          val type = method.parameterList.parameters.single().type
          val field = PropertyUtilBase.findPropertyField(psiClass, name, false)
          result.putIfAbsent(name, BindableProperty(name, type, field?.navigationElement ?: method.navigationElement,
                                                    description(field) ?: description(method), field?.let(::defaultOf),
                                                    deprecationOf(field, psiClass, name)))
        }
        // `private final Pool pool = new Pool()` with only a getter: Spring binds into the existing nested object
        for (method in psiClass.allMethods) {
          if (method.hasModifierProperty(PsiModifier.STATIC) || !PropertyUtilBase.isSimplePropertyGetter(method)) continue
          val name = PropertyUtilBase.getPropertyNameByGetter(method)
          val type = method.returnType ?: continue
          if (name in result || PsiUtil.resolveClassInClassTypeOnly(type)?.let(::isNestedPojo) != true) continue
          val field = PropertyUtilBase.findPropertyField(psiClass, name, false)
          result[name] = BindableProperty(name, type, field?.navigationElement ?: method.navigationElement,
                                          description(field) ?: description(method), null, deprecationOf(field, psiClass, name))
        }
        return result.values.toList()
      }

      private fun defaultOf(element: PsiElement): String? {
        val initializer = when (element) {
          is PsiParameter -> element.navigationElement.toUElementOfType<UParameter>()?.uastInitializer
          is PsiField -> element.navigationElement.toUElementOfType<UField>()?.uastInitializer
          else -> null
        } ?: return null
        return when (val value = initializer.evaluate()) {
          null -> null
          is String -> value
          else -> value.toString()
        }
      }

      private fun deprecationOf(field: PsiField?, psiClass: PsiClass, name: String): Deprecation? {
        val getter = PropertyUtilBase.findPropertyGetter(psiClass, name, false, true)
        val annotation = getter?.getAnnotation(DEPRECATED_PROPERTY)
        if (annotation != null) {
          return Deprecation("warning", AnnotationUtil.getStringAttributeValue(annotation, "reason"),
                             AnnotationUtil.getStringAttributeValue(annotation, "replacement"))
        }
        return if (field?.isDeprecated == true || getter?.isDeprecated == true) Deprecation("warning", null, null) else null
      }
    }
  }

  /** The first sentence of a Javadoc comment, as HTML; KDoc is not read. */
  private fun description(owner: PsiElement?): String? {
    val doc: PsiDocComment = (owner as? PsiDocCommentOwner)?.docComment ?: return null
    val text = doc.descriptionElements.joinToString("") { it.text }.trim().replace(Regex("\\s+"), " ")
    if (text.isEmpty()) return null
    val end = text.indexOf(". ")
    return if (end > 0) text.substring(0, end + 1) else text
  }
}
