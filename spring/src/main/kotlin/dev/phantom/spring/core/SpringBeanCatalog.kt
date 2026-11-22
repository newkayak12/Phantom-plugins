// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring.core

import com.intellij.codeInsight.AnnotationUtil
import com.intellij.codeInsight.MetaAnnotationUtil
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiUtil
import com.intellij.psi.util.PsiUtilCore
import java.util.concurrent.atomic.AtomicInteger
import org.jetbrains.annotations.TestOnly
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.UFile
import org.jetbrains.uast.ULiteralExpression
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UPolyadicExpression
import org.jetbrains.uast.UastCallKind
import org.jetbrains.uast.toUElement
import org.jetbrains.uast.toUElementOfType

/** What a bean is for, by its stereotype; the order is the order of groups in the Spring Beans window. */
enum class BeanKind(val title: String) {
  CONTROLLER("Controllers"),
  SERVICE("Services"),
  REPOSITORY("Repositories"),
  COMPONENT("Components"),
  CONFIGURATION("Configurations"),
  PROPERTIES("Configuration Properties"),
}

/** A bean declared in project sources: a stereotype class, a Spring Data repository interface, or a `@Bean` method. */
class BeanInfo(val element: PsiModifierListOwner, val name: String, val kind: BeanKind, val typeText: String, val module: String,
               val isInterface: Boolean = false) {
  /** The `@Bean` methods of a configuration class. */
  val beanMethods: MutableList<BeanInfo> = ArrayList()
}

/** One dependency of a bean: an injection point and the beans that can fill it. */
class BeanDependency(val point: InjectionPoint, val candidates: List<PsiModifierListOwner>)

/**
 * The project's beans for the Spring Beans window, collected from annotations and indexes when the window asks; there
 * is no background work and nothing is kept between refreshes.
 */
object SpringBeanCatalog {
  private const val CONTROLLER = "org.springframework.stereotype.Controller"
  private const val SERVICE = "org.springframework.stereotype.Service"
  private const val REPOSITORY = "org.springframework.stereotype.Repository"
  private const val CONFIGURATION = "org.springframework.context.annotation.Configuration"

  /** Production beans of every module, grouped by module name. Call in a smart read action. */
  fun collect(project: Project): Map<String, List<BeanInfo>> {
    if (!SpringProjectGate.isSpringProject(project)) return emptyMap()
    val facade = JavaPsiFacade.getInstance(project)
    val all = GlobalSearchScope.allScope(project)
    val component = facade.findClass(SpringNames.COMPONENT, all) ?: return emptyMap()
    val result = LinkedHashMap<String, List<BeanInfo>>()
    for (module in ModuleManager.getInstance(project).modules.sortedBy { it.name }) {
      ProgressManager.checkCanceled()
      val beans = beansOf(module, component).takeIf { it.isNotEmpty() } ?: continue
      result[module.name] = beans
    }
    return result
  }

  private fun beansOf(module: Module, component: PsiClass): List<BeanInfo> {
    val project = module.project
    val scope = module.moduleProductionSourceScope
    val classes = LinkedHashSet<PsiClass>()
    // @Component and every annotation that carries it, @Service and @RestController as well as a project's own @UseCase
    val stereotypes = listOf(component) + MetaAnnotationUtil.getAnnotationTypesWithChildren(module, SpringNames.COMPONENT, false)
    for (annotation in stereotypes.distinct()) {
      AnnotatedElementsSearch.searchPsiClasses(annotation, scope).forEach { if (SpringBeans.isBeanClass(it)) classes.add(it) }
    }
    JavaPsiFacade.getInstance(project).findClass(SpringNames.CONFIGURATION_PROPERTIES, GlobalSearchScope.allScope(project))?.let { properties ->
      AnnotatedElementsSearch.searchPsiClasses(properties, scope).forEach { if (!it.isAnnotationType) classes.add(it) }
    }
    JavaPsiFacade.getInstance(project).findClass(SpringNames.DATA_REPOSITORY, GlobalSearchScope.allScope(project))?.let { repository ->
      ClassInheritorsSearch.search(repository, scope, true).forEach { if (it.isInterface && SpringBeans.isBeanClass(it)) classes.add(it) }
    }
    return classes.filter { ModuleUtilCore.findModuleForPsiElement(it) == module }.map { psiClass ->
      val kind = kindOf(psiClass)
      BeanInfo(psiClass, SpringBeans.beanName(psiClass) ?: psiClass.name.orEmpty(), kind, psiClass.qualifiedName ?: psiClass.name.orEmpty(), module.name,
               psiClass.isInterface).also { info ->
        if (kind == BeanKind.CONFIGURATION) {
          psiClass.methods.filter(SpringBeans::isBeanMethod).forEach { method ->
            val returned = PsiUtil.resolveClassInClassTypeOnly(method.returnType)
            info.beanMethods += BeanInfo(method, SpringBeans.beanName(method) ?: method.name, BeanKind.CONFIGURATION,
                                         returned?.qualifiedName ?: method.returnType?.presentableText.orEmpty(), module.name)
          }
        }
      }
    }.sortedWith(compareBy({ it.kind.ordinal }, { it.name.lowercase() }))
  }

