// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.jpa.core

import com.intellij.codeInsight.AnnotationUtil
import com.intellij.codeInspection.AbstractBaseUastLocalInspectionTool
import com.intellij.codeInspection.LocalInspectionToolSession
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.util.TextRange
import com.intellij.psi.*
import com.intellij.psi.util.InheritanceUtil
import com.intellij.psi.util.PsiUtil
import com.intellij.uast.UastHintedVisitorAdapter
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.getParentOfType
import org.jetbrains.uast.toUElement
import org.jetbrains.uast.visitor.AbstractUastNonRecursiveVisitor

/**
 * Methods whose parameters name entity attributes: Jakarta Data `@Find`/`@Delete` and Hibernate's `@Find`. Each
 * parameter matches the attribute of the same name (or the one `@By` names) and must have a comparable type.
 */
internal object AttributeParameters {
  /** Parameters the provider passes alongside the attribute arguments (paging, sorting, restrictions, sessions). */
  private val SPECIAL_PACKAGES = listOf("jakarta.data", "org.hibernate", "jakarta.persistence")
  private val WRAPPERS = setOf("java.util.Optional", "java.util.List", "java.util.Set", "java.util.Collection", "java.util.stream.Stream",
                               "jakarta.data.page.Page", "jakarta.data.page.CursoredPage", "java.util.concurrent.CompletionStage")
  const val BY: String = "jakarta.data.repository.By"

  fun isSpecial(parameter: PsiParameter): Boolean {
    val psiClass = PsiUtil.resolveClassInClassTypeOnly(parameter.type) ?: return false
    val fqn = psiClass.qualifiedName ?: return false
    return SPECIAL_PACKAGES.any { fqn.startsWith("$it.") } && !JpaModel.isPersistent(psiClass)
  }

  /** The entity a method returns, through `Optional`, collections, streams, pages and arrays. */
  fun returnedEntity(method: PsiMethod): PsiClass? {
    var type = method.returnType ?: return null
    repeat(2) {
      if (type is PsiArrayType) type = (type as PsiArrayType).componentType
      val classType = type as? PsiClassType ?: return null
      val psiClass = classType.resolve() ?: return null
      if (psiClass.qualifiedName !in WRAPPERS) return psiClass.takeIf { JpaModel.isEntity(it) }
      val inner = classType.parameters.firstOrNull() ?: return null
      type = if (inner is PsiWildcardType) inner.bound ?: return null else inner
    }
    return PsiUtil.resolveClassInClassTypeOnly(type)?.takeIf { JpaModel.isEntity(it) }
  }

  fun check(holder: ProblemsHolder, method: PsiMethod, entity: PsiClass) {
    for (parameter in method.parameterList.parameters) {
      if (isSpecial(parameter)) continue
      val anchor = parameter.toUElement()?.sourcePsi ?: continue
      val by = parameter.getAnnotation(BY)?.let { AnnotationUtil.getStringAttributeValue(it, "value") }
      val name = by ?: parameter.name
      // `id(this)` and other function forms are resolved by the provider
      if (name.contains('(') || name.isEmpty()) continue
      val path = resolve(entity, name)
      if (path == null) {
        holder.registerProblem(anchor, PhantomJpaBundle.message("attribute.parameter.unknown", name, entity.name ?: ""), ProblemHighlightType.GENERIC_ERROR_OR_WARNING)
        continue
      }
      val attributeType = when (val member = path.last()) {
        is PsiField -> member.type
        is PsiMethod -> member.returnType
        else -> null
      } ?: continue
      if (!comparable(attributeType, parameter.type, parameter)) {
        holder.registerProblem(anchor, PhantomJpaBundle.message("attribute.parameter.type", parameter.name, parameter.type.presentableText, name, attributeType.presentableText))
      }
    }
  }

  /** `name` or a dotted / `_`-separated path into embedded types and associations. */
  private fun resolve(entity: PsiClass, name: String): List<PsiMember>? {
    var owner: PsiClass = entity
    val result = ArrayList<PsiMember>()
    for (segment in name.split('.', '_').filter { it.isNotEmpty() }) {
      val member = JpaModel.attribute(owner, segment) ?: return null
      result.add(member)
      owner = JpaModel.valueClass(member) ?: break
    }
    return result.takeIf { it.isNotEmpty() }
  }

  /** Equal after boxing or assignable; collections (an `in` list), arrays, generics and unknown types never warn. */
  private fun comparable(attribute: PsiType, parameter: PsiType, context: PsiElement): Boolean {
    if (parameter is PsiArrayType || JpaMapping.isCollectionOrMap(parameter) || JpaMapping.isCollectionOrMap(attribute)) return true
    val manager = PsiManager.getInstance(context.project)
    fun box(t: PsiType): PsiClassType? = if (t is PsiPrimitiveType) t.getBoxedType(manager, context.resolveScope) else t as? PsiClassType
    val a = box(attribute) ?: return true
    val p = box(parameter) ?: return true
    val ac = a.resolve() ?: return true
    val pc = p.resolve() ?: return true
    if (ac is PsiTypeParameter || pc is PsiTypeParameter) return true
    return a.isAssignableFrom(p) || p.isAssignableFrom(a)
  }
}

/**
 * Jakarta Data repositories (`@Repository` from `jakarta.data`, or a `DataRepository` subtype):
 * - `@Find` / `@Delete` parameters must name entity attributes with a matching type
 * - `@Insert`, `@Update` and `@Save` take exactly one parameter, the entity or a collection of it
 * - query-by-method-name methods (`findBy…`) must name existing attributes
 */
