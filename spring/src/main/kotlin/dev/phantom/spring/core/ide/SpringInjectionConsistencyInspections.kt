// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring.core.ide

import com.intellij.codeInspection.*
import com.intellij.psi.*
import com.intellij.psi.util.InheritanceUtil
import com.intellij.psi.util.PsiUtil
import com.intellij.uast.UastHintedVisitorAdapter
import dev.phantom.spring.core.*
import org.jetbrains.annotations.NonNls
import org.jetbrains.uast.*
import org.jetbrains.uast.visitor.AbstractUastNonRecursiveVisitor

@NonNls
private object InjectionNames {
  val RESOURCE = listOf("jakarta.annotation.Resource", "javax.annotation.Resource")
  val INJECT = listOf(SpringNames.AUTOWIRED, "jakarta.inject.Inject", "javax.inject.Inject")
  const val REQUIRED = "org.springframework.beans.factory.annotation.Required"
}

/**
 * Injection annotations that contradict each other on one field, method or parameter:
 * - `@Value` with `@Qualifier` or `@Resource`: the value is resolved from configuration, so the qualifier or the
 *   bean lookup is ignored
 * - `@Resource` with `@Autowired`/`@Inject`: two injection mechanisms, by name and by type, for one target
 */
class SpringInjectionConsistencyInspection : AbstractBaseUastLocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    if (!SpringProjectGate.isSpringProject(holder.project)) return PsiElementVisitor.EMPTY_VISITOR
    return UastHintedVisitorAdapter.create(holder.file.language, object : AbstractUastNonRecursiveVisitor() {
      // a Kotlin constructor property is seen once, as the parameter
      override fun visitField(node: UField): Boolean = node.sourcePsi?.toUElementOfType<UParameter>() != null || check(node)
      override fun visitMethod(node: UMethod): Boolean = check(node)
      override fun visitParameter(node: UParameter): Boolean = check(node)

      private fun check(node: UDeclaration): Boolean {
        val annotations = node.uAnnotations
        fun find(names: Collection<String>) = annotations.firstOrNull { it.qualifiedName in names }
        val value = find(listOf(SpringNames.VALUE))
        val qualifier = find(listOf(SpringNames.QUALIFIER))
        val resource = find(InjectionNames.RESOURCE)
        val inject = find(InjectionNames.INJECT)
        val second = when {
          value != null -> qualifier ?: resource
          resource != null -> inject
          else -> null
        } ?: return true
        val first = value ?: resource ?: return true
        val anchor = second.sourcePsi ?: return true
        holder.registerProblem(anchor, PhantomSpringBundle.message("inspection.injection.consistency.message",
                                                                   "@" + simple(second), "@" + simple(first)))
        return true
      }

      private fun simple(annotation: UAnnotation) = annotation.qualifiedName.orEmpty().substringAfterLast('.')
    }, arrayOf(UField::class.java, UMethod::class.java, UParameter::class.java), true)
  }
}

/**
 * `@Required` was deprecated in Spring 5.1 and removed in 6.0 (the processor that enforced it is gone), so it no
 * longer makes anything required. On a method that is not a setter it never did anything.
 */
class SpringRequiredInspection : AbstractBaseUastLocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    if (!SpringProjectGate.isSpringProject(holder.project)) return PsiElementVisitor.EMPTY_VISITOR
    return UastHintedVisitorAdapter.create(holder.file.language, object : AbstractUastNonRecursiveVisitor() {
      override fun visitMethod(node: UMethod): Boolean {
        val required = node.uAnnotations.firstOrNull { it.qualifiedName == InjectionNames.REQUIRED } ?: return true
        val anchor = required.sourcePsi ?: return true
        val method = node.javaPsi
        val setter = method.name.startsWith("set") && method.name.length > 3 && method.parameterList.parametersCount == 1
        val message = if (setter) "inspection.required.removed" else "inspection.required.not.setter"
        holder.registerProblem(anchor, PhantomSpringBundle.message(message), ProblemHighlightType.LIKE_DEPRECATED)
        return true
      }
    }, arrayOf(UMethod::class.java), true)
  }
}

/**
 * `@Qualifier("name")` naming a project bean whose type can never be injected into the point: neither type is a
 * subtype of the other. Spring fails at startup. Beans with the name outside project sources (auto-configuration)
 * are unknown, so the check only runs when every bean of that name is a project bean.
 */
class SpringQualifierTypeInspection : AbstractBaseUastLocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    if (!SpringProjectGate.isSpringProject(holder.project) || SpringBeans.isInTestSources(holder.file)) return PsiElementVisitor.EMPTY_VISITOR
    return UastHintedVisitorAdapter.create(holder.file.language, object : AbstractUastNonRecursiveVisitor() {
      override fun visitParameter(node: UParameter): Boolean = check(node)
      override fun visitField(node: UField): Boolean = check(node)

      private fun check(node: UDeclaration): Boolean {
        val point = SpringBeans.injectionPointOf(node) ?: return true
        val name = point.qualifier ?: return true
        if (SpringAutowiringInspection.isMultiValued(point.type)) return true
        val pointClass = PsiUtil.resolveClassInClassTypeOnly(point.type) ?: return true
        if (pointClass is PsiTypeParameter || pointClass.qualifiedName == CommonClassNames.JAVA_LANG_OBJECT) return true
        val beans = SpringBeanNames.byName(holder.project)[name].orEmpty()
        if (beans.isEmpty()) return true
        val types = beans.map { beanClass(it) ?: return true }
        if (types.any { compatible(it, pointClass) }) return true
        val anchor = node.uastAnchor?.sourcePsi ?: return true
        holder.registerProblem(anchor, PhantomSpringBundle.message("inspection.qualifier.type.message", name, types.first().name ?: "", pointClass.name ?: ""))
        return true
      }

      private fun beanClass(bean: PsiModifierListOwner): PsiClass? = when (bean) {
        is PsiClass -> bean
        is PsiMethod -> bean.returnType?.let { PsiUtil.resolveClassInClassTypeOnly(it) }?.takeIf { it !is PsiTypeParameter }
        else -> null
      }

      private fun compatible(a: PsiClass, b: PsiClass): Boolean =
        InheritanceUtil.isInheritorOrSelf(a, b, true) || InheritanceUtil.isInheritorOrSelf(b, a, true) ||
        // a FactoryBean produces another type than its own
        InheritanceUtil.isInheritor(a, "org.springframework.beans.factory.FactoryBean")
    }, arrayOf(UParameter::class.java, UField::class.java), true)
  }
}
