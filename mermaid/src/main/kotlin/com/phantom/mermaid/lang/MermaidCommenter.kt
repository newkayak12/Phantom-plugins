// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.
package com.phantom.mermaid.lang

import com.intellij.lang.Commenter

class MermaidCommenter: Commenter {
  override fun getLineCommentPrefix(): String {
    return "%%"
  }

  override fun getBlockCommentPrefix(): String? {
    return null
  }

  override fun getBlockCommentSuffix(): String? {
    return null
  }

  override fun getCommentedBlockCommentPrefix(): String? {
    return null
  }

  override fun getCommentedBlockCommentSuffix(): String? {
    return null
  }
}
