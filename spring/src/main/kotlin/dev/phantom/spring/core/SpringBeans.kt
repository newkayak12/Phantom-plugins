// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring.core

import com.intellij.codeInsight.AnnotationUtil
import com.intellij.codeInsight.MetaAnnotationUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Key
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.GlobalSearchScopesCore
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.InheritanceUtil
import com.intellij.psi.util.PsiUtil
import com.intellij.psi.util.PsiUtilCore
import org.jetbrains.annotations.TestOnly
import org.jetbrains.uast.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * A place where Spring injects a bean: a constructor or `@Bean`/`@Autowired` method parameter, or an injected field.
 * [element] is the Java view (also for Kotlin, through light elements); [navigationTarget] is the source element.
 */
class InjectionPoint(
  val element: PsiModifierListOwner,
  val type: PsiType,
  val qualifier: String?,
  val name: String?,
) {
  val navigationTarget: PsiElement get() = element.navigationElement
}

/**
 * Bean model computed directly from PSI and indexes. Nothing here is precomputed or cached project-wide:
 * the gutter only does cheap annotation checks while highlighting, and the searches run when the user clicks.
 */
object SpringBeans {
  private val STEREOTYPES = listOf(SpringNames.COMPONENT)
  private const val MAX_TYPE_DEPTH = 4
  private const val MAX_PARENT_WALK = 12

  fun isBeanClass(psiClass: PsiClass): Boolean {
    if (psiClass.isAnnotationType || psiClass.isEnum || psiClass is PsiTypeParameter) return false
    if (psiClass.isInterface) return isRepositoryInterface(psiClass)
    if (psiClass.hasModifierProperty(PsiModifier.ABSTRACT)) return false
    return MetaAnnotationUtil.isMetaAnnotated(psiClass, STEREOTYPES) ||
           psiClass.hasAnnotation(SpringNames.CONFIGURATION_PROPERTIES)
  }

  /** User-defined stereotypes such as `@UseCase` that carry `@Component` through meta-annotations. */
  fun isStereotypeAnnotation(psiClass: PsiClass): Boolean =
    psiClass.isAnnotationType && MetaAnnotationUtil.isMetaAnnotated(psiClass, STEREOTYPES)

  private fun isRepositoryInterface(psiClass: PsiClass): Boolean =
    !psiClass.hasAnnotation(SpringNames.NO_REPOSITORY_BEAN) && InheritanceUtil.isInheritor(psiClass, SpringNames.DATA_REPOSITORY)

  fun isBeanMethod(method: PsiMethod): Boolean = method.hasAnnotation(SpringNames.BEAN)

  fun isInjectMarked(owner: PsiModifierListOwner): Boolean = SpringNames.INJECT_MARKERS.any { owner.hasAnnotation(it) }

  // ---- names and qualifiers

  fun beanName(bean: PsiModifierListOwner): String? = when (bean) {
    is PsiClass -> classBeanName(bean)
    is PsiMethod -> methodBeanName(bean)
    else -> null
  }

  private fun classBeanName(psiClass: PsiClass): String? {
    for (annotation in psiClass.annotations) {
      val fqn = annotation.qualifiedName ?: continue
      val isStereotype = fqn == SpringNames.COMPONENT ||
                         annotation.resolveAnnotationType()?.let { isStereotypeAnnotation(it) } == true
      if (!isStereotype) continue
      val explicit = AnnotationUtil.getStringAttributeValue(annotation, "value")
      if (!explicit.isNullOrBlank()) return explicit
    }
    return psiClass.name?.let(::decapitalize)
  }

  private fun methodBeanName(method: PsiMethod): String {
    val annotation = method.getAnnotation(SpringNames.BEAN) ?: return method.name
    for (attribute in listOf("name", "value")) {
      val first = AnnotationUtil.arrayAttributeValues(annotation.findDeclaredAttributeValue(attribute)).firstOrNull()
      val name = first?.let { AnnotationUtil.getStringAttributeValue(it) }
      if (!name.isNullOrBlank()) return name
    }
    return method.name
  }

  fun qualifier(owner: PsiModifierListOwner): String? {
    for (fqn in SpringNames.NAMED_QUALIFIERS) {
      val annotation = owner.getAnnotation(fqn) ?: continue
      AnnotationUtil.getStringAttributeValue(annotation, "value")?.takeIf { it.isNotBlank() }?.let { return it }
    }
    return null
  }

  /** java.beans.Introspector.decapitalize, which Spring's default bean name generator uses. */
  fun decapitalize(name: String): String {
    if (name.isEmpty()) return name
    if (name.length > 1 && name[0].isUpperCase() && name[1].isUpperCase()) return name
    return name[0].lowercaseChar() + name.substring(1)
  }

  // ---- injection points

  fun injectionPointOf(element: UElement): InjectionPoint? = when (element) {
    is UParameter -> parameterInjectionPoint(element)
    is UField -> {
      // Kotlin constructor properties (`class A(private val b: B)`) may surface as fields; prefer the parameter view.
      val asParameter = element.sourcePsi?.toUElementOfType<UParameter>()
      if (asParameter != null) parameterInjectionPoint(asParameter) else fieldInjectionPoint(element)
    }
    else -> null
  }

