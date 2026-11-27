// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.localinsights

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.ListTableModel
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JPanel

class LocalInsightsToolWindowFactory : ToolWindowFactory, DumbAware {
  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val panel = LocalInsightsPanel(project)
    val content = toolWindow.contentManager.factory.createContent(panel.component, "", false)
    toolWindow.contentManager.addContent(content)
  }
}

/** Reads the store when opened and on Refresh; it never polls, so a closed tool window costs nothing. */
class LocalInsightsPanel(private val project: Project) {
  val inspectionModel = ListTableModel<InspectionTiming>(
    column<InspectionTiming>("column.inspection") { it.displayName },
    column<InspectionTiming>("column.runs") { it.runs },
    column<InspectionTiming>("column.total") { it.totalMillis },
    column<InspectionTiming>("column.max") { it.maxMillis },
  )
  val indexingModel = ListTableModel<IndexingTiming>(
    column<IndexingTiming>("column.plugin") { it.plugin },
    column<IndexingTiming>("column.files") { it.files },
    column<IndexingTiming>("column.total") { it.totalNanos / NANOS_PER_MILLI },
  )
  private val inspectionTable = TableView(inspectionModel).apply {
    emptyText.text = LocalInsightsBundle.message("empty.inspections")
  }
  private val indexingTable = TableView(indexingModel).apply {
    emptyText.text = LocalInsightsBundle.message("empty.indexing")
  }
  private val tabs = JBTabbedPane()
  private val disableButton = JButton(LocalInsightsBundle.message("action.disable.inspection")).apply { isEnabled = false }
  val component = JPanel(BorderLayout())

  init {
    tabs.addTab(LocalInsightsBundle.message("tab.inspections"), JBScrollPane(inspectionTable))
    tabs.addTab(LocalInsightsBundle.message("tab.indexing"), JBScrollPane(indexingTable))
    val buttons = JPanel(FlowLayout(FlowLayout.LEFT))
    buttons.add(JButton(LocalInsightsBundle.message("action.refresh")).apply { addActionListener { refresh() } })
    buttons.add(
      JButton(LocalInsightsBundle.message("action.clear")).apply {
        addActionListener {
          InsightsStore.getInstance().clear()
          refresh()
        }
      },
    )
    buttons.add(disableButton)
    disableButton.addActionListener {
      inspectionTable.selectedObject?.let { disableInspection(project, it.shortName) }
    }
    inspectionTable.selectionModel.addListSelectionListener { updateDisableButton() }
    tabs.addChangeListener { updateDisableButton() }
    component.add(buttons, BorderLayout.NORTH)
    component.add(tabs, BorderLayout.CENTER)
    refresh()
  }

  private fun updateDisableButton() {
    disableButton.isEnabled = tabs.selectedIndex == 0 && inspectionTable.selectedObject != null
  }

  fun refresh() {
    val store = InsightsStore.getInstance()
    inspectionModel.items = store.slowestInspections()
    indexingModel.items = store.indexingByPlugin()
  }

  private fun <T> column(key: String, value: (T) -> Any): ColumnInfo<T, Any> =
    object : ColumnInfo<T, Any>(LocalInsightsBundle.message(key)) {
      override fun valueOf(item: T): Any = value(item)
    }

  companion object {
    private const val NANOS_PER_MILLI = 1_000_000L
  }
}

/**
 * Turns the inspection off in the project's current profile; InspectionProfileImpl fires the profile change, which
 * restarts highlighting and saves the profile with the project settings. A tool the profile no longer has is left alone.
 */
fun disableInspection(project: Project, shortName: String) {
  val profile = InspectionProjectProfileManager.getInstance(project).currentProfile
  if (profile.getToolsOrNull(shortName, project) == null) return
  profile.setToolEnabled(shortName, false, project)
}
