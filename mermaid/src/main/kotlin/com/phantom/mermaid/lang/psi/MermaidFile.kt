// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.
package com.phantom.mermaid.lang.psi

import com.intellij.extapi.psi.PsiFileBase
import com.phantom.mermaid.lang.MermaidFileType
import com.phantom.mermaid.lang.MermaidLanguage
import com.intellij.openapi.fileTypes.FileType
import com.intellij.psi.FileViewProvider

class MermaidFile(viewProvider: FileViewProvider): PsiFileBase(viewProvider, MermaidLanguage), MermaidPsiElement {
  override fun getFileType(): FileType {
    return MermaidFileType
  }
}
