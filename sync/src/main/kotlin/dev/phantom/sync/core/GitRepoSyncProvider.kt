// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.sync.core

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidatorEx
import com.intellij.openapi.ui.Messages
import com.intellij.settingsSync.core.SettingsSyncEvents
import com.intellij.settingsSync.core.SettingsSyncLocalSettings
import com.intellij.settingsSync.core.SettingsSyncRemoteCommunicator
import com.intellij.settingsSync.core.auth.SettingsSyncAuthService
import com.intellij.settingsSync.core.communicator.SettingsSyncCommunicatorProvider
import com.intellij.settingsSync.core.communicator.SettingsSyncUserData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Component
import java.io.IOException
import javax.swing.Icon

const val GIT_REPO_PROVIDER_CODE: String = "phantom-git"

/**
 * Backup and Sync to a git repository the user owns, instead of a JetBrains account. "Logging in" means naming the
 * repository; access goes through the user's git credentials (SSH key or credential helper). The URL itself is stored in
 * plain text ([GitRepoSyncAccounts]), so a URL that carries a password or token is refused instead of being stored.
 */
class GitRepoSyncProvider : SettingsSyncCommunicatorProvider {
  override val providerCode: String = GIT_REPO_PROVIDER_CODE

  override val authService: SettingsSyncAuthService = GitRepoAuthService()
  override val supportsMultipleAccounts: Boolean = true

  override fun createCommunicator(userId: String): SettingsSyncRemoteCommunicator =
    GitRepoCommunicator(GitSettingsRepository(userId, GitRepoSyncAccounts.workDir(userId)))
}

class GitRepoAuthService : SettingsSyncAuthService {
  override val providerCode: String = GIT_REPO_PROVIDER_CODE

  override val providerName: String = PhantomSyncBundle.message("provider.name")

  override val icon: Icon = AllIcons.Vcs.Vendors.Github

  override val logoutFunction: (suspend (Component) -> Unit) = {
    val local = SettingsSyncLocalSettings.getInstance()
    if (local.providerCode == GIT_REPO_PROVIDER_CODE) local.userId?.let { GitRepoSyncAccounts.getInstance().remove(it) }
    SettingsSyncEvents.getInstance().fireLoginStateChanged()
  }

  override fun crossSyncSupported(): Boolean = true

  override suspend fun login(parentComponent: Component?): SettingsSyncUserData? {
    while (true) {
      val url = withContext(Dispatchers.EDT) {
        val message = PhantomSyncBundle.message("login.message")
        val title = PhantomSyncBundle.message("login.title")
        if (parentComponent != null) Messages.showInputDialog(parentComponent, message, title, icon, "", UrlValidator)
        else Messages.showInputDialog(null as Project?, message, title, icon, "", UrlValidator)
      }?.trim()?.ifEmpty { null } ?: return null
      val problem = withContext(Dispatchers.IO) {
        try {
          GitSettingsRepository(url, GitRepoSyncAccounts.workDir(url)).checkAccess()
          null
        }
        catch (e: IOException) {
          e.message ?: e.javaClass.simpleName
        }
      }
      if (problem == null) {
        GitRepoSyncAccounts.getInstance().add(url)
        return userData(url)
      }
      withContext(Dispatchers.EDT) {
        val message = PhantomSyncBundle.message("login.failed", GitSettingsRepository.maskCredentials(url), problem)
        if (parentComponent != null) Messages.showErrorDialog(parentComponent, message, PhantomSyncBundle.message("login.title"))
        else Messages.showErrorDialog(message, PhantomSyncBundle.message("login.title"))
      }
    }
  }

  override fun getUserData(userId: String): SettingsSyncUserData? =
    if (userId in GitRepoSyncAccounts.getInstance().urls) userData(userId) else null

  override fun getAvailableUserAccounts(): List<SettingsSyncUserData> = GitRepoSyncAccounts.getInstance().urls.map(::userData)

  private fun userData(url: String) =
    SettingsSyncUserData(id = url, providerCode = GIT_REPO_PROVIDER_CODE, name = GitRepoSyncAccounts.repositoryName(url), email = null,
                       // shown in the UI; the id keeps the real URL, which git needs
                       printableName = GitSettingsRepository.maskCredentials(url))
  private object UrlValidator : InputValidatorEx {
    override fun getErrorText(inputString: String): String? = when {
      inputString.isBlank() || inputString.trim().contains(' ') -> PhantomSyncBundle.message("login.invalid")
      GitSettingsRepository.hasCredentials(inputString.trim()) -> PhantomSyncBundle.message("login.credentials")
      else -> null
    }
  }
}
