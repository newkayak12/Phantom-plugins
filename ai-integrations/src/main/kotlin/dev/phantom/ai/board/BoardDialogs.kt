// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.board

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.BrowserLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.PhantomAiBundle
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JComponent

/** The board's questions as IDE dialogs. */
class IdeBoardDialogs(private val project: Project) : BoardDialogs {
  override fun prompt(card: BoardCard, followUp: Boolean): String? {
    val key = if (followUp) "board.followup" else "board.prompt"
    return Messages.showMultilineInputDialog(
      project, PhantomAiBundle.message("$key.message", card.title), PhantomAiBundle.message("$key.title"),
      if (followUp) "" else card.prompt.orEmpty(), null, null,
    )?.trim()
  }

  override fun confirmDone(card: BoardCard): DoneChoice? {
    val dialog = DoneDialog(project, card)
    return if (dialog.showAndGet()) DoneChoice(dialog.removeWorktree()) else null
  }

  override fun editCard(repos: List<String>, card: BoardCard?): CardDraft? {
    if (card == null && repos.isEmpty()) return null
    val dialog = CardDialog(project, repos, card)
    return if (dialog.showAndGet()) dialog.draft() else null
  }
}

/** Title, prompt, agent (and, for a new card, its repository) of a board card. */
class CardDialog(project: Project, private val repos: List<String>, private val card: BoardCard?) : DialogWrapper(project) {
  private val repo = JComboBox(DefaultComboBoxModel(repos.toTypedArray()))
  private val name = JBTextField(card?.title.orEmpty())
  private val prompt = JBTextArea(card?.prompt.orEmpty(), 6, 50).apply { lineWrap = true; wrapStyleWord = true }
  private val agent = JComboBox(DefaultComboBoxModel(AgentKind.entries.map { it.displayName }.toTypedArray())).apply {
    selectedIndex = AgentKind.entries.indexOf(card?.agent ?: AgentKind.CLAUDE)
  }

  init {
    title = PhantomAiBundle.message(if (card == null) "board.card.new" else "board.card.edit")
    init()
  }

  override fun createCenterPanel(): JComponent = panel {
    if (card == null && repos.size > 1) row(PhantomAiBundle.message("board.card.repo")) { cell(repo).align(AlignX.FILL) }
    row(PhantomAiBundle.message("board.card.name")) { cell(name).align(AlignX.FILL).focused() }
    row(PhantomAiBundle.message("board.card.prompt")) { cell(JBScrollPane(prompt)).align(AlignX.FILL) }
    row(PhantomAiBundle.message("board.card.agent")) { cell(agent) }
  }

  override fun doValidate(): ValidationInfo? =
    if (name.text.isBlank()) ValidationInfo(PhantomAiBundle.message("board.card.name.empty"), name) else null

  fun draft(): CardDraft = CardDraft(
    card?.repo ?: (repo.selectedItem as? String).orEmpty(),
    name.text.trim(),
    prompt.text.trim().ifEmpty { null },
    AgentKind.entries[agent.selectedIndex.coerceAtLeast(0)],
  )
}

/** Done: says nothing is merged or pushed, links the pull request, and offers to remove the worktree. */
class DoneDialog(project: Project, private val card: BoardCard) : DialogWrapper(project) {
  private val remove = JBCheckBox(PhantomAiBundle.message("board.done.remove")).apply {
    isEnabled = !card.planned && !card.isMain
  }

  init {
    title = PhantomAiBundle.message("board.done.title")
    init()
  }

  override fun createCenterPanel(): JComponent = panel {
    row { label(PhantomAiBundle.message("board.done.message", card.title)) }
    if (card.prUrl != null) row { cell(BrowserLink(PhantomAiBundle.message("board.done.pr", card.prNumber ?: ""), card.prUrl)) }
    if (!card.planned) row { cell(remove) }
  }

  fun removeWorktree(): Boolean = remove.isEnabled && remove.isSelected
}
