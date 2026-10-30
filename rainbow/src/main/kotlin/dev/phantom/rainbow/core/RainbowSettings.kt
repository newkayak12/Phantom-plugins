// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.rainbow.core

import com.intellij.DynamicBundle
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.PropertyKey

class RainbowState : BaseState() {
  var brackets by property(true)
  var indent by property(true)
}

@Service
@State(name = "PhantomRainbow", storages = [Storage("phantom-rainbow.xml")])
class RainbowSettings : SimplePersistentStateComponent<RainbowState>(RainbowState()) {
  val brackets get() = state.brackets
  val indent get() = state.indent

  companion object {
    fun getInstance(): RainbowSettings = ApplicationManager.getApplication().getService(RainbowSettings::class.java)
  }
}

/** Editor | Rainbow: turn bracket or indent colors off. */
class RainbowConfigurable : BoundConfigurable(PhantomRainbowBundle.message("settings.title")) {
  override fun createPanel(): DialogPanel {
    val state = RainbowSettings.getInstance().state
    return panel {
      row { checkBox(PhantomRainbowBundle.message("settings.brackets")).bindSelected(state::brackets) }
      row { checkBox(PhantomRainbowBundle.message("settings.indent")).bindSelected(state::indent) }
      row { comment(PhantomRainbowBundle.message("settings.colors")) }
    }
  }

  override fun apply() {
    super.apply()
    for (project in ProjectManager.getInstance().openProjects) DaemonCodeAnalyzer.getInstance(project).restart("Phantom Rainbow settings changed")
  }
}

@NonNls
private const val BUNDLE = "messages.PhantomRainbowBundle"

object PhantomRainbowBundle : DynamicBundle(BUNDLE) {
  @Nls
  fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String = getMessage(key, *params)
}
