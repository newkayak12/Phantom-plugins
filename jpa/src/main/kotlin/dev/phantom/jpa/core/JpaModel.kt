// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.jpa.core

import com.intellij.codeInsight.AnnotationUtil
import com.intellij.java.library.JavaLibraryUtil
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Key
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.InheritanceUtil
import com.intellij.psi.util.PsiUtil
import com.intellij.psi.util.PsiUtilCore
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

object JpaGate {
  private val ENTITY_CLASSES = listOf("jakarta.persistence.Entity", "javax.persistence.Entity")

  fun isJpaModule(module: Module): Boolean {
    if (module.isDisposed) return false
    if (ENTITY_CLASSES.any { JavaLibraryUtil.hasLibraryClass(module, it) }) return true
    // Light test fixtures declare the annotations as source stubs instead of a library.
    return ApplicationManager.getApplication().isUnitTestMode &&
           ENTITY_CLASSES.any { JavaPsiFacade.getInstance(module.project).findClass(it, module.getModuleWithDependenciesAndLibrariesScope(false)) != null }
  }
}

/**
 * Entity lookups straight from PSI and indexes, scoped to the module that contains the query.
 * Entity names are cached per scope until JVM code changes; reference resolution is cached by the platform per query.
 */
object JpaModel {
  private val ENTITY = listOf("jakarta.persistence.Entity", "javax.persistence.Entity")
  private val PERSISTENT = ENTITY + listOf(
    "jakarta.persistence.Embeddable", "javax.persistence.Embeddable",
    "jakarta.persistence.MappedSuperclass", "javax.persistence.MappedSuperclass",
  )
  private val TRANSIENT = listOf("jakarta.persistence.Transient", "javax.persistence.Transient")
  private val COLLECTIONS = listOf("java.util.Collection", "java.lang.Iterable")

  /** The Java or Kotlin element that hosts the query, or the element itself outside injections. */
  fun hostOf(element: PsiElement): PsiElement {
    val manager = InjectedLanguageManager.getInstance(element.project)
    val file = element.containingFile ?: return element
    return manager.getInjectionHost(file) ?: manager.getInjectionHost(file.originalFile) ?: element
  }

  fun scopeFor(context: PsiElement): GlobalSearchScope {
    val host = hostOf(context)
    val project = host.project
    val module = ModuleUtilCore.findModuleForPsiElement(host) ?: return GlobalSearchScope.allScope(project)
    val file = PsiUtilCore.getVirtualFile(host)
    val inTests = file != null && ProjectFileIndex.getInstance(project).isInTestSourceContent(file)
    // Module scope matters: the user's workspace has several copies of the same entity FQNs.
    return module.getModuleWithDependenciesAndLibrariesScope(inTests)
  }

  fun isEntity(psiClass: PsiClass): Boolean = ENTITY.any { psiClass.hasAnnotation(it) }

  fun isPersistent(psiClass: PsiClass): Boolean = PERSISTENT.any { psiClass.hasAnnotation(it) }

  fun entityName(psiClass: PsiClass): String? {
    for (fqn in ENTITY) {
      val annotation = psiClass.getAnnotation(fqn) ?: continue
      AnnotationUtil.getStringAttributeValue(annotation, "name")?.takeIf { it.isNotBlank() }?.let { return it }
    }
    return psiClass.name
  }

  private val ENTITY_BY_NAME = Key.create<CachedValue<ConcurrentHashMap<Pair<GlobalSearchScope, String>, Optional<PsiClass>>>>("phantom.jpa.entityByName")

  /**
   * The entity a JPQL name stands for. Kept per module scope and name until JVM code or roots change: every path in every
   * query of a file starts from one of a few entity names, and the fallback scans all entities of the scope.
   */
  fun findEntity(name: String, context: PsiElement): PsiClass? {
    val scope = scopeFor(context)
    val project = context.project
    val byName = CachedValuesManager.getManager(project).getCachedValue(project, ENTITY_BY_NAME, {
      CachedValueProvider.Result.create(ConcurrentHashMap<Pair<GlobalSearchScope, String>, Optional<PsiClass>>(), *JpaCacheDeps.code(project))
    }, false)
    val key = scope to name
    byName[key]?.let { return it.orElse(null) }
    val found = findEntityUncached(name, context, scope)
    byName.putIfAbsent(key, Optional.ofNullable(found))
    return found
  }

  private fun findEntityUncached(name: String, context: PsiElement, scope: GlobalSearchScope): PsiClass? {
    val simple = name.substringAfterLast('.')
    PsiShortNamesCache.getInstance(context.project).getClassesByName(simple, scope)
      .firstOrNull { isEntity(it) && (entityName(it) == simple || it.qualifiedName == name) }
      ?.let { return it }
    return entities(context, scope).firstOrNull { entityName(it) == name }
  }

  fun entities(context: PsiElement, scope: GlobalSearchScope = scopeFor(context)): List<PsiClass> {
    val facade = JavaPsiFacade.getInstance(context.project)
    return ENTITY.flatMap { fqn ->
      val annotation = facade.findClass(fqn, GlobalSearchScope.allScope(context.project)) ?: return@flatMap emptyList()
      AnnotatedElementsSearch.searchPsiClasses(annotation, scope).findAll()
    }
  }

  /** Persistent attributes by JPQL name: fields (Kotlin properties' backing fields) first, then getters. */
  fun attribute(owner: PsiClass, name: String): PsiMember? {
    owner.findFieldByName(name, true)?.takeIf { isAttributeField(it) }?.let { return it }
    val capitalized = name.replaceFirstChar { it.uppercaseChar() }
    for (getter in listOf("get$capitalized", "is$capitalized")) {
      owner.findMethodsByName(getter, true).firstOrNull { it.parameterList.isEmpty && !it.hasModifierProperty(PsiModifier.STATIC) }?.let { return it }
    }
    return null
  }

  fun attributes(owner: PsiClass): List<PsiField> = owner.allFields.filter { isAttributeField(it) }.distinctBy { it.name }

  private fun isAttributeField(field: PsiField): Boolean =
    !field.hasModifierProperty(PsiModifier.STATIC) && TRANSIENT.none { field.hasAnnotation(it) } &&
    !field.hasModifierProperty(PsiModifier.TRANSIENT)

  /** The class a path continues into after [member]: the field type, or the element type of a collection or map. */
  fun valueClass(member: PsiMember): PsiClass? {
    val type = when (member) {
      is PsiField -> member.type
      is PsiMethod -> member.returnType
      else -> null
    } ?: return null
    return valueClass(type)
  }

  private fun valueClass(type: PsiType): PsiClass? {
    val classType = type as? PsiClassType ?: return null
    val psiClass = classType.resolve() ?: return null
    if (COLLECTIONS.any { InheritanceUtil.isInheritor(psiClass, it) } || psiClass.qualifiedName in COLLECTIONS) {
      return PsiUtil.substituteTypeParameter(type, "java.lang.Iterable", 0, false)?.let { PsiUtil.resolveClassInClassTypeOnly(it) }
             ?: classType.parameters.firstOrNull()?.let { bound(it) }
    }
    if (InheritanceUtil.isInheritor(psiClass, "java.util.Map") || psiClass.qualifiedName == "java.util.Map") {
      return classType.parameters.getOrNull(1)?.let { bound(it) }
    }
    return psiClass
  }

  private fun bound(type: PsiType): PsiClass? =
    PsiUtil.resolveClassInClassTypeOnly(if (type is PsiWildcardType) type.bound ?: return null else type)
}