  /**
   * The beans [collect] lists under [name], in its order (except among beans of one module, kind and name, which it
   * keeps in search order), found from the name alone: classes named `name` or `Name`,
   * methods named `name`, and the classes of files that have the name in a string (`@Service("name")`,
   * `@Bean(name = "name")`). Each candidate is then held to the same rules as [beansOf]. Null when words cannot find
   * every bean of that name: a name without letters or digits, or a bean name in the project written as something
   * other than a plain string literal (a constant, a concatenation, a stereotype's default value).
   */
  fun named(project: Project, name: String): List<PsiModifierListOwner>? {
    if (!SpringProjectGate.isSpringProject(project)) return emptyList()
    val facade = JavaPsiFacade.getInstance(project)
    val all = GlobalSearchScope.allScope(project)
    val component = facade.findClass(SpringNames.COMPONENT, all) ?: return emptyList()
    if (StringUtil.getWordsIn(name).isEmpty() || hasUnsearchableNames(project)) return null
    val scope = GlobalSearchScope.projectScope(project)
    val names = PsiShortNamesCache.getInstance(project)
    val candidates = LinkedHashSet<PsiClass>()
    for (short in setOf(name, StringUtil.capitalize(name))) candidates += names.getClassesByName(short, scope)
    names.getMethodsByName(name, scope).mapNotNullTo(candidates) { it.containingClass }
    PsiSearchHelper.getInstance(project).processCandidateFilesForText(scope, UsageSearchContext.IN_STRINGS, true, name) { file ->
      (PsiManager.getInstance(project).findFile(file) as? PsiClassOwner)?.classes?.forEach { addWithNested(it, candidates) }
      true
    }
    val properties = facade.findClass(SpringNames.CONFIGURATION_PROPERTIES, all)
    val repository = facade.findClass(SpringNames.DATA_REPOSITORY, all)
    class Entry(val element: PsiModifierListOwner, val module: String, val kind: Int, val owner: String, val index: Int)
    val entries = ArrayList<Entry>()
    for (psiClass in candidates) {
      ProgressManager.checkCanceled()
      val module = ModuleUtilCore.findModuleForPsiElement(psiClass) ?: continue
      val file = PsiUtilCore.getVirtualFile(psiClass) ?: continue
      if (!module.moduleProductionSourceScope.contains(file)) continue
      val stereotypes = (listOf(component) + MetaAnnotationUtil.getAnnotationTypesWithChildren(module, SpringNames.COMPONENT, false)).mapNotNull { it.qualifiedName }
      val listed = stereotypes.any { psiClass.hasAnnotation(it) } && SpringBeans.isBeanClass(psiClass) ||
                   properties != null && !psiClass.isAnnotationType && psiClass.hasAnnotation(SpringNames.CONFIGURATION_PROPERTIES) ||
                   repository != null && psiClass.isInterface && SpringBeans.isBeanClass(psiClass) && psiClass.isInheritor(repository, true)
      if (!listed) continue
      val kind = kindOf(psiClass)
      val classBean = SpringBeans.beanName(psiClass) ?: psiClass.name.orEmpty()
      if (classBean == name) entries += Entry(psiClass, module.name, kind.ordinal, classBean.lowercase(), -1)
      if (kind == BeanKind.CONFIGURATION) {
        psiClass.methods.filter(SpringBeans::isBeanMethod).forEachIndexed { index, method ->
          if ((SpringBeans.beanName(method) ?: method.name) == name) entries += Entry(method, module.name, kind.ordinal, classBean.lowercase(), index)
        }
      }
    }
    // collect() sorts modules by name and beans by kind and name, with each class's @Bean methods right after it
    return entries.sortedWith(compareBy({ it.module }, { it.kind }, { it.owner }, { it.index })).map { it.element }
  }

