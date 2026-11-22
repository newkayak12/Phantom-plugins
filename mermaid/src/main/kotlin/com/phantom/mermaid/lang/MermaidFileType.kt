// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.
package com.phantom.mermaid.lang

import com.phantom.mermaid.icons.MermaidIcons
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.util.NlsSafe
import javax.swing.Icon


object MermaidFileType: LanguageFileType(MermaidLanguage) {
  @NlsSafe
  private val MERMAID_DESCRIPTION = "Mermaid"
  
  override fun getName(): String = MERMAID_DESCRIPTION

  override fun getDescription(): String = MERMAID_DESCRIPTION

  override fun getDefaultExtension(): String = "mermaid"

  override fun getIcon(): Icon = MermaidIcons.MermaidFileType
}
