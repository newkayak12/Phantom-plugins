// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.
package com.phantom.mermaid.lang.highlighting

import com.intellij.lexer.Lexer
import com.phantom.mermaid.lang.lexer.MermaidLexer
import com.phantom.mermaid.lang.lexer.MermaidTokens
import com.phantom.mermaid.lang.psi.MermaidFile
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.search.IndexPatternBuilder
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet

class MermaidIndexPatternBuilder : IndexPatternBuilder {
  override fun getIndexingLexer(file: PsiFile): Lexer? {
    if (file !is MermaidFile) return null

    return MermaidLexer()
  }

  override fun getCommentTokenSet(file: PsiFile): TokenSet? {
    if (file !is MermaidFile) return null

    return TokenSet.create(MermaidTokens.LINE_COMMENT)
  }

  override fun getCommentStartDelta(tokenType: IElementType?) = 2

  override fun getCommentEndDelta(tokenType: IElementType?) = 0
}