  private fun addWithNested(psiClass: PsiClass, to: MutableSet<PsiClass>) {
    to += psiClass
    psiClass.innerClasses.forEach { addWithNested(it, to) }
  }

  /**
   * Whether some bean name cannot be found by its words: a stereotype or `@Bean` given a name that is not a plain
   * string literal, a stereotype whose `value` has a default, or a Kotlin type alias of a stereotype. Annotation
   * files are found by the annotation's name in code (an import alias still imports the real name). Only class and
   * method annotations and type aliases matter, so typing in a body keeps the result: the scan reads every annotated
   * file, and their per-file results go when the garbage collector drops the PSI of files not open in an editor.
   */
  internal fun hasUnsearchableNames(project: Project): Boolean = CachedValuesManager.getManager(project).getCachedValue(project) {
    CachedValueProvider.Result.create(findUnsearchableNames(project), *SpringStructureTracker.deps(project))
  }

  @TestOnly
  val unsearchableNameScans: AtomicInteger = AtomicInteger()

  internal fun findUnsearchableNames(project: Project): Boolean {
    unsearchableNameScans.incrementAndGet()
    val component = JavaPsiFacade.getInstance(project).findClass(SpringNames.COMPONENT, GlobalSearchScope.allScope(project)) ?: return false
    val stereotypes = (listOf(component) + ModuleManager.getInstance(project).modules.flatMap {
      MetaAnnotationUtil.getAnnotationTypesWithChildren(it, SpringNames.COMPONENT, false)
    }).distinct()
    val withDefault = stereotypes.any { type ->
      type.findMethodsByName("value", false).any { method ->
        (method as? PsiAnnotationMethod)?.defaultValue?.let { AnnotationUtil.getStringAttributeValue(it) }?.isNotBlank() == true
      }
    }
    if (withDefault) return true
    val qualifiedNames = stereotypes.mapNotNull { it.qualifiedName }.toSet() + SpringNames.BEAN
    val shortNames = qualifiedNames.map { it.substringAfterLast('.') }.toSet()
    val scope = GlobalSearchScope.projectScope(project)
    val helper = PsiSearchHelper.getInstance(project)
    val psiManager = PsiManager.getInstance(project)
    var found = false
    for (word in shortNames) {
      helper.processCandidateFilesForText(scope, UsageSearchContext.IN_CODE, true, word) { file ->
        ProgressManager.checkCanceled()
        val uses = psiManager.findFile(file)?.let(::nameUses) ?: return@processCandidateFilesForText true
        found = uses.annotations.any { qualifiedName(it) in qualifiedNames } || uses.aliased.any { it in shortNames }
        !found
      }
      if (found) return true
    }
    return false
  }

  /**
   * Class and method annotations of a file whose `value` or `name` is not plain string literals, and the short names
   * Kotlin type aliases in it stand for (`typealias Svc = Service` names beans under another annotation name).
   */
  private class NameUses(val annotations: List<PsiElement>, val aliased: Set<String>)

  // resolved on every scan, not kept with the file: the annotation class may live in another file
  private fun qualifiedName(annotation: PsiElement): String? =
    (annotation as? PsiAnnotation)?.qualifiedName ?: annotation.toUElementOfType<UAnnotation>()?.qualifiedName

  private val TYPE_ALIAS = Regex("""\btypealias\s+\w+(?:<[^>]*>)?\s*=\s*([\w.]+)""")

  // kept until the file changes: the scan reruns after every code edit, and most files are unchanged
  private fun nameUses(file: PsiFile): NameUses = CachedValuesManager.getCachedValue(file) {
    val annotations = ArrayList<PsiElement>()
    fun visitU(uClass: UClass) {
      (uClass.uAnnotations + uClass.methods.flatMap { it.uAnnotations }).filter { annotation ->
        annotation.attributeValues.any { (it.name ?: "value") in NAME_ATTRIBUTES && !isPlainStrings(it.expression) }
      }.mapNotNullTo(annotations) { it.sourcePsi }
      uClass.innerClasses.forEach(::visitU)
    }
    // Java PSI where UAST does not cover the language (Groovy beans)
    fun visitPsi(psiClass: PsiClass) {
      (listOf(psiClass) + psiClass.methods).flatMap { it.annotations.toList() }.filterTo(annotations) { annotation ->
        annotation.parameterList.attributes.any { (it.name ?: "value") in NAME_ATTRIBUTES && it.value?.let(::isPlainStrings) != true }
      }
      psiClass.innerClasses.forEach(::visitPsi)
    }
    val uFile = file.toUElement() as? UFile
    if (uFile != null) uFile.classes.forEach(::visitU) else (file as? PsiClassOwner)?.classes?.forEach(::visitPsi)
    val aliased = if (file.language.id != "kotlin") emptySet() else TYPE_ALIAS.findAll(file.text).map { it.groupValues[1].substringAfterLast('.') }.toSet()
    CachedValueProvider.Result.create(NameUses(annotations, aliased), file)
  }

