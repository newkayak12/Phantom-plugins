// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.jpa.core

import com.intellij.codeInspection.AbstractBaseUastLocalInspectionTool
import com.intellij.codeInspection.LocalInspectionToolSession
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElementVisitor
import com.intellij.uast.UastHintedVisitorAdapter
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.getParentOfType
import org.jetbrains.uast.visitor.AbstractUastNonRecursiveVisitor

/**
 * Spring Data derived query methods whose name Spring would reject at startup: a property the entity does not have,
 * or a parameter count that does not match the operators. Methods with `@Query` (or another store's query annotation), a
 * named query on the entity, a body, or no `find…By` prefix are skipped.
 */
class DerivedQueryInspection : AbstractBaseUastLocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    val module = ModuleUtilCore.findModuleForPsiElement(holder.file) ?: return PsiElementVisitor.EMPTY_VISITOR
    if (!JpaGate.isJpaModule(module)) return PsiElementVisitor.EMPTY_VISITOR
    return UastHintedVisitorAdapter.create(holder.file.language, Visitor(holder), arrayOf(UMethod::class.java), true)
  }

  private class Visitor(private val holder: ProblemsHolder) : AbstractUastNonRecursiveVisitor() {
    override fun visitMethod(node: UMethod): Boolean {
      val method = node.javaPsi
      if (node.uastBody != null || !DerivedQuery.isDerivedQueryMethod(method)) return true
      val repository = node.getParentOfType<UClass>()?.javaPsi ?: return true
      if (!repository.isInterface) return true
      val entity = DerivedQuery.entityOf(repository) ?: return true
      if (DerivedQuery.hasNamedQuery(entity, method)) return true
      val identifier = node.uastAnchor?.sourcePsi ?: return true
      if (identifier.text != method.name) return true
      val parsed = DerivedQuery.parse(method.name) ?: return true

      var expected = 0
      for (part in parsed.parts + parsed.orderBy) {
        if (part in parsed.parts) expected += part.arguments
        if (DerivedQuery.resolvePath(entity, part.propertySource) != null) continue
        val range = TextRange(part.offset, part.offset + part.propertySource.length)
        holder.registerProblem(identifier, PhantomJpaBundle.message("derived.unknown.property", DerivedQuery.decapitalize(part.propertySource), entity.name ?: ""),
                               ProblemHighlightType.GENERIC_ERROR_OR_WARNING, range)
        return true
      }
      val actual = method.parameterList.parameters.count { DerivedQuery.countsAsArgument(it) }
      // Spring fails at startup when arguments are missing; extra ones are only reported by some stores
      if (actual < expected) {
        holder.registerProblem(identifier, PhantomJpaBundle.message("derived.parameter.count", expected, actual))
      }
      return true
    }
  }
}
