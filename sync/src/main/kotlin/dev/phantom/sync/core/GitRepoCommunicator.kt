// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.sync.core

import com.intellij.openapi.Disposable
import com.intellij.settingsSync.core.AbstractServerCommunicator
import java.io.IOException
import java.io.InputStream

/** Backup and Sync over a git repository: the snapshot zip is a file on the repository's branch. */
class GitRepoCommunicator(private val repository: GitSettingsRepository) : AbstractServerCommunicator(), Disposable {
  override val userId: String
    get() = repository.url

  override fun handleRemoteError(e: Throwable): String =
    PhantomSyncBundle.message("error.remote", repository.url, e.message ?: e.javaClass.simpleName)

  override fun readFileInternal(filePath: String): Pair<InputStream?, String?> {
    val (content, version) = repository.read(filePath)
    return content?.inputStream() to version
  }

  override fun writeFileInternal(filePath: String, versionId: String?, content: InputStream): String =
    repository.write(filePath, versionId, content.readAllBytes())

  @Throws(IOException::class)
  override fun getLatestVersion(filePath: String): String? = repository.latestVersion(filePath)

  override fun deleteFileInternal(filePath: String) {
    repository.delete(filePath)
  }

  override fun dispose() {
    // nothing to release
  }
}
