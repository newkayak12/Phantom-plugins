// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.sync.core

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.util.text.StringUtil
import java.nio.file.Path

/**
 * The settings repositories the user connected. Kept out of the synced settings ([RoamingType.DISABLED]): each
 * machine chooses its repository itself.
 */
@Service(Service.Level.APP)
@State(name = "PhantomSettingsRepositories", storages = [Storage(value = "phantomSettingsRepositories.xml", roamingType = RoamingType.DISABLED)])
class GitRepoSyncAccounts : SimplePersistentStateComponent<GitRepoSyncAccounts.State>(State()) {
  class State : BaseState() {
    var urls by list<String>()
  }

  val urls: List<String>
    get() = state.urls.toList()

  fun add(url: String) {
    if (url !in state.urls) {
      state.urls.add(url)
      state.intIncrementModificationCount()
    }
  }

  fun remove(url: String) {
    if (state.urls.remove(url)) state.intIncrementModificationCount()
    GitSettingsRepository.deleteQuietly(workDir(url))
  }

  companion object {
    fun getInstance(): GitRepoSyncAccounts = service()

    /** Where Phantom keeps its working copy of [url]; in the system folder, since it can be cloned again. */
    fun workDir(url: String): Path =
      Path.of(PathManager.getSystemPath(), "phantom-sync", StringUtil.sanitizeJavaIdentifier(repositoryName(url)) + "-" + Integer.toHexString(url.hashCode()))

    /** `git@github.com:me/ide-settings.git` → `me/ide-settings` */
    fun repositoryName(url: String): String {
      val path = url.trim().trimEnd('/').removeSuffix(".git").substringAfter("://").substringAfter(':', "")
        .ifEmpty { url.trim().trimEnd('/').removeSuffix(".git").substringAfter("://") }
      val parts = path.split('/').filter { it.isNotEmpty() }
      return parts.takeLast(2).joinToString("/").ifEmpty { url }
    }
  }
}