  private fun parameterInjectionPoint(parameter: UParameter): InjectionPoint? {
    val psiParameter = parameter.javaPsi as? PsiParameter ?: return null
    val method = (parameter.uastParent as? UMethod)?.javaPsi ?: return null
    if (psiParameter.hasAnnotation(SpringNames.VALUE)) return null
    val containingClass = method.containingClass ?: return null
    val injected = when {
      isBeanMethod(method) -> true
      // @ConfigurationProperties constructors bind configuration values, not beans
      method.isConstructor -> isBeanClass(containingClass) && !containingClass.hasAnnotation(SpringNames.CONFIGURATION_PROPERTIES) &&
                              (containingClass.constructors.size == 1 || isInjectMarked(method))
      else -> isInjectMarked(method) && isBeanClass(containingClass)
    }
    if (!injected) return null
    return InjectionPoint(psiParameter, psiParameter.type, qualifier(psiParameter), psiParameter.name)
  }

  private fun fieldInjectionPoint(field: UField): InjectionPoint? {
    val psiField = field.javaPsi as? PsiField ?: return null
    if (!isInjectMarked(psiField)) return null
    val containingClass = psiField.containingClass ?: return null
    if (!isBeanClass(containingClass)) return null
    return InjectionPoint(psiField, psiField.type, qualifier(psiField), psiField.name)
  }

  /** The class whose beans can be injected into a point of this type, unwrapping Optional, List, ObjectProvider, Map values. */
  fun beanTypeOf(type: PsiType): PsiClass? = beanTypeOf(type, 0)

  private fun beanTypeOf(type: PsiType?, depth: Int): PsiClass? {
    if (type == null || depth > MAX_TYPE_DEPTH) return null
    return when (type) {
      is PsiArrayType -> beanTypeOf(type.componentType, depth + 1)
      is PsiWildcardType -> beanTypeOf(type.bound, depth + 1)
      is PsiCapturedWildcardType -> beanTypeOf(type.wildcard.bound, depth + 1)
      is PsiClassType -> {
        val psiClass = type.resolve() ?: return null
        val fqn = psiClass.qualifiedName
        when {
          fqn in SpringNames.SINGLE_ARG_WRAPPERS -> beanTypeOf(type.parameters.firstOrNull(), depth + 1)
          fqn == SpringNames.MAP -> beanTypeOf(type.parameters.getOrNull(1), depth + 1)
          psiClass is PsiTypeParameter -> null
          fqn == null || fqn.startsWith("java.") || fqn.startsWith("kotlin.") -> null
          else -> psiClass
        }
      }
      else -> null
    }
  }

  fun isProjectSourceClass(psiClass: PsiClass): Boolean {
    val file = PsiUtilCore.getVirtualFile(psiClass) ?: return false
    return ProjectFileIndex.getInstance(psiClass.project).isInSourceContent(file)
  }

  fun isInTestSources(context: PsiElement): Boolean {
    val file = PsiUtilCore.getVirtualFile(context) ?: return false
    return ProjectFileIndex.getInstance(context.project).isInTestSourceContent(file)
  }

  private fun scopeFor(context: PsiElement): GlobalSearchScope =
    if (isInTestSources(context)) GlobalSearchScope.projectScope(context.project) else GlobalSearchScopesCore.projectProductionScope(context.project)

  private val PRODUCTION_BEAN_METHODS = Key.create<CachedValue<List<PsiMethod>>>("phantom.spring.beanMethods.production")
  private val ALL_BEAN_METHODS = Key.create<CachedValue<List<PsiMethod>>>("phantom.spring.beanMethods.all")
  private val INHERITORS = Key.create<CachedValue<ConcurrentHashMap<Pair<PsiClass, Boolean>, List<PsiClass>>>>("phantom.spring.inheritors")

  @TestOnly
  val beanMethodSearches: AtomicInteger = AtomicInteger()
  @TestOnly
  val inheritorSearches: AtomicInteger = AtomicInteger()

  /**
   * All `@Bean` methods visible from [context]. The autowiring inspection asks for them once per injection point, so the
   * annotation search runs once per change of declarations instead (typing in a method body is not one).
   */
  private fun beanMethods(context: PsiElement): List<PsiMethod> {
    val project = context.project
    val inTests = isInTestSources(context)
    val manager = CachedValuesManager.getManager(project)
    return manager.getCachedValue(project, if (inTests) ALL_BEAN_METHODS else PRODUCTION_BEAN_METHODS, beanMethodsProvider(project, inTests), false)
  }

  private fun beanMethodsProvider(project: Project, inTests: Boolean) = CachedValueProvider {
    CachedValueProvider.Result.create(searchBeanMethods(project, inTests), *SpringStructureTracker.deps(project))
  }

  private fun searchBeanMethods(project: Project, inTests: Boolean): List<PsiMethod> {
    beanMethodSearches.incrementAndGet()
    val scope = if (inTests) GlobalSearchScope.projectScope(project) else GlobalSearchScopesCore.projectProductionScope(project)
    val beanAnnotation = JavaPsiFacade.getInstance(project).findClass(SpringNames.BEAN, GlobalSearchScope.allScope(project))
    return if (beanAnnotation == null) emptyList() else AnnotatedElementsSearch.searchPsiMethods(beanAnnotation, scope).findAll().toList()
  }

