// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.accounts

import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.util.ui.JBUI
import dev.phantom.ai.core.PhantomAiBundle
import dev.phantom.ai.terminal.LocalTerminalStarter
import java.awt.Dimension
import javax.swing.Action
import javax.swing.JComponent

/**
 * The CLI's login in a terminal of its own, above the Settings dialog. A terminal starts its process only once it is on
 * screen, so a tab in a tool window behind that modal dialog never ran the login.
 */
internal class LoginDialog(
  private val command: String,
  private val env: Map<String, String>,
  title: String,
) : DialogWrapper(null, null, true, IdeModalityType.IDE) {
  private var focus: JComponent? = null

  init {
    setTitle(title)
    setOKButtonText(PhantomAiBundle.message("accounts.login.close"))
    init()
  }

  override fun createActions(): Array<Action> = arrayOf(okAction)

  override fun createCenterPanel(): JComponent {
    val manager = ProjectManager.getInstance()
    val project = manager.openProjects.firstOrNull() ?: manager.defaultProject
    val started = LocalTerminalStarter(project).startPlain(disposable, System.getProperty("user.home"), env, command)
    focus = started.preferredFocus
    return started.component.apply { preferredSize = Dimension(JBUI.scale(760), JBUI.scale(420)) }
  }

  override fun getPreferredFocusedComponent(): JComponent? = focus
}
