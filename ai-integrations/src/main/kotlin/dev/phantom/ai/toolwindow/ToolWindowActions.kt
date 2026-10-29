// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowType
import dev.phantom.ai.accounts.AccountsConfigurable
import dev.phantom.ai.core.PhantomAiBundle

/** Hides or shows the tree column of the panel. The id is fixed, but the action is not registered: it belongs to one panel. */
class ToggleTreeAction(private val panel: AgentToolWindowPanel) : AnAction(), DumbAware {
  override fun getActionUpdateThread() = ActionUpdateThread.EDT

  override fun update(e: AnActionEvent) {
    val hidden = panel.treeHidden
    e.presentation.text = PhantomAiBundle.message(if (hidden) "toolwindow.show.tree" else "toolwindow.hide.tree")
    e.presentation.icon = AllIcons.Actions.ToggleVisibility
  }

  override fun actionPerformed(e: AnActionEvent) = panel.toggleTree()

  companion object {
    const val ID = "dev.phantom.ai.ToggleTree"
  }
}

/** Floats a docked tool window and docks a floating or windowed one. */
class FloatDockAction(private val toolWindow: ToolWindow) : AnAction(), DumbAware {
  override fun getActionUpdateThread() = ActionUpdateThread.EDT

  private fun floating() = toolWindow.type == ToolWindowType.FLOATING || toolWindow.type == ToolWindowType.WINDOWED

  override fun update(e: AnActionEvent) {
    e.presentation.text = PhantomAiBundle.message(if (floating()) "toolwindow.dock" else "toolwindow.float")
    e.presentation.icon = if (floating()) AllIcons.Actions.MoveToButton else AllIcons.Actions.MoveToWindow
  }

  override fun actionPerformed(e: AnActionEvent) {
    toolWindow.setType(if (floating()) ToolWindowType.DOCKED else ToolWindowType.FLOATING, null)
  }
}

/** Reads the worktrees again (one added or removed outside Phantom); sessions and names refresh on their own. */
class RefreshTreeAction(private val panel: AgentToolWindowPanel) : AnAction(), DumbAware {
  override fun getActionUpdateThread() = ActionUpdateThread.EDT

  override fun update(e: AnActionEvent) {
    e.presentation.text = PhantomAiBundle.message("sidebar.refresh")
    e.presentation.icon = AllIcons.Actions.Refresh
  }

  override fun actionPerformed(e: AnActionEvent) {
    dev.phantom.ai.sidebar.SharedWorktrees.invalidateAll()
    panel.tree.refresh()
  }
}

/** Opens the settings page of the plugin (accounts). */
class SettingsAction(private val project: Project) : AnAction(), DumbAware {
  override fun getActionUpdateThread() = ActionUpdateThread.EDT

  override fun update(e: AnActionEvent) {
    e.presentation.text = PhantomAiBundle.message("toolwindow.settings")
    e.presentation.icon = AllIcons.General.GearPlain
  }

  override fun actionPerformed(e: AnActionEvent) {
    ShowSettingsUtil.getInstance().showSettingsDialog(project, AccountsConfigurable::class.java)
  }
}

object ToolWindowActions {
  /** Title actions in the order: Refresh, tree toggle, Float/Dock, Settings. */
  fun install(toolWindow: ToolWindow, project: Project, panel: AgentToolWindowPanel) {
    toolWindow.setTitleActions(listOf(RefreshTreeAction(panel), ToggleTreeAction(panel), FloatDockAction(toolWindow), SettingsAction(project)))
  }
}
