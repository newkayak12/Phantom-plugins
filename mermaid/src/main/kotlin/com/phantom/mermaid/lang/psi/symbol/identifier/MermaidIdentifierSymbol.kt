// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.
package com.phantom.mermaid.lang.psi.symbol.identifier

import com.intellij.find.usages.api.SearchTarget
import com.phantom.mermaid.lang.psi.symbol.MermaidSymbol
import com.intellij.model.Pointer

@Suppress("UnstableApiUsage")
interface MermaidIdentifierSymbol: MermaidSymbol, SearchTarget {
  val text: String

  override fun createPointer(): Pointer<out MermaidIdentifierSymbol>
}
