// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring.core.ide

import com.intellij.codeInsight.AnnotationUtil
import com.intellij.codeInsight.MetaAnnotationUtil
import com.intellij.codeInsight.NullableNotNullManager
import com.intellij.codeInspection.*
import com.intellij.lang.jvm.actions.annotationRequest
import com.intellij.lang.jvm.actions.createAddAnnotationActions
import com.intellij.lang.jvm.actions.stringAttribute
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.GlobalSearchScopesCore
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.uast.UastHintedVisitorAdapter
import dev.phantom.spring.core.*
import org.jetbrains.uast.*
import org.jetbrains.uast.visitor.AbstractUastNonRecursiveVisitor

/**
 * Warns where Spring would fail to start: an injection point with no bean of its type, or with several and nothing
 * (qualifier, `@Primary`, parameter name) to pick one. Only types declared in project sources are checked, since
 * library beans come from auto-configuration we do not model, and test sources are skipped (test slices and mocks
 * decide what exists there). When unsure it stays quiet: a false "no bean" is worse than a missed one.
 */
class SpringAutowiringInspection : AbstractBaseUastLocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    val file = holder.file
    if (!SpringProjectGate.isSpringProject(holder.project) || SpringBeans.isInTestSources(file)) return PsiElementVisitor.EMPTY_VISITOR
    return UastHintedVisitorAdapter.create(file.language, Visitor(holder), arrayOf(UParameter::class.java, UField::class.java), true)
  }

  private class Visitor(private val holder: ProblemsHolder) : AbstractUastNonRecursiveVisitor() {
    override fun visitParameter(node: UParameter): Boolean {
      check(node)
      return true
    }

    override fun visitField(node: UField): Boolean {
      check(node)
      return true
    }

    private fun check(node: UDeclaration) {
      val point = SpringBeans.injectionPointOf(node) ?: return
      if (isMultiValued(point.type)) return
      val beanType = SpringBeans.beanTypeOf(point.type) ?: return
      if (!SpringBeans.isProjectSourceClass(beanType)) return
      val typeName = beanType.name ?: return

      val candidates = narrowByGenerics(point, SpringBeans.findCandidates(point))
      when {
        candidates.isEmpty() -> {
          if (isOptional(node, point) || !couldBeBean(beanType) || isRegisteredElsewhere(beanType)) return
          val message = point.qualifier?.let { PhantomSpringBundle.message("inspection.autowiring.no.bean.named", typeName, it) }
                        ?: PhantomSpringBundle.message("inspection.autowiring.no.bean", typeName)
          holder.registerUProblem(node, message)
        }
        candidates.size > 1 && point.qualifier == null && !resolvesToOne(point, candidates) -> {
          val names = candidates.mapNotNull { SpringBeans.beanName(it) }.sorted()
          val message = PhantomSpringBundle.message("inspection.autowiring.multiple", typeName, names.joinToString(", "))
          holder.registerUProblem(node, message, *fixes(point, candidates))
        }
      }
    }

    private fun fixes(point: InjectionPoint, unordered: List<PsiModifierListOwner>): Array<LocalQuickFix> {
      val candidates = unordered.sortedBy { SpringBeans.beanName(it).orEmpty() }
      val target = point.element as? com.intellij.lang.jvm.JvmModifiersOwner ?: return LocalQuickFix.EMPTY_ARRAY
      val actions = ArrayList<com.intellij.codeInsight.intention.IntentionAction>()
      for (candidate in candidates.take(MAX_FIXED_CANDIDATES)) {
        val name = SpringBeans.beanName(candidate) ?: continue
        actions += createAddAnnotationActions(target, annotationRequest(SpringNames.QUALIFIER, stringAttribute("value", name)))
      }
      for (candidate in candidates.take(MAX_FIXED_CANDIDATES)) {
        val owner = candidate as? com.intellij.lang.jvm.JvmModifiersOwner ?: continue
        if (!SpringBeans.isProjectSourceClass((candidate as? PsiClass) ?: (candidate as PsiMember).containingClass ?: continue)) continue
        actions += createAddAnnotationActions(owner, annotationRequest(SpringNames.PRIMARY))
      }
      return IntentionWrapper.wrapToQuickFixes(actions, holder.file).toTypedArray()
    }
  }

  companion object {
    private const val MAX_FIXED_CANDIDATES = 3
    private const val CONDITIONAL = "org.springframework.context.annotation.Conditional"

    /** Collections, maps, arrays, `Optional` and providers accept any number of beans, including none. */
    fun isMultiValued(type: PsiType): Boolean {
      if (type is PsiArrayType) return true
      val fqn = (type as? PsiClassType)?.resolve()?.qualifiedName ?: return false
      return fqn in SpringNames.SINGLE_ARG_WRAPPERS || fqn == SpringNames.MAP
    }

    /** `required = false`, a nullable type or a Kotlin default value: Spring starts fine without the bean. */
    private fun isOptional(node: UDeclaration, point: InjectionPoint): Boolean {
      if (node is UParameter && node.uastInitializer != null) return true
      val owner = point.element
      if (NullableNotNullManager.getInstance(owner.project)?.isNullable(owner, false) == true) return true
      val method = (owner as? PsiParameter)?.declarationScope as? PsiMethod
      return listOfNotNull(owner, method).any { o ->
        val autowired = o.getAnnotation(SpringNames.AUTOWIRED) ?: return@any false
        AnnotationUtil.getBooleanAttributeValue(autowired, "required") == false
      }
    }

    /**
     * A missing bean is worth reporting only if a concrete class could be one and simply is not annotated.
     * An interface nobody implements is usually a generated proxy (Feign, MyBatis, HTTP interfaces).
     */
    private fun couldBeBean(beanType: PsiClass): Boolean {
      if (isConcrete(beanType)) return true
      val scope = GlobalSearchScope.projectScope(beanType.project)
      return ClassInheritorsSearch.search(beanType, scope, true).anyMatch { isConcrete(it) }
    }

    private fun isConcrete(psiClass: PsiClass): Boolean =
      !psiClass.isInterface && !psiClass.isAnnotationType && !psiClass.isEnum && !psiClass.hasModifierProperty(PsiModifier.ABSTRACT)

    /** Classes named in an annotation, as in `@Import(Foo.class)` or `@EnableConfigurationProperties(Props.class)`. */
    private fun isRegisteredElsewhere(beanType: PsiClass): Boolean {
      // production only: `@SpringBootTest(classes = …)` and `@WebMvcTest(…)` in tests do not register anything for the app
      val scope = GlobalSearchScopesCore.projectProductionScope(beanType.project)
      val types = LinkedHashSet<PsiClass>().apply {
        add(beanType)
        ClassInheritorsSearch.search(beanType, scope, true).forEach { add(it) }
      }
      return types.any { type ->
        ReferencesSearch.search(type, scope).anyMatch { reference ->
          reference.element.toUElement()?.getParentOfType<UAnnotation>(true) != null
        }
      }
    }

    /** `Repository<User>` must not match a `Repository<Order>` bean. When the types cannot be compared, keep everything. */
    private fun narrowByGenerics(point: InjectionPoint, candidates: List<PsiModifierListOwner>): List<PsiModifierListOwner> {
      val wanted = point.type as? PsiClassType ?: return candidates
      if (wanted.parameterCount == 0 || candidates.size < 2) return candidates
      val factory = JavaPsiFacade.getElementFactory(point.element.project)
      val matching = candidates.filter { candidate ->
        val provided = when (candidate) {
          is PsiClass -> factory.createType(candidate)
          is PsiMethod -> candidate.returnType
          else -> null
        }
        provided == null || wanted.isAssignableFrom(provided)
      }
      return matching.ifEmpty { candidates }
    }

    /** Spring's tie-breaking: one `@Primary` bean, or one whose name is the parameter name. Conditional beans may never coexist. */
    private fun resolvesToOne(point: InjectionPoint, candidates: List<PsiModifierListOwner>): Boolean {
      if (candidates.count { it.hasAnnotation(SpringNames.PRIMARY) } == 1) return true
      if (point.name != null && candidates.count { SpringBeans.beanName(it) == point.name } == 1) return true
      return candidates.count { !isConditional(it) } <= 1
    }

    private fun isConditional(bean: PsiModifierListOwner): Boolean {
      val holders = listOfNotNull(bean, (bean as? PsiMethod)?.containingClass)
      return holders.any { MetaAnnotationUtil.isMetaAnnotated(it, listOf(CONDITIONAL)) }
    }
  }
}
