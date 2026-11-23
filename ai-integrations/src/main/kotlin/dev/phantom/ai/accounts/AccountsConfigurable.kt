// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.accounts

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.AiState
import dev.phantom.ai.core.HookConsent
import dev.phantom.ai.core.PhantomAiBundle
import dev.phantom.ai.hooks.HookInstallService
import java.awt.FlowLayout
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Settings | Tools | Phantom AI Integration: per provider a System default row, one row per account, the active one
 * selected; below them the choices that reach outside Phantom (hooks in the agents' config files, skipped permission
 * prompts, database tools), each with what it means.
 */
class AccountsConfigurable(
  private val service: AccountsService = AccountsService.getInstance(),
  private val hooks: HookInstallService = HookInstallService.getInstance(),
  private val stateProvider: () -> AiState = { AiState.getInstance() },
) : Configurable {
  private val chosen = mutableMapOf<AgentKind, String?>()
  private var root: JPanel? = null
  private var hooksBox: JBCheckBox? = null
  private var skipBox: JBCheckBox? = null
  private var dbBox: JBCheckBox? = null

  override fun getDisplayName(): String = PhantomAiBundle.message("accounts.title")

  override fun createComponent(): JComponent {
    AgentKind.entries.forEach { chosen[it] = service.active(it)?.id }
    // Which accounts are still logged in: each CLI is asked, so not on the EDT without progress
    ProgressManager.getInstance().runProcessWithProgressSynchronously(
      { service.refreshLogins() }, PhantomAiBundle.message("accounts.checking"), false, null,
    )
    val panel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    root = panel
    rebuild()
    val files = hooks.systemFiles().joinToString(", ")
    val h = JBCheckBox(PhantomAiBundle.message("settings.hooks", files)).apply { name = "settings.hooks" }
    val k = JBCheckBox(PhantomAiBundle.message("settings.skip.permissions")).apply { name = "settings.skip.permissions" }
    val d = JBCheckBox(PhantomAiBundle.message("settings.db.tools")).apply { name = "settings.db.tools" }
    hooksBox = h; skipBox = k; dbBox = d
    val options = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); border = JBUI.Borders.emptyTop(12) }
    for ((box, help) in listOf(h to "settings.hooks.help", k to "settings.skip.permissions.help", d to "settings.db.tools.help")) {
      options.add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply { add(box) })
      options.add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply { add(help(PhantomAiBundle.message(help)).apply { name = help }) })
    }
    resetOptions()
    return JPanel().apply {
      layout = BoxLayout(this, BoxLayout.Y_AXIS)
      add(panel)
      add(options)
    }
  }

  /** A gray explanation under a check box, wrapped to the page width. */
  private fun help(text: String) = JBLabel("<html><body style='width: 460px'>$text</body></html>").apply {
    foreground = UIUtil.getContextHelpForeground()
    border = JBUI.Borders.emptyLeft(20)
  }

  private fun resetOptions() {
    val s = stateProvider().state
    hooksBox?.isSelected = hooks.consent() == HookConsent.ALLOWED
    skipBox?.isSelected = s.skipPermissions
    dbBox?.isSelected = s.dbTools
  }

  private fun optionsModified(): Boolean {
    val s = stateProvider().state
    return hooksBox?.let { it.isSelected != (hooks.consent() == HookConsent.ALLOWED) } == true ||
      skipBox?.let { it.isSelected != s.skipPermissions } == true ||
      dbBox?.let { it.isSelected != s.dbTools } == true
  }

  private fun rebuild() {
    val panel = root ?: return
    panel.removeAll()
    for (kind in AgentKind.entries) {
      panel.add(JBLabel(kind.displayName).apply { border = JBUI.Borders.empty(8, 0, 2, 0) })
      val group = ButtonGroup()
      fun row(text: String, id: String?, account: AgentAccount?) {
        val signedIn = account == null || service.signedIn(account)
        val shown = if (signedIn) text else PhantomAiBundle.message("accounts.needs.sign.in", text)
        val radio = JBRadioButton(shown, chosen[kind] == id).apply { name = "${kind.name}:${id ?: "default"}"; isEnabled = signedIn }
        radio.addActionListener { chosen[kind] = id }
        group.add(radio)
        val line = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        line.add(radio)
        if (account != null) {
          if (!signedIn) {
            line.add(JButton(PhantomAiBundle.message("accounts.sign.in")).apply {
              name = "signin:${kind.name}:$id"
              addActionListener { signingIn { service.signInAgain(kind, account.id) } }
            })
          }
          line.add(JButton(PhantomAiBundle.message("accounts.remove")).apply {
            name = "remove:${kind.name}:$id"
            addActionListener {
              if (MessageDialogBuilder.yesNo(PhantomAiBundle.message("accounts.remove"), PhantomAiBundle.message("accounts.remove.confirm", account.label)).ask(panel)) {
                service.remove(kind, account.id)
                if (chosen[kind] == id) chosen[kind] = null
                rebuild()
              }
            }
          })
        }
        panel.add(line)
      }
      row(PhantomAiBundle.message("accounts.default"), null, null)
      service.accounts(kind).forEach { row(it.label, it.id, it) }
      panel.add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
        add(JButton(PhantomAiBundle.message("accounts.add")).apply {
          name = "add:${kind.name}"
          addActionListener { signingIn { service.add(kind) } }
        })
      })
    }
    panel.revalidate()
    panel.repaint()
  }

  /** Says why a login failed; a cancel needs no words. */
  private fun signingIn(login: () -> Unit) {
    try {
      login()
    } catch (e: LoginFailed) {
      Messages.showErrorDialog(root, e.message, PhantomAiBundle.message("accounts.login.failed.title"))
    } catch (_: ProcessCanceledException) {
    }
    rebuild()
  }

  override fun isModified(): Boolean = AgentKind.entries.any { chosen[it] != service.active(it)?.id } || optionsModified()

  override fun apply() {
    AgentKind.entries.forEach { service.setActive(it, chosen[it]) }
    val s = stateProvider().state
    skipBox?.let { s.skipPermissions = it.isSelected }
    dbBox?.let { s.dbTools = it.isSelected }
    // turning the tools off also forgets which data sources were allowed: turned on again, each is asked for again
    if (!s.dbTools) s.dbAllowedSources = mutableListOf()
    hooksBox?.let { box ->
      // Unticking an earlier allow takes the hooks out; an unanswered prompt stays unanswered until the box is ticked.
      val allowed = hooks.consent() == HookConsent.ALLOWED
      if (box.isSelected && !allowed) hooks.setConsent(HookConsent.ALLOWED)
      if (!box.isSelected && allowed) hooks.setConsent(HookConsent.DECLINED)
    }
  }

  override fun reset() {
    AgentKind.entries.forEach { chosen[it] = service.active(it)?.id }
    rebuild()
    resetOptions()
  }

  override fun disposeUIResources() {
    root = null
    hooksBox = null; skipBox = null; dbBox = null
  }
}
