// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.jpa.core

import com.intellij.codeInspection.AbstractBaseUastLocalInspectionTool
import com.intellij.codeInspection.LocalInspectionToolSession
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.module.ModuleUtilCore
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
 * Spring Data repository method signatures Spring rejects or answers wrongly:
 * - `Page`/`Slice` without a `Pageable` parameter (Spring fails at startup)
 * - `count…By` not returning a number, `exists…By` not returning a boolean
 * - a `find…By` returning another entity than the repository manages
 * - a derived query argument whose type cannot be compared with its property (`Int` for a `Long` id)
 */
class RepositoryMethodInspection : AbstractBaseUastLocalInspectionTool() {
  override fun isAvailableForFile(file: PsiFile): Boolean = !GhostMode.enabled && super.isAvailableForFile(file)
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    val module = ModuleUtilCore.findModuleForPsiElement(holder.file) ?: return PsiElementVisitor.EMPTY_VISITOR
    if (!JpaGate.isJpaModule(module)) return PsiElementVisitor.EMPTY_VISITOR
    return UastHintedVisitorAdapter.create(holder.file.language, Visitor(holder), arrayOf(UMethod::class.java), true)
  }

  private class Visitor(private val holder: ProblemsHolder) : AbstractUastNonRecursiveVisitor() {
    override fun visitMethod(node: UMethod): Boolean {
      val method = node.javaPsi
      if (node.uastBody != null || method.isConstructor || method.hasModifierProperty(PsiModifier.STATIC)) return true
      val repository = node.getParentOfType<UClass>()?.javaPsi ?: return true
      if (!repository.isInterface || !InheritanceUtil.isInheritor(repository, DerivedQuery.REPOSITORY)) return true
      val identifier = node.uastAnchor?.sourcePsi ?: return true
      val returnType = method.returnType ?: return true

      if (isPaged(returnType) && method.parameterList.parameters.none { isPageable(it.type) }) {
        holder.registerProblem(identifier, PhantomJpaBundle.message("repository.page.without.pageable", className(returnType)), ProblemHighlightType.GENERIC_ERROR)
      }
      if (!DerivedQuery.isDerivedQueryMethod(method)) return true
      val entity = DerivedQuery.entityOf(repository) ?: return true
      val verb = DerivedQuery.PREFIX.find(method.name)?.groupValues?.get(1) ?: return true
      when (verb) {
        "count" -> if (!isNumber(returnType)) holder.registerProblem(identifier, PhantomJpaBundle.message("repository.count.type", returnType.presentableText))
        "exists" -> if (!isBoolean(returnType)) holder.registerProblem(identifier, PhantomJpaBundle.message("repository.exists.type", returnType.presentableText))
        "delete", "remove" -> {}
        else -> {
          val element = PsiUtil.resolveClassInClassTypeOnly(unwrap(returnType))
          if (element != null && JpaModel.isEntity(element) && !related(element, entity)) {
            holder.registerProblem(identifier, PhantomJpaBundle.message("repository.other.entity", element.name ?: "", entity.name ?: ""))
          }
        }
      }
      checkArguments(method, entity, identifier)
      return true
    }

    private fun checkArguments(method: PsiMethod, entity: PsiClass, identifier: PsiElement) {
      val parsed = DerivedQuery.parse(method.name) ?: return
      val arguments = method.parameterList.parameters.filter { DerivedQuery.countsAsArgument(it) }
      var index = 0
      for (part in parsed.parts) {
        val path = DerivedQuery.resolvePath(entity, part.propertySource) ?: return
        val operator = part.operator
        val propertyType = memberType(path.last())
        repeat(part.arguments) {
          val parameter = arguments.getOrNull(index++) ?: return
          if (propertyType == null || operator in COLLECTION_OPERATORS || JpaMapping.isCollectionOrMap(propertyType)) return@repeat
          if (comparable(propertyType, parameter.type)) return@repeat
          val anchor = parameter.toUElement()?.sourcePsi ?: identifier
          holder.registerProblem(anchor, PhantomJpaBundle.message("repository.argument.type", parameter.name, parameter.type.presentableText,
                                                                  DerivedQuery.decapitalize(part.propertySource), propertyType.presentableText))
        }
      }
    }

    private fun memberType(member: PsiMember): PsiType? = when (member) {
      is PsiField -> member.type
      is PsiMethod -> member.returnType
      else -> null
    }

    /** Equal after boxing, or one assignable to the other; unknown, generic and type-parameter types never warn. */
    private fun comparable(a: PsiType, b: PsiType): Boolean {
      val left = box(a) ?: return true
      val right = box(b) ?: return true
      if (PsiUtil.resolveClassInClassTypeOnly(left) is PsiTypeParameter || PsiUtil.resolveClassInClassTypeOnly(right) is PsiTypeParameter) return true
      if (PsiUtil.resolveClassInClassTypeOnly(left) == null || PsiUtil.resolveClassInClassTypeOnly(right) == null) return true
      return left.isAssignableFrom(right) || right.isAssignableFrom(left)
    }

    private fun box(type: PsiType): PsiClassType? =
      if (type is PsiPrimitiveType) type.getBoxedType(PsiManager.getInstance(holder.project), holder.file.resolveScope) else type as? PsiClassType

    private fun related(a: PsiClass, b: PsiClass): Boolean = InheritanceUtil.isInheritorOrSelf(a, b, true) || InheritanceUtil.isInheritorOrSelf(b, a, true)

    private fun isPaged(type: PsiType): Boolean = PsiUtil.resolveClassInClassTypeOnly(type)?.qualifiedName in PAGED
    private fun className(type: PsiType): String = PsiUtil.resolveClassInClassTypeOnly(type)?.name.orEmpty()

    private fun isPageable(type: PsiType): Boolean {
      val psiClass = PsiUtil.resolveClassInClassTypeOnly(type) ?: return false
      return psiClass.qualifiedName == PAGEABLE || InheritanceUtil.isInheritor(psiClass, PAGEABLE)
    }

    private fun isNumber(type: PsiType): Boolean = type == PsiTypes.longType() || type == PsiTypes.intType() ||
      PsiUtil.resolveClassInClassTypeOnly(type)?.let { it.qualifiedName in NUMBERS || InheritanceUtil.isInheritor(it, "java.lang.Number") } == true ||
      unwrapAsync(type)?.let { isNumber(it) } == true

    private fun isBoolean(type: PsiType): Boolean = type == PsiTypes.booleanType() ||
      PsiUtil.resolveClassInClassTypeOnly(type)?.qualifiedName == "java.lang.Boolean" || unwrapAsync(type)?.let { isBoolean(it) } == true

    private fun unwrapAsync(type: PsiType): PsiType? {
      val classType = type as? PsiClassType ?: return null
      if (classType.resolve()?.qualifiedName !in ASYNC) return null
      return classType.parameters.firstOrNull()
    }

    /** The element type behind `Optional`, collections, streams, pages and async wrappers. */
    private fun unwrap(type: PsiType): PsiType {
      var current = type
      repeat(3) {
        val classType = current as? PsiClassType ?: return current
        val psiClass = classType.resolve() ?: return current
        val inner = classType.parameters.firstOrNull() ?: return current
        val wrapper = psiClass.qualifiedName in WRAPPERS || InheritanceUtil.isInheritor(psiClass, "java.lang.Iterable") ||
                      InheritanceUtil.isInheritor(psiClass, "java.util.stream.BaseStream")
        if (!wrapper) return current
        current = if (inner is PsiWildcardType) inner.bound ?: return current else inner
      }
      return current
    }
  }

  private companion object {
    const val PAGEABLE = "org.springframework.data.domain.Pageable"
    val PAGED = setOf("org.springframework.data.domain.Page", "org.springframework.data.domain.Slice")
    val NUMBERS = setOf("java.lang.Long", "java.lang.Integer", "java.lang.Short", "java.math.BigInteger", "java.math.BigDecimal")
    val COLLECTION_OPERATORS = setOf("In", "IsIn", "NotIn", "IsNotIn", "Containing", "IsContaining", "Contains", "NotContaining", "IsNotContaining", "NotContains",
                                     "Like", "IsLike", "NotLike", "IsNotLike", "StartingWith", "IsStartingWith", "StartsWith", "EndingWith", "IsEndingWith",
                                     "EndsWith", "Matches", "MatchesRegex", "Regex", "Near", "IsNear", "Within", "IsWithin")
    val ASYNC = setOf("java.util.concurrent.CompletableFuture", "java.util.concurrent.Future", "reactor.core.publisher.Mono",
                      "org.springframework.util.concurrent.ListenableFuture", "java.util.Optional")
    val WRAPPERS = ASYNC + setOf("org.springframework.data.domain.Page", "org.springframework.data.domain.Slice", "org.springframework.data.domain.Window",
                                 "reactor.core.publisher.Flux", "java.util.stream.Stream", "org.springframework.data.util.Streamable")
  }
}
