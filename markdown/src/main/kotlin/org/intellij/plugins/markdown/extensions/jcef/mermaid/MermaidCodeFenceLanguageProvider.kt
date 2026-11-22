// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.
package org.intellij.plugins.markdown.extensions.jcef.mermaid

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.ide.plugins.PluginManager
import com.intellij.lang.Language
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.fileTypes.PlainTextLanguage
import org.intellij.plugins.markdown.injection.CodeFenceLanguageProvider

internal class MermaidCodeFenceLanguageProvider: CodeFenceLanguageProvider {
  override fun getLanguageByInfoString(infoString: String): Language? {
    if (isMermaidPluginInstalled()) {
      return null
    }
    return when {
      isMermaidInfoString(infoString) -> obtainMermaidLanguage()
      else -> null
    }
  }

  override fun getCompletionVariantsForInfoString(parameters: CompletionParameters): List<LookupElement> {
    if (isMermaidPluginInstalled()) {
      return emptyList()
    }
    val language = obtainMermaidLanguage()
    val lookupElement = LookupElementBuilder.create(MERMAID)
      .withIcon(language.associatedFileType?.icon)
    return listOf(lookupElement)
  }

  companion object {
    private const val MERMAID = "mermaid"
    private val mermaidPluginId = PluginId.getId("com.intellij.mermaid")

    // A Mermaid plugin brings its own fence language and completion
    internal fun isMermaidPluginInstalled(): Boolean = PluginManager.isPluginInstalled(mermaidPluginId)

    internal fun isMermaidInfoString(infoString: String): Boolean {
      return infoString == MERMAID
    }

    internal fun obtainMermaidLanguage(): Language {
      val existingLanguage = Language.findLanguageByID("Mermaid")
      return existingLanguage ?: PlainTextLanguage.INSTANCE
    }
  }
}
