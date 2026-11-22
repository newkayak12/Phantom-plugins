// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.
package com.phantom.mermaid.lang.formatter.settings

import com.intellij.application.options.CodeStyleAbstractConfigurable
import com.intellij.application.options.CodeStyleAbstractPanel
import com.phantom.mermaid.MermaidBundle
import com.intellij.psi.codeStyle.CodeStyleSettings

internal class MermaidCodeStyleConfigurable(settings: CodeStyleSettings, originalSettings: CodeStyleSettings)
  : CodeStyleAbstractConfigurable(settings, originalSettings, MermaidBundle.message("mermaid.settings.name")) {

  override fun createPanel(settings: CodeStyleSettings): CodeStyleAbstractPanel {
    return MermaidCodeStyleSettingsPanel(currentSettings, settings)
  }
}
