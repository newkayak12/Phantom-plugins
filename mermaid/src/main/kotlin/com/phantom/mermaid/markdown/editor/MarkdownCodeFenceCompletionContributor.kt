// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.
package com.phantom.mermaid.markdown.editor

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.phantom.mermaid.editor.MermaidLiveTemplateCompletionProvider
import com.intellij.patterns.PlatformPatterns.not
import com.intellij.patterns.PlatformPatterns.psiElement
import com.intellij.util.ProcessingContext
import org.intellij.plugins.markdown.lang.MarkdownTokenTypes

internal class MarkdownCodeFenceCompletionContributor : CompletionContributor() {
  init {
    extend(
      CompletionType.BASIC,
      psiElement().afterLeaf(
        psiElement(MarkdownTokenTypes.COLON).afterLeaf(
          psiElement(MarkdownTokenTypes.COLON).afterLeaf(
            psiElement(MarkdownTokenTypes.COLON).afterLeaf(not(psiElement(MarkdownTokenTypes.COLON)))
          )
        )
      ),
      MarkdownCodeFenceLiveTemplateCompletionProvider()
    )
  }
}

class MarkdownCodeFenceLiveTemplateCompletionProvider : MermaidLiveTemplateCompletionProvider() {
  override fun addCompletions(
    parameters: CompletionParameters,
    context: ProcessingContext,
    result: CompletionResultSet
  ) {
    val project = parameters.originalFile.project
    result.addElement(createKeywordLookupElement(project, keyword = "codeFence", predefinedNameVar = "mermaid"))
  }
}

