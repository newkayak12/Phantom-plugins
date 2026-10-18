// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring.core.ide

import com.intellij.codeInsight.MetaAnnotationUtil
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInspection.*
import com.intellij.icons.AllIcons
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.uast.injectionHostUExpression
import com.intellij.psi.*
import com.intellij.psi.registerUastReferenceProvider
import com.intellij.psi.uastInjectionHostReferenceProvider
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.uast.UastHintedVisitorAdapter
import dev.phantom.spring.core.*
import org.jetbrains.annotations.NonNls
import org.jetbrains.jps.model.java.JavaSourceRootType
import org.jetbrains.uast.*
import org.jetbrains.uast.visitor.AbstractUastNonRecursiveVisitor

/** Project beans by name, from the same collection the Spring Beans window uses, recomputed after PSI changes. */
object SpringBeanNames {
  fun byName(project: Project): Map<String, List<PsiModifierListOwner>> = CachedValuesManager.getManager(project).getCachedValue(project) {
    val map = LinkedHashMap<String, MutableList<PsiModifierListOwner>>()
    for (beans in SpringBeanCatalog.collect(project).values) {
      for (bean in beans) {
        map.getOrPut(bean.name) { ArrayList() }.add(bean.element)
        bean.beanMethods.forEach { map.getOrPut(it.name) { ArrayList() }.add(it.element) }
      }
    }
    CachedValueProvider.Result.create<Map<String, List<PsiModifierListOwner>>>(map, PsiModificationTracker.MODIFICATION_COUNT)
  }

  private const val CONDITIONAL = "org.springframework.context.annotation.Conditional"

  /** Beans behind a profile or condition may never meet; same-named overloads of a `@Bean` method are one bean. */
  fun clash(a: PsiModifierListOwner, b: PsiModifierListOwner): Boolean {
    if (a == b || isConditional(a) || isConditional(b)) return false
    if (a is PsiMethod && b is PsiMethod && a.containingClass == b.containingClass) return false
    val ma = ModuleUtilCore.findModuleForPsiElement(a) ?: return false
    val mb = ModuleUtilCore.findModuleForPsiElement(b) ?: return false
    return sharesApplication(ma, mb)
  }

  private fun isConditional(bean: PsiModifierListOwner): Boolean =
    listOfNotNull(bean, (bean as? PsiMember)?.containingClass).any { MetaAnnotationUtil.isMetaAnnotated(it, listOf(CONDITIONAL)) }

  /** Both modules end up in one production classpath: one depends on the other, or a production module on both. */
  private fun sharesApplication(a: Module, b: Module): Boolean {
    if (a == b) return true
    val aUsers = ModuleUtilCore.getAllDependentModules(a).toSet() + a
    val bUsers = ModuleUtilCore.getAllDependentModules(b).toSet() + b
    return aUsers.intersect(bUsers).any { it == a || it == b || isProduction(it) }
  }

  private fun isProduction(module: Module): Boolean = ModuleRootManager.getInstance(module).getSourceRoots(JavaSourceRootType.SOURCE).isNotEmpty()
}

/** Two beans with one name stop a Spring Boot application from starting (bean overriding is off by default). */
class SpringDuplicateBeanNameInspection : AbstractBaseUastLocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    if (!SpringProjectGate.isSpringProject(holder.project) || SpringBeans.isInTestSources(holder.file)) return PsiElementVisitor.EMPTY_VISITOR
    return UastHintedVisitorAdapter.create(holder.file.language, object : AbstractUastNonRecursiveVisitor() {
      override fun visitClass(node: UClass): Boolean {
        val psiClass = node.javaPsi
        if (SpringBeans.isBeanClass(psiClass)) check(node, psiClass)
        return true
      }

      override fun visitMethod(node: UMethod): Boolean {
        if (SpringBeans.isBeanMethod(node.javaPsi)) check(node, node.javaPsi)
        return true
      }

      private fun check(node: UDeclaration, bean: PsiModifierListOwner) {
        val name = SpringBeans.beanName(bean) ?: return
        val others = SpringBeanNames.byName(holder.project)[name].orEmpty().filter { SpringBeanNames.clash(bean, it) }
        if (others.isEmpty()) return
        val where = others.joinToString(", ") { other -> (other as? PsiClass)?.qualifiedName ?: (other as? PsiMethod)?.let { "${it.containingClass?.name}.${it.name}()" } ?: "?" }
        val anchor = node.uastAnchor?.sourcePsi ?: return
        holder.registerProblem(anchor, PhantomSpringBundle.message("inspection.duplicate.bean.message", name, where))
      }
    }, arrayOf(UClass::class.java, UMethod::class.java), true)
  }
}

/** `@Scope("protoype")` fails only when the bean is first created; the known scope names are checked here. */
class SpringScopeInspection : AbstractBaseUastLocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    if (!SpringProjectGate.isSpringProject(holder.project)) return PsiElementVisitor.EMPTY_VISITOR
    return UastHintedVisitorAdapter.create(holder.file.language, object : AbstractUastNonRecursiveVisitor() {
      override fun visitAnnotation(node: UAnnotation): Boolean {
        if (node.qualifiedName != SCOPE) return true
        val value = node.findDeclaredAttributeValue("value") ?: node.findDeclaredAttributeValue("scopeName") ?: return true
        val scope = value.evaluateString() ?: return true
        if (scope.isEmpty() || scope in KNOWN_SCOPES || scope.contains("\${")) return true
        holder.registerProblem(value.sourcePsi ?: return true, PhantomSpringBundle.message("inspection.scope.message", scope))
        return true
      }
    }, arrayOf(UAnnotation::class.java), true)
  }

  companion object {
    @NonNls private const val SCOPE = "org.springframework.context.annotation.Scope"
    /** Core, web, and the ones Spring Cloud and Spring Batch register. */
    @NonNls private val KNOWN_SCOPES = setOf("singleton", "prototype", "request", "session", "application", "websocket", "refresh", "thread", "job", "step")
  }
}

/** Bean names in `@Qualifier("…")` and `@DependsOn("…")`: completion and Ctrl+B to the bean. Library beans stay unresolved quietly. */
class SpringBeanNameReferenceContributor : PsiReferenceContributor() {
  override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
    for ((annotation, attribute) in listOf(SpringNames.QUALIFIER to "value", DEPENDS_ON to "value")) {
      registrar.registerUastReferenceProvider(
        injectionHostUExpression().annotationParam(annotation, attribute),
        uastInjectionHostReferenceProvider { _, host ->
          if (!SpringProjectGate.isSpringProject(host.project)) PsiReference.EMPTY_ARRAY
          else arrayOf(BeanNameReference(host, ElementManipulators.getValueTextRange(host)))
        },
        PsiReferenceRegistrar.DEFAULT_PRIORITY,
      )
    }
  }

  companion object {
    @NonNls const val DEPENDS_ON: String = "org.springframework.context.annotation.DependsOn"
  }
}

class BeanNameReference(host: PsiElement, range: TextRange) : PsiPolyVariantReferenceBase<PsiElement>(host, range, true) {
  override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
    PsiElementResolveResult.createResults(SpringBeanNames.byName(element.project)[value].orEmpty().map { it.navigationElement })

  override fun getVariants(): Array<Any> = SpringBeanNames.byName(element.project).map { (name, beans) ->
    val bean = beans.first()
    LookupElementBuilder.create(name).withIcon(if (bean is PsiMethod) AllIcons.Nodes.Method else AllIcons.Nodes.Class)
      .withTypeText((bean as? PsiClass)?.name ?: (bean as? PsiMethod)?.containingClass?.name, true)
  }.toTypedArray()
}
