// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.
package git4idea.instant

import com.intellij.openapi.extensions.ExtensionPointName
import org.jetbrains.annotations.ApiStatus.Experimental
import org.jetbrains.annotations.ApiStatus.Internal

/**
 * If authentication is done via bearer token, [git4idea.instant.InstantGitTokenProvider.getAuthToken] could be implemented,
 * otherwise, [git4idea.instant.InstantGitTokenProvider.getAuthHeaders] should provide authentication headers.
 */
@Experimental
@Internal
interface InstantGitTokenProvider {

  companion object {
    val EP_NAME = ExtensionPointName.create<InstantGitTokenProvider>("Git4Idea.instantGitTokenProvider")
  }

  fun getToken(): String? {
    return null
  }

  suspend fun getAuthToken(): String? {
    return null
  }

  suspend fun getAuthHeaders(): Map<String, String>? {
    return (getAuthToken() ?: getToken())?.let { mapOf("Authorization" to "Bearer $it") }
  }
}