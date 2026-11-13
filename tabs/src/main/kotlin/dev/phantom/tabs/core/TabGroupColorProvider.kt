// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.tabs.core

import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.fileEditor.impl.EditorTabColorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.awt.Color

/** Group members get a faint tab background in the group's color. */
class TabGroupColorProvider : EditorTabColorProvider, DumbAware {
  override fun getEditorTabColor(project: Project, file: VirtualFile): Color? =
    // no service yet: no groups yet, and asking for a tab color does not make one
    project.serviceIfCreated<TabGroupsService>()?.groupColorOf(file)?.soft
}
