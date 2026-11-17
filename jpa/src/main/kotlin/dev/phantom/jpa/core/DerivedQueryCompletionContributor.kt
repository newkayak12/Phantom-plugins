// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.jpa.core

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.parentsOfType
import org.jetbrains.uast.UClass
import org.jetbrains.uast.toUElementOfType

/**
 * Completes derived query method names while they are being declared in a repository interface:
 * `findBy` → `findByRestaurantId` → `findByRestaurantIdAnd…`, `…In`, `…OrderBy…Desc`.
 * Items are whole method names, so camel-hump matching works on what was typed.
 */
class DerivedQueryCompletionContributor : CompletionContributor() {
  override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
    if (parameters.completionType != CompletionType.BASIC) return
    // The original file, not the completion copy: Kotlin parses `fun findBy<dummy>` there as a receiver type reference.
    val position = parameters.originalPosition ?: parameters.position
    if (!isDeclarationName(position) && !isDeclarationName(parameters.position)) return
    val typed = result.prefixMatcher.prefix
    if (typed.isEmpty() || !typed[0].isLowerCase()) return
    val repository = repositoryOf(position) ?: return
    val module = ModuleUtilCore.findModuleForPsiElement(parameters.originalFile) ?: return
    if (!JpaGate.isJpaModule(module)) return
    val entity = DerivedQuery.entityOf(repository) ?: return

    val names = suggestions(typed, entity)
    if (names.isEmpty()) return
    val entityName = entity.name.orEmpty()
    for ((i, name) in names.withIndex()) {
      val element = LookupElementBuilder.create(name).withTypeText(entityName, true)
      result.addElement(PrioritizedLookupElement.withPriority(element, (names.size - i).toDouble()))
    }
  }

  /** Method names to offer for what has been typed so far. */
  fun suggestions(typed: String, entity: PsiClass): List<String> {
    val prefixMatch = DerivedQuery.PREFIX.find(typed) ?: return DerivedQuery.SUBJECTS.filter { it.startsWith(typed) && it != typed }
    val head = typed.substring(0, prefixMatch.range.last + 1)
    val predicate = typed.substring(head.length)
    val paths = DerivedQuery.completionPaths(entity)

    val orderIndex = predicate.indexOf("OrderBy")
    if (orderIndex >= 0) {
      val before = head + predicate.substring(0, orderIndex + "OrderBy".length)
      val rest = predicate.substring(orderIndex + "OrderBy".length)
      val done = Regex("^(.*(?:Asc|Desc))").find(rest)?.value.orEmpty()
      val current = rest.substring(done.length)
      val base = before + done
      val result = ArrayList<String>()
      for (path in paths) {
        if (current.isEmpty() || path.startsWith(current) || current.startsWith(path)) {
          result.add("$base${path}Asc")
          result.add("$base${path}Desc")
        }
      }
      return result
    }

    // Everything up to the last And/Or is kept as typed; the last part is being completed.
    val split = Regex("(And|Or)(?=\\p{Lu}|$)").findAll(predicate).lastOrNull()
    val kept = if (split == null) head else head + predicate.substring(0, split.range.last + 1)
    val current = if (split == null) predicate else predicate.substring(split.range.last + 1)
    val result = ArrayList<String>()
    for (path in paths) {
      if (current.isEmpty() || path.startsWith(current) || current.startsWith(path)) {
        result.add(kept + path)
        if (current.length >= path.length && current.startsWith(path)) {
          // the property is complete: continue with an operator, another condition or ordering
          for (operator in DerivedQuery.COMPLETION_OPERATORS) result.add(kept + path + operator)
          for (next in listOf("And", "Or", "OrderBy")) result.add(kept + path + next)
        }
      }
    }
    return result.distinct()
  }

  private fun isDeclarationName(position: PsiElement): Boolean {
    val parent = position.parent ?: return false
    // Java: the identifier of a method (or of a field while the parameter list is not typed yet)
    if (parent is com.intellij.psi.PsiMethod && parent.nameIdentifier == position) return true
    if (parent is com.intellij.psi.PsiField && parent.nameIdentifier == position) return true
    // Kotlin: `fun findBy<caret>` — the name of a function declaration; checked by class name to avoid a Kotlin dependency
    return parent.javaClass.simpleName == "KtNamedFunction"
  }

  private fun repositoryOf(position: PsiElement): PsiClass? {
    val javaClass = PsiTreeUtil.getParentOfType(position, PsiClass::class.java)
    if (javaClass != null) return javaClass.takeIf { it.isInterface }
    return position.parentsOfType<PsiElement>(withSelf = false).firstNotNullOfOrNull { it.toUElementOfType<UClass>() }
      ?.javaPsi?.takeIf { it.isInterface }
  }
}
