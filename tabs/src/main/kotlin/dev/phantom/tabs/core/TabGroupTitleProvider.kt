// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.tabs.core

import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.fileEditor.impl.EditorTabPresentationUtil
import com.intellij.openapi.fileEditor.impl.EditorTabTitleProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * The first tab of a group shows the group's name before its own title; a collapsed group's first tab shows only the
 * group's name and how many tabs it holds. Other tabs keep the title other providers give them.
 *
 * A provider is not told which pane a tab is in, so it only answers for files that look the same in every pane they are
 * open in; the panes of a file grouped in one pane only set its title themselves ([TabGroupPaneController]).
 */
class TabGroupTitleProvider : EditorTabTitleProvider {
  override fun getEditorTabTitle(project: Project, file: VirtualFile): String? {
    if (computingBase.get() || project.isDisposed) return null
    val title = project.serviceIfCreated<TabGroupsService>()?.groupTitleOf(file) ?: return null
    return title.text { baseTitle(project, file) }
  }

  internal companion object {
    /** Set while asking the other providers for the title this one adds the group name to. */
    private val computingBase: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }

    /** The title [file]'s tab has from every provider but this one. */
    fun baseTitle(project: Project, file: VirtualFile): String {
      if (computingBase.get()) return EditorTabPresentationUtil.getEditorTabTitle(project, file)
      computingBase.set(true)
      try {
        return EditorTabPresentationUtil.getEditorTabTitle(project, file)
      }
      finally {
        computingBase.set(false)
      }
    }
  }
}

/** What a group's first tab shows, [base] being its own title; null when it shows only that (an unnamed, expanded group). */
internal fun GroupTitle.text(base: () -> String): String? {
  val name = name.ifEmpty { if (collapsed) PhantomTabsBundle.message("group.unnamed") else return null }
  if (collapsed) return PhantomTabsBundle.message("tab.title.collapsed", name, size)
  return PhantomTabsBundle.message("tab.title.expanded", name, base())
}
