// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.sidebar

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.PhantomAiBundle
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JComponent

/**
 * What to create: a worktree [name] from [base] (null = the default branch), then [agent] started in it (null = none), and
 * the worktree opened as its own project window when [openAsProject].
 */
data class NewWorktreeRequest(val name: String, val base: String?, val agent: AgentKind?, val openAsProject: Boolean = true)

/** Whether a new worktree opens as its own project window: a worktree is a separate checkout. On by default; remembered. */
object WorktreeOpening {
  private const val KEY = "dev.phantom.ai.worktree.openAsProject"

  var asProject: Boolean
    get() = PropertiesComponent.getInstance().getBoolean(KEY, true)
    set(value) = PropertiesComponent.getInstance().setValue(KEY, value, true)
}

/** Name, base branch and agent of a new worktree, as Orca's new-workspace card. */
class NewWorktreeDialog(project: Project, repoName: String, private val defaultBase: String?) : DialogWrapper(project) {
  private val name = JBTextField()
  private val base = JBTextField(defaultBase.orEmpty())
  private val agentNames = listOf(AgentKind.CLAUDE.displayName, AgentKind.CODEX.displayName, PhantomAiBundle.message("worktree.agent.none"))
  private val agent = JComboBox(DefaultComboBoxModel(agentNames.toTypedArray()))
  private val openAsProject = JBCheckBox(PhantomAiBundle.message("worktree.open.project"), WorktreeOpening.asProject)

  init {
    title = PhantomAiBundle.message("worktree.new.title", repoName)
    init()
  }

  override fun createCenterPanel(): JComponent = panel {
    row(PhantomAiBundle.message("worktree.name")) { cell(name).align(AlignX.FILL).focused() }
    row(PhantomAiBundle.message("worktree.base")) { cell(base).align(AlignX.FILL) }
    row(PhantomAiBundle.message("worktree.agent")) { cell(agent) }
    row { cell(openAsProject) }
  }

  override fun doValidate(): ValidationInfo? =
    if (WorktreeNames.sanitize(name.text).isEmpty()) ValidationInfo(PhantomAiBundle.message("worktree.name.empty"), name) else null

  /** The answer; the open-as-project choice is remembered for the next worktree. */
  fun request(): NewWorktreeRequest {
    WorktreeOpening.asProject = openAsProject.isSelected
    return NewWorktreeRequest(
      name.text.trim(),
      base.text.trim().takeIf { it.isNotEmpty() && it != defaultBase },
      when (agent.selectedIndex) { 0 -> AgentKind.CLAUDE; 1 -> AgentKind.CODEX; else -> null },
      openAsProject.isSelected,
    )
  }
}
