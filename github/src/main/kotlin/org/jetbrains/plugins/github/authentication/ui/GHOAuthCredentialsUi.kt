// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.plugins.github.authentication.ui

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.Panel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.NamedColorUtil
import org.jetbrains.plugins.github.api.GithubApiRequestExecutor
import org.jetbrains.plugins.github.api.GithubServerPath
import org.jetbrains.plugins.github.authentication.GHDeviceFlow
import org.jetbrains.plugins.github.exceptions.GithubAuthenticationException
import org.jetbrains.plugins.github.i18n.GithubBundle.message
import org.jetbrains.plugins.github.ui.util.Validator
import java.awt.datatransfer.StringSelection
import javax.swing.JComponent

/**
 * Browser login without JetBrains' account server: reuses the GitHub CLI login when there is one, otherwise runs
 * the device flow (shows a code, opens the GitHub page where the user enters it, and waits for approval).
 */
internal class GHOAuthCredentialsUi(
  val factory: GithubApiRequestExecutor.Factory,
  private val isAccountUnique: UniqueLoginPredicate
) : GHCredentialsUi() {
  private val status = JBLabel(message("label.login.progress")).apply {
    icon = AnimatedIcon.Default()
    foreground = NamedColorUtil.getInactiveTextColor()
  }
  private val code = JBLabel("").apply { font = JBFont.h1().asBold() }

  override fun getPreferredFocusableComponent(): JComponent? = null

  override fun getValidator(): Validator = { null }

  override suspend fun login(server: GithubServerPath): Pair<String, String> {
    val token = acquireToken(server)
    val executor = factory.create(server, token)
    val login = GHTokenCredentialsUi.acquireLogin(server, executor, isAccountUnique, null)
    return login to token
  }

  override fun handleAcquireError(error: Throwable): ValidationInfo = GHTokenCredentialsUi.handleError(error)

  override fun setBusy(busy: Boolean) = Unit

  override fun Panel.centerPanel() {
    row { cell(code) }
    row { cell(status) }
  }

  private suspend fun acquireToken(server: GithubServerPath): String {
    status.text = message("phantom.login.status.looking.for.cli")
    GHDeviceFlow.ghCliToken(server)?.let {
      status.text = message("phantom.login.status.using.cli")
      return it
    }
    val clientId = GHDeviceFlow.clientId
                   ?: throw GithubAuthenticationException(message("phantom.login.browser.not.set.up"))
    val deviceCode = GHDeviceFlow.requestCode(server, clientId)
    code.text = deviceCode.userCode
    CopyPasteManager.getInstance().setContents(StringSelection(deviceCode.userCode))
    status.text = message("phantom.login.status.enter.code", deviceCode.verificationUri)
    BrowserUtil.browse(deviceCode.verificationUri)
    return GHDeviceFlow.awaitToken(server, clientId, deviceCode)
  }
}
