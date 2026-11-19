// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.workspaces.core

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.options.advanced.AdvancedSettings
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ex.ToolWindowManagerEx
import com.intellij.openapi.wm.impl.DesktopLayout
import com.intellij.openapi.wm.impl.WindowInfoImpl
import com.intellij.util.concurrency.annotations.RequiresEdt

/**
 * The project switched to takes the tool window layout of the one that was in front: which side and order each stripe
 * button has, which tool windows are open, their sizes and docked or floating. Each project keeps its layout in its own
 * workspace.xml, so without this, every tab showed its tool windows somewhere else.
 */
object ToolWindowLayoutSync {
  const val SETTING_ID: String = "phantom.workspaces.sync.tool.windows"

  /**
   * [target] with each tool window [source] also has taken from [source]. Tool windows only [target] has keep their own
   * state: applied as is, the platform hides the stripe button of a tool window the layout does not mention.
   */
  fun <T> merge(target: Map<String, T>, source: Map<String, T>): Map<String, T> {
    val merged = LinkedHashMap(target)
    for ((id, info) in source) merged[id] = info
    return merged
  }

  @RequiresEdt
  fun copy(from: Project, to: Project) {
    if (from === to || from.isDisposed || to.isDisposed || !AdvancedSettings.getBoolean(SETTING_ID)) return
    try {
      val source = ToolWindowManagerEx.getInstanceEx(from).getLayout()
      val manager = ToolWindowManagerEx.getInstanceEx(to)
      val infos = merge(manager.getLayout().getInfos(), source.getInfos())
      val copies = infos.entries.associateTo(HashMap<String, WindowInfoImpl>(infos.size)) { (id, info) -> id to info.copy() }
      manager.setLayout(DesktopLayout(copies, source.unifiedWeights.copy()))
    }
    catch (e: Exception) {
      // a platform change in the internal layout classes must not break switching projects
      logger<ToolWindowLayoutSync>().warn("Tool window layout not copied from ${from.name} to ${to.name}", e)
    }
  }
}
