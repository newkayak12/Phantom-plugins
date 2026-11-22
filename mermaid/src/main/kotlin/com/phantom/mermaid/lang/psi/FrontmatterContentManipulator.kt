// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.
package com.phantom.mermaid.lang.psi

import com.intellij.openapi.util.TextRange
import com.intellij.psi.AbstractElementManipulator

class FrontmatterContentManipulator : AbstractElementManipulator<MermaidFrontmatterContent>() {
  override fun handleContentChange(
    element: MermaidFrontmatterContent,
    range: TextRange,
    newContent: String?
  ): MermaidFrontmatterContent {
    val oldText = element.text
    val newText = oldText.substring(0, range.startOffset) + newContent + oldText.substring(range.endOffset)
    val newElement = checkNotNull(MermaidElementFactory.createFrontmatterContent(element.project, newText))
    return element.replace(newElement) as MermaidFrontmatterContent
  }
}
