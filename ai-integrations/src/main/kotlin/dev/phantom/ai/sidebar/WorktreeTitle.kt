// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.sidebar

/** The title of a worktree card: the name the user gave, else the pull request title, else the branch. */
object WorktreeTitle {
  fun resolve(userName: String?, prTitle: String?, branch: String?, path: String): String =
    userName.clean() ?: prTitle.clean() ?: branch.clean() ?: path.trimEnd('/').substringAfterLast('/')

  private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}
