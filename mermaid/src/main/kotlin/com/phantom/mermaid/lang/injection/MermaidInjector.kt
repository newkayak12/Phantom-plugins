// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.phantom.mermaid.lang.injection

import com.intellij.json.json5.Json5Language
import com.intellij.lang.injection.general.Injection
import com.intellij.lang.injection.general.LanguageInjectionContributor
import com.intellij.lang.injection.general.SimpleInjection
import com.phantom.mermaid.lang.psi.MermaidDirectiveValue
import com.phantom.mermaid.lang.psi.MermaidFrontmatterContent
import com.phantom.mermaid.lang.psi.MermaidMarkdownValue
import com.intellij.psi.PsiElement
import org.intellij.plugins.markdown.lang.MarkdownLanguage
import org.jetbrains.yaml.YAMLLanguage

class MermaidInjector : LanguageInjectionContributor {
  override fun getInjection(context: PsiElement): Injection? {
    return when (context) {
      is MermaidMarkdownValue -> SimpleInjection(MarkdownLanguage.INSTANCE, "", "", null)
      is MermaidDirectiveValue -> SimpleInjection(Json5Language.INSTANCE, "", "", null)
      is MermaidFrontmatterContent -> SimpleInjection(YAMLLanguage.INSTANCE, "", "", null)
      else -> null
    }
  }
}
