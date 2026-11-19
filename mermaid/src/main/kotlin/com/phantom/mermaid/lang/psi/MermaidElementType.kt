// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.phantom.mermaid.lang.psi

import com.phantom.mermaid.lang.MermaidLanguage
import com.intellij.psi.tree.IElementType

class MermaidElementType(name: String): IElementType(name, MermaidLanguage)
