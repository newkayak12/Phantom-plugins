// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.tabs.core

import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.fileEditor.impl.EditorTabPresentationUtil
import com.intellij.openapi.fileEditor.impl.EditorTabTitleProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * The first tab of a group shows the group's name before its own title; a collapsed group's first tab shows only the
 * group's name and how many tabs it holds. Other tabs keep the title other providers give them.
 */
class TabGroupTitleProvider : EditorTabTitleProvider {
  override fun getEditorTabTitle(project: Project, file: VirtualFile): String? {
    if (computingBase.get() || project.isDisposed) return null
    val title = project.serviceIfCreated<TabGroupsService>()?.groupTitleOf(file) ?: return null
    val name = title.name.ifEmpty { if (title.collapsed) PhantomTabsBundle.message("group.unnamed") else return null }
    if (title.collapsed) return PhantomTabsBundle.message("tab.title.collapsed", name, title.size)
    computingBase.set(true)
    val base = try {
      EditorTabPresentationUtil.getEditorTabTitle(project, file)
    }
    finally {
      computingBase.set(false)
    }
    return PhantomTabsBundle.message("tab.title.expanded", name, base)
  }

  private companion object {
    /** Set while asking the other providers for the title this one adds the group name to. */
    val computingBase: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }
  }
}
