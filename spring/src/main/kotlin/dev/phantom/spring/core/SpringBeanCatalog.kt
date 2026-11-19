// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring.core

import com.intellij.codeInsight.MetaAnnotationUtil
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.util.PsiUtil
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UMethod
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
