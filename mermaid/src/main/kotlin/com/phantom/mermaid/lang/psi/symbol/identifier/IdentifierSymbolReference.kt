// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.
package com.phantom.mermaid.lang.psi.symbol.identifier

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.phantom.mermaid.lang.psi.MermaidNamedPsiElement
import com.phantom.mermaid.lang.psi.symbol.MermaidPsiSymbolReferenceBase
import com.phantom.mermaid.lang.psi.symbol.identifier.UnresolvedIdentifierSymbol.Companion.isDeclaration
import com.intellij.model.Symbol
import com.intellij.model.psi.PsiCompletableReference
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager

@Suppress("UnstableApiUsage")
class IdentifierSymbolReference(
  element: PsiElement,
  rangeInElement: TextRange,
  private val text: String,
) : MermaidPsiSymbolReferenceBase(element, rangeInElement), PsiCompletableReference {
  override fun resolveReference(): Collection<Symbol> {
    val file = element.containingFile
    // phantom-idea: declarations come from a per-file map cached until the file changes; the upstream code walked the
    // whole PSI tree on every resolve, which made highlighting quadratic in the number of identifiers.
    val matchingDeclarations = file.declarationsByText()[text].orEmpty()
    if (matchingDeclarations.isEmpty()) {
      return listOf(element).mapNotNull {
        (it as? MermaidNamedPsiElement)?.let { element ->
          UnresolvedIdentifierSymbol.createPointer(
            element
          ).dereference()
        }
      }
    }
    return matchingDeclarations.mapNotNull { IdentifierSymbol.createPointer(it).dereference() }
  }

  override fun getCompletionVariants(): Collection<LookupElement> {
    val file = element.containingFile
    val labels = file.collectNamedElements()
    // phantom-idea: an identifier used several times is offered once, not once per occurrence.
    return labels.map { it.name }.distinct().map { LookupElementBuilder.create(it) }.toList()
  }

  companion object {
    private fun PsiFile.declarationsByText(): Map<String, List<MermaidNamedPsiElement>> =
      CachedValuesManager.getCachedValue(this) {
        val map = collectNamedElements().filter { it.isDeclaration }.groupBy { it.text }
        CachedValueProvider.Result.create(map, this)
      }

    private fun PsiFile.collectNamedElements(): Sequence<MermaidNamedPsiElement> {
      return SyntaxTraverser.psiTraverser(this)
        .asSequence()
        .filterIsInstance<MermaidNamedPsiElement>()
    }
  }
}
