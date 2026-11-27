// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.visualization.settings

import com.intellij.openapi.options.Configurable
import dev.phantom.ai.visualization.AiVisualizationBundle
import dev.phantom.ai.visualization.theme.Theme
import dev.phantom.ai.visualization.theme.Themes
import java.awt.FlowLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/** Tools | Phantom AI Visualization: a combo box with the registered themes. */
class VizConfigurable : Configurable {
  private var combo: JComboBox<Theme>? = null

  override fun getDisplayName(): String = AiVisualizationBundle.message("settings.display.name")

  override fun createComponent(): JComponent {
    val box = JComboBox(DefaultComboBoxModel(Themes.all.toTypedArray()))
    box.renderer = javax.swing.ListCellRenderer { list, value, index, sel, focus ->
      javax.swing.DefaultListCellRenderer().getListCellRendererComponent(list, value?.let { "${it.id}  ${it.displayName}" }, index, sel, focus)
    }
    box.selectedItem = VizSettings.getInstance().theme
    combo = box
    return JPanel(FlowLayout(FlowLayout.LEFT)).apply {
      add(JLabel(AiVisualizationBundle.message("settings.theme")))
      add(box)
    }
  }

  override fun isModified(): Boolean = (combo?.selectedItem as? Theme)?.id.let { it != null && it != VizSettings.getInstance().themeId }

  override fun apply() {
    (combo?.selectedItem as? Theme)?.let { VizSettings.getInstance().themeId = it.id }
  }

  override fun reset() {
    combo?.selectedItem = VizSettings.getInstance().theme
  }

  override fun disposeUIResources() {
    combo = null
  }
}
