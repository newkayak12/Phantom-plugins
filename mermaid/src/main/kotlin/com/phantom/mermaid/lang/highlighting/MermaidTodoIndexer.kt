// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.
package com.phantom.mermaid.lang.highlighting

import com.intellij.lexer.Lexer
import com.phantom.mermaid.lang.lexer.MermaidLexer
import com.phantom.mermaid.lang.lexer.MermaidTokens
import com.intellij.psi.impl.cache.impl.BaseFilterLexer
import com.intellij.psi.impl.cache.impl.OccurrenceConsumer
import com.intellij.psi.impl.cache.impl.todo.LexerBasedTodoIndexer
import com.intellij.psi.search.UsageSearchContext

class MermaidTodoIndexer : LexerBasedTodoIndexer() {
  override fun createLexer(consumer: OccurrenceConsumer): Lexer {
    return object : BaseFilterLexer(MermaidLexer(), consumer) {
      override fun advance() {
        if (myDelegate.tokenType == MermaidTokens.LINE_COMMENT) {
          scanWordsInToken(UsageSearchContext.IN_COMMENTS.toInt(), false, false)
          advanceTodoItemCountsInToken()
        }
        myDelegate.advance()
      }
    }
  }
}
