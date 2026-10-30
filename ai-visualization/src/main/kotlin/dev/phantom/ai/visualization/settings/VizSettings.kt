// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.visualization.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.messages.Topic
import dev.phantom.ai.visualization.theme.Theme
import dev.phantom.ai.visualization.theme.Themes

class VizSettingsState : BaseState() {
  /** Id of the chosen theme; "A" (Warm Wood) until another one is picked. */
  var themeId by string("A")
}

/** Told when the chosen theme changes; open AI Visualization tabs listen and re-render. */
fun interface VizThemeListener {
  fun themeChanged(theme: Theme)
}

/** The theme setting. Registered as an application service in plugin.xml; a change is published on [TOPIC]. */
@State(name = "PhantomAiVisualization", storages = [Storage(value = "phantom-ai-visualization.xml", roamingType = RoamingType.DISABLED)])
class VizSettings : SimplePersistentStateComponent<VizSettingsState>(VizSettingsState()) {
  var themeId: String
    get() = state.themeId ?: Themes.default.id
    set(v) {
      if (v == themeId) return
      state.themeId = v
      ApplicationManager.getApplication().messageBus.syncPublisher(TOPIC).themeChanged(theme)
    }

  /** The chosen theme; the default when the stored id is not registered. */
  val theme: Theme get() = Themes.byId(themeId)

  companion object {
    @JvmField
    val TOPIC: Topic<VizThemeListener> = Topic(VizThemeListener::class.java, Topic.BroadcastDirection.NONE)

    fun getInstance(): VizSettings = ApplicationManager.getApplication().getService(VizSettings::class.java)
  }
}
