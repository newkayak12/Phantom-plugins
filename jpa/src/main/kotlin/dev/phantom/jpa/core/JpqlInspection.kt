// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.jpa.core

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil

/**
 * JPQL mistakes Hibernate rejects when it parses the query, beyond the unknown entity and attribute names the
 * annotator reports: a path that starts from an identification variable the query never declares (`x.name` where
 * only `o` is declared), and a standard function called with the wrong number of arguments. Queries built from Kotlin
 * templates are skipped: part of their text is unknown.
 */
class JpqlInspection : LocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
    val file = holder.file as? JpqlFile ?: return PsiElementVisitor.EMPTY_VISITOR
    if (file.hasPlaceholders) return PsiElementVisitor.EMPTY_VISITOR
    return object : PsiElementVisitor() {
      override fun visitElement(element: PsiElement) {
        when (element) {
          is JpqlPath -> checkPath(element, holder)
          is JpqlFunctionName -> checkArguments(element, holder)
        }
      }
    }
  }

  private fun checkPath(path: JpqlPath, holder: ProblemsHolder) {
    val segments = path.segments()
    if (segments.size < 2) return
    // `com.example.Status.ACTIVE` and `Status.ACTIVE`: a constant, not a path from a variable
    if (segments.any { it.text.firstOrNull()?.isUpperCase() == true }) return
    // `CAST(x AS string)` and select-list result variables
    val previous = PsiTreeUtil.skipWhitespacesAndCommentsBackward(path)
    if (previous?.node?.elementType == JpqlTokens.KEYWORD && previous.text.equals("AS", ignoreCase = true)) return
    val first = segments.first()
    if (first.resolveTarget() != null) return
    holder.registerProblem(first, PhantomJpaBundle.message("jpql.undefined.variable", first.text))
  }

  private fun checkArguments(function: JpqlFunctionName, holder: ProblemsHolder) {
    val range = ARITY[function.text.uppercase()] ?: return
    val count = argumentCount(function) ?: return
    if (count in range) return
    val expected = if (range.first == range.last) "${range.first}" else "${range.first}–${range.last}"
    holder.registerProblem(function, PhantomJpaBundle.message("jpql.function.arguments", function.text, expected, count))
  }

  /** Arguments of the call after [function], or null for the special forms (`SUBSTRING(s FROM 1)`, `TRIM(LEADING ...)`). */
  private fun argumentCount(function: JpqlFunctionName): Int? {
    var e: PsiElement? = next(function)
    if (e?.node?.elementType != JpqlTokens.LPAREN) return null
    var depth = 0
    var commas = 0
    var empty = true
    while (e != null) {
      when (e.node.elementType) {
        JpqlTokens.LPAREN -> depth++
        JpqlTokens.RPAREN -> {
          depth--
          if (depth == 0) return if (empty) 0 else commas + 1
        }
        JpqlTokens.COMMA -> if (depth == 1) commas++
        else -> {
          if (depth == 1 && e.node.elementType == JpqlTokens.KEYWORD && e.text.uppercase() in SPECIAL_FORMS) return null
          // a FROM inside the call makes the parser read an entity name
          if (depth == 1 && e is JpqlEntityRef) return null
          empty = false
        }
      }
      e = next(e)
    }
    return null
  }

  private fun next(e: PsiElement): PsiElement? {
    var n = e.nextSibling
    while (n is PsiWhiteSpace || n is PsiComment) n = n.nextSibling
    return n
  }

  private companion object {
    val ARITY: Map<String, IntRange> = mapOf(
      "LOWER" to 1..1, "UPPER" to 1..1, "LENGTH" to 1..1, "ABS" to 1..1, "SQRT" to 1..1, "SIZE" to 1..1,
      "COUNT" to 1..1, "SUM" to 1..1, "AVG" to 1..1, "MIN" to 1..1, "MAX" to 1..1,
      "MOD" to 2..2, "NULLIF" to 2..2, "SUBSTRING" to 2..3, "LOCATE" to 2..3,
    )
    val SPECIAL_FORMS = setOf("FROM", "FOR", "LEADING", "TRAILING", "BOTH")
  }
}