  private val NAME_ATTRIBUTES = setOf("value", "name")

  private fun isPlainStrings(value: PsiAnnotationMemberValue): Boolean = when (value) {
    is PsiLiteral -> value.value is String && !value.text.contains('\\') && !value.text.startsWith("\"\"\"")
    is PsiArrayInitializerMemberValue -> value.initializers.all(::isPlainStrings)
    else -> false
  }

  /** A string literal the word index sees as written, or an array of them: no escapes, templates or text blocks. */
  private fun isPlainStrings(expression: UExpression): Boolean = when (expression) {
    is ULiteralExpression -> expression.value is String && expression.sourcePsi?.text?.let { !it.contains('\\') && !it.startsWith("\"\"\"") } == true
    is UPolyadicExpression -> expression.operands.size == 1 && isPlainStrings(expression.operands.single()) &&
                              expression.sourcePsi?.text?.let { !it.contains('\\') && !it.contains('$') && !it.startsWith("\"\"\"") } == true
    is UCallExpression -> (expression.kind == UastCallKind.NESTED_ARRAY_INITIALIZER || expression.kind == UastCallKind.NEW_ARRAY_WITH_INITIALIZER ||
                           expression.methodName == "arrayOf") && expression.valueArguments.all(::isPlainStrings)
    else -> false
  }

  fun kindOf(psiClass: PsiClass): BeanKind = when {
    psiClass.isInterface -> BeanKind.REPOSITORY
    psiClass.hasAnnotation(SpringNames.CONFIGURATION_PROPERTIES) && !MetaAnnotationUtil.isMetaAnnotated(psiClass, listOf(SpringNames.COMPONENT)) -> BeanKind.PROPERTIES
    MetaAnnotationUtil.isMetaAnnotated(psiClass, listOf(CONTROLLER)) -> BeanKind.CONTROLLER
    MetaAnnotationUtil.isMetaAnnotated(psiClass, listOf(CONFIGURATION)) -> BeanKind.CONFIGURATION
    MetaAnnotationUtil.isMetaAnnotated(psiClass, listOf(REPOSITORY)) -> BeanKind.REPOSITORY
    MetaAnnotationUtil.isMetaAnnotated(psiClass, listOf(SERVICE)) -> BeanKind.SERVICE
    else -> BeanKind.COMPONENT
  }

  /** What [bean] has injected, with the beans that can fill each point. Call in a smart read action. */
  fun dependencies(bean: PsiModifierListOwner): List<BeanDependency> {
    val points = when (bean) {
      is PsiMethod -> bean.toUElementOfType<UMethod>()?.uastParameters.orEmpty().mapNotNull { SpringBeans.injectionPointOf(it) }
      is PsiClass -> {
        val uClass = bean.toUElementOfType<UClass>() ?: return emptyList()
        val fromConstructors = uClass.methods.filter { it.isConstructor }.flatMap { m -> m.uastParameters.mapNotNull { SpringBeans.injectionPointOf(it) } }
        val fromSetters = uClass.methods.filter { !it.isConstructor && SpringBeans.isInjectMarked(it.javaPsi) }
          .flatMap { m -> m.uastParameters.mapNotNull { SpringBeans.injectionPointOf(it) } }
        val fromFields = uClass.fields.mapNotNull { SpringBeans.injectionPointOf(it) }
        (fromConstructors + fromSetters + fromFields).distinctBy { it.element }
      }
      else -> emptyList()
    }
    return points.map { BeanDependency(it, SpringBeans.findCandidates(it)) }
  }

  /** The beans that have [bean] injected, by the class (or `@Bean` method) holding the injection point. */
  fun dependents(bean: PsiModifierListOwner): List<PsiModifierListOwner> =
    SpringBeans.findInjectionPoints(bean).mapNotNull { point ->
      val element = point.element
      val method = (element as? PsiParameter)?.declarationScope as? PsiMethod
      when {
        method != null && SpringBeans.isBeanMethod(method) -> method
        else -> (method?.containingClass ?: (element as? PsiField)?.containingClass)
      }
    }.distinct()
}
