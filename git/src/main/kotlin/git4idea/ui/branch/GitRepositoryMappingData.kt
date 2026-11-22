// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.
package git4idea.ui.branch

import git4idea.repo.GitRemote
import git4idea.repo.GitRepository

interface GitRepositoryMappingData {
  val gitRemote: GitRemote
  val gitRepository: GitRepository

  val repositoryPath: String
}