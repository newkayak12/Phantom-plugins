// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.visualization.editor

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.testFramework.LightVirtualFile

/** The one "AI Visualization" file of a project; never on disk, never editable. */
class AiOfficeFile : LightVirtualFile("AI Visualization") {
  init {
    isWritable = false
  }

  override fun isWritable(): Boolean = false

  companion object {
    private val FILES = Key.create<AiOfficeFile>("dev.phantom.ai.visualization.file")

    /** The project's single instance, so opening again selects the same tab. */
    fun of(project: Project): AiOfficeFile = project.getUserData(FILES) ?: AiOfficeFile().also { project.putUserData(FILES, it) }
  }
}
