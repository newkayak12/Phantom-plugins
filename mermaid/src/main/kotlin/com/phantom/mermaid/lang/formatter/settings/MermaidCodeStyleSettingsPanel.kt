// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.
package com.phantom.mermaid.lang.formatter.settings

import com.intellij.application.options.TabbedLanguageCodeStylePanel
import com.phantom.mermaid.lang.MermaidLanguage
import com.intellij.psi.codeStyle.CodeStyleSettings

internal class MermaidCodeStyleSettingsPanel(
  currentSettings: CodeStyleSettings,
  settings: CodeStyleSettings
): TabbedLanguageCodeStylePanel(MermaidLanguage, currentSettings, settings) {
  override fun initTabs(settings: CodeStyleSettings) {
    addIndentOptionsTab(settings)
    addBlankLinesTab(settings)
    addSpacesTab(settings)
  }
}