class JakartaDataMethodInspection : AbstractBaseUastLocalInspectionTool() {
  override fun isAvailableForFile(file: PsiFile): Boolean = !GhostMode.enabled && super.isAvailableForFile(file)
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    if (JavaPsiFacade.getInstance(holder.project).findClass(DATA_REPOSITORY, holder.file.resolveScope) == null) return PsiElementVisitor.EMPTY_VISITOR
    return UastHintedVisitorAdapter.create(holder.file.language, object : AbstractUastNonRecursiveVisitor() {
      override fun visitMethod(node: UMethod): Boolean {
        val method = node.javaPsi
        if (node.uastBody != null || method.hasModifierProperty(PsiModifier.STATIC)) return true
        val repository = node.getParentOfType<UClass>()?.javaPsi ?: return true
        if (!repository.isInterface || !isRepository(repository)) return true
        val entity = entityOf(repository) ?: AttributeParameters.returnedEntity(method)
        val identifier = node.uastAnchor?.sourcePsi ?: return true
        val lifecycle = LIFECYCLE.firstOrNull { method.hasAnnotation(it) }
        when {
          lifecycle != null -> {
            if (method.parameterList.parametersCount != 1) {
              holder.registerProblem(identifier, PhantomJpaBundle.message("jakarta.data.lifecycle.parameters", "@" + lifecycle.substringAfterLast('.')),
                                     ProblemHighlightType.GENERIC_ERROR)
            }
          }
          method.hasAnnotation(FIND) || method.hasAnnotation(DELETE) -> {
            val target = AttributeParameters.returnedEntity(method) ?: entity ?: return true
            AttributeParameters.check(holder, method, target)
          }
          QUERY_ANNOTATIONS.none { method.hasAnnotation(it) } && entity != null && DerivedQuery.PREFIX.containsMatchIn(method.name) -> {
            if (identifier.text != method.name) return true
            val parsed = DerivedQuery.parse(method.name) ?: return true
            for (part in parsed.parts + parsed.orderBy) {
              if (DerivedQuery.resolvePath(entity, part.propertySource) != null) continue
              holder.registerProblem(identifier, PhantomJpaBundle.message("derived.unknown.property", DerivedQuery.decapitalize(part.propertySource), entity.name ?: ""),
                                     ProblemHighlightType.GENERIC_ERROR_OR_WARNING, TextRange(part.offset, part.offset + part.propertySource.length))
              return true
            }
          }
        }
        return true
      }
    }, arrayOf(UMethod::class.java), true)
  }

  private fun isRepository(psiClass: PsiClass): Boolean = psiClass.hasAnnotation(REPOSITORY) || InheritanceUtil.isInheritor(psiClass, DATA_REPOSITORY)

  private fun entityOf(repository: PsiClass): PsiClass? {
    if (!InheritanceUtil.isInheritor(repository, DATA_REPOSITORY)) return null
    val type = JavaPsiFacade.getElementFactory(repository.project).createType(repository)
    return PsiUtil.substituteTypeParameter(type, DATA_REPOSITORY, 0, false)?.let { PsiUtil.resolveClassInClassTypeOnly(it) }?.takeIf { JpaModel.isEntity(it) }
  }

  private companion object {
    const val DATA_REPOSITORY = "jakarta.data.repository.DataRepository"
    const val REPOSITORY = "jakarta.data.repository.Repository"
    const val FIND = "jakarta.data.repository.Find"
    const val DELETE = "jakarta.data.repository.Delete"
    val LIFECYCLE = listOf("jakarta.data.repository.Insert", "jakarta.data.repository.Update", "jakarta.data.repository.Save")
    val QUERY_ANNOTATIONS = listOf("jakarta.data.repository.Query", "org.hibernate.annotations.processing.HQL", "org.hibernate.annotations.processing.SQL")
  }
}

/**
 * Hibernate's `@Find` finder methods (checked by Hibernate's annotation processor at build time): every parameter
 * must name an attribute of the returned entity, with a matching type.
 */
class HibernateFindInspection : AbstractBaseUastLocalInspectionTool() {
  override fun isAvailableForFile(file: PsiFile): Boolean = !GhostMode.enabled && super.isAvailableForFile(file)
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    if (JavaPsiFacade.getInstance(holder.project).findClass(FIND, holder.file.resolveScope) == null) return PsiElementVisitor.EMPTY_VISITOR
    val module = ModuleUtilCore.findModuleForPsiElement(holder.file) ?: return PsiElementVisitor.EMPTY_VISITOR
    if (!JpaGate.isJpaModule(module)) return PsiElementVisitor.EMPTY_VISITOR
    return UastHintedVisitorAdapter.create(holder.file.language, object : AbstractUastNonRecursiveVisitor() {
      override fun visitMethod(node: UMethod): Boolean {
        val method = node.javaPsi
        if (!method.hasAnnotation(FIND)) return true
        // a return type that is not an entity (a projection, a reactive wrapper) is left to Hibernate's processor
        val entity = AttributeParameters.returnedEntity(method) ?: return true
        AttributeParameters.check(holder, method, entity)
        return true
      }
    }, arrayOf(UMethod::class.java), true)
  }

  private companion object {
    const val FIND = "org.hibernate.annotations.processing.Find"
  }
}
