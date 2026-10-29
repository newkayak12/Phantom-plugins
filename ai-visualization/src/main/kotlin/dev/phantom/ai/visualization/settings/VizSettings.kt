// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.visualization.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import dev.phantom.ai.visualization.theme.Theme
import dev.phantom.ai.visualization.theme.Themes

class VizSettingsState : BaseState() {
  /** Id of the chosen theme; "A" (Warm Wood) until another theme exists and is picked. */
  var themeId by string("A")
}

/** The theme setting. Registered as an application service in plugin.xml. */
@State(name = "PhantomAiVisualization", storages = [Storage(value = "phantom-ai-visualization.xml", roamingType = RoamingType.DISABLED)])
class VizSettings : SimplePersistentStateComponent<VizSettingsState>(VizSettingsState()) {
  var themeId: String
    get() = state.themeId ?: Themes.default.id
    set(v) {
      state.themeId = v
    }

  /** The chosen theme; the default when the stored id is not registered. */
  val theme: Theme get() = Themes.byId(themeId)

  companion object {
    fun getInstance(): VizSettings = ApplicationManager.getApplication().getService(VizSettings::class.java)
  }
}
