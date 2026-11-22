// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.
package org.jetbrains.plugins.github.authentication

import org.jetbrains.plugins.github.api.GithubServerPath

enum class GHAuthType {
  OAUTH,
  TOKEN
}

enum class GHLoginSource {
  GIT,
  CLONE,
  SHARE,
  PR_TW,
  PR_LIST,
  PR_DETAILS,
  PR_CHANGES,
  PR_TIMELINE,
  SETTINGS,
  GIST,
  SYNC_FORK,
  TRACKER,
  WELCOME_SCREEN,
  UNKNOWN;
}

enum class GHServerOffering {
  GITHUB_COM,

  /**
   * GitHub Enterprise Cloud with data residency
   */
  GHE_CLOUD,

  GHE_SERVER;

  companion object {
    fun of(serverPath: GithubServerPath): GHServerOffering = when {
      serverPath.isGithubDotCom -> GITHUB_COM
      serverPath.isGheDataResidency -> GHE_CLOUD
      else -> GHE_SERVER
    }
  }
}

data class GHLoginData(val loginSource: GHLoginSource,
                       val reLogin: Boolean = false,
                       val authType: GHAuthType = GHAuthType.OAUTH,
                       val serverOffering: GHServerOffering = GHServerOffering.GITHUB_COM
) {
  companion object {
    fun GHLoginData.reLogin(): GHLoginData = copy(reLogin = true)
  }
}


