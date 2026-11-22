// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.
package com.phantom.mermaid.lang.formatter

import com.intellij.formatting.FormattingContext
import com.intellij.formatting.FormattingModel
import com.intellij.formatting.FormattingModelBuilder
import com.intellij.psi.formatter.DocumentBasedFormattingModel

internal class MermaidFormattingModelBuilder : FormattingModelBuilder {
  override fun createModel(formattingContext: FormattingContext): FormattingModel {
    val settings = formattingContext.codeStyleSettings
    val block = MermaidFormattingBlock(formattingContext.node, settings, MermaidSpacingBuilder.get(settings))

    return DocumentBasedFormattingModel(block, settings, formattingContext.containingFile)
  }
}