  /**
   * Project classes that inherit [target], in production sources only or in all project sources. The autowiring
   * inspection asks for the same bean types at every injection point of every file it highlights, so each search is
   * kept until declarations change.
   */
  fun inheritors(target: PsiClass, production: Boolean): List<PsiClass> {
    val project = target.project
    val map = CachedValuesManager.getManager(project).getCachedValue(project, INHERITORS, {
      CachedValueProvider.Result.create(ConcurrentHashMap<Pair<PsiClass, Boolean>, List<PsiClass>>(), *SpringStructureTracker.deps(project))
    }, false)
    val key = target to production
    map[key]?.let { return it }
    // searched outside the map's lock: the search may be cancelled, and other keys must not wait for it
    val found = searchInheritors(target, production)
    return map.putIfAbsent(key, found) ?: found
  }

  private fun searchInheritors(target: PsiClass, production: Boolean): List<PsiClass> {
    inheritorSearches.incrementAndGet()
    val scope = if (production) GlobalSearchScopesCore.projectProductionScope(target.project) else GlobalSearchScope.projectScope(target.project)
    return ClassInheritorsSearch.search(target, scope, true).findAll().toList()
  }

  // ---- searches (run on click, in a read action with a progress indicator)

  /** Beans declared in project sources that Spring would consider for [point], best matches first. */
  fun findCandidates(point: InjectionPoint): List<PsiModifierListOwner> = findCandidates(point, fresh = false)

  /** [fresh]: search again instead of reading the caches, to compare the two in tests. */
  internal fun findCandidates(point: InjectionPoint, fresh: Boolean): List<PsiModifierListOwner> {
    val target = beanTypeOf(point.type) ?: return emptyList()
    val project = target.project
    val inTests = isInTestSources(point.element)
    val scope = scopeFor(point.element)
    val candidates = LinkedHashSet<PsiModifierListOwner>()

    if (scope.contains(PsiUtilCore.getVirtualFile(target) ?: return emptyList()) && isBeanClass(target)) {
      candidates.add(target)
    }
    val inheritors = if (fresh) searchInheritors(target, !inTests) else inheritors(target, !inTests)
    inheritors.forEach { inheritor ->
      if (isBeanClass(inheritor)) candidates.add(inheritor)
    }
    for (method in if (fresh) searchBeanMethods(project, inTests) else beanMethods(point.element)) {
      val returned = PsiUtil.resolveClassInClassTypeOnly(method.returnType)
      if (returned != null && InheritanceUtil.isInheritorOrSelf(returned, target, true)) candidates.add(method)
    }

    val qualifier = point.qualifier
    val filtered = if (qualifier != null) {
      candidates.filter { beanName(it) == qualifier || qualifier(it) == qualifier }
    }
    else candidates.toList()

    // Spring's tie-breaking for a single injection point: @Primary first, then a bean whose name equals the parameter name.
    return filtered.sortedWith(compareBy<PsiModifierListOwner>(
      { !it.hasAnnotation(SpringNames.PRIMARY) },
      { point.name == null || beanName(it) != point.name },
    ))
  }

  /** Injection points in project sources that would receive [bean] (a bean class or a `@Bean` method). */
  fun findInjectionPoints(bean: PsiModifierListOwner): List<InjectionPoint> {
    val beanClass = when (bean) {
      is PsiClass -> bean
      is PsiMethod -> PsiUtil.resolveClassInClassTypeOnly(bean.returnType)
      else -> null
    } ?: return emptyList()
    val project = bean.project
    val manager = PsiManager.getInstance(project)
    val name = beanName(bean)
    val ownQualifier = qualifier(bean)
    val types = LinkedHashSet<PsiClass>()
    types.add(beanClass)
    InheritanceUtil.getSuperClasses(beanClass, types, false)

    val result = LinkedHashMap<PsiElement, InjectionPoint>()
    val scope = GlobalSearchScope.projectScope(project)
    for (type in types) {
      ReferencesSearch.search(type, scope).forEach { reference ->
        val point = injectionPointAround(reference.element)
        if (point != null && manager.areElementsEquivalent(beanTypeOf(point.type), type)) {
          val q = point.qualifier
          if (q == null || q == name || q == ownQualifier) result.putIfAbsent(point.element, point)
        }
        true
      }
    }
    return result.values.toList()
  }

  private fun injectionPointAround(element: PsiElement): InjectionPoint? {
    var current: PsiElement? = element
    repeat(MAX_PARENT_WALK) {
      val e = current ?: return null
      if (e is PsiFile) return null
      e.toUElementOfType<UParameter>()?.let { return injectionPointOf(it) }
      e.toUElementOfType<UField>()?.let { return injectionPointOf(it) }
      when (e.toUElement()) {
        is UMethod, is UClass, is UFile, is UBlockExpression -> return null
        else -> {}
      }
      current = e.parent
    }
    return null
  }
}
