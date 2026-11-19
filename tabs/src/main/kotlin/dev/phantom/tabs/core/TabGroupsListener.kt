// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.tabs.core

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/** Keeps every pane's groups in line when tabs open, get selected or get pinned. */
internal class TabGroupsListener : FileEditorManagerListener {
  override fun fileOpened(source: FileEditorManager, file: VirtualFile) = refreshLater(source)

  override fun selectionChanged(event: FileEditorManagerEvent) = refreshLater(event.manager)

  override fun filePinStateChanged(source: FileEditorManager, file: VirtualFile) {
    ApplicationManager.getApplication().invokeLater({
      val service = source.project.groupsInUse() ?: return@invokeLater
      for (controller in service.allControllers()) {
        if (controller.window.isFileOpen(file) && controller.window.isFilePinned(file)) controller.removeFromGroup(file) else controller.refresh()
      }
    }, source.project.disposed)
  }

  override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
    source.project.serviceIfCreated<TabGroupsService>()?.existingControllers()?.forEach { it.afterClose() }
  }

  private fun refreshLater(source: FileEditorManager) {
    ApplicationManager.getApplication().invokeLater({
      source.project.groupsInUse()?.allControllers()?.forEach { it.refresh() }
    }, source.project.disposed)
  }
}

/**
 * The project's groups when it has any. Before the service exists (it is made by [TabGroupsStartup] or a tab action) there
 * are none to show yet; saved ones are brought back by [TabGroupsStartup] once their panes are listed.
 */
private fun Project.groupsInUse(): TabGroupsService? = serviceIfCreated<TabGroupsService>()?.takeIf { it.isInUse }

/** Keeps the selection off tabs hidden in collapsed groups when the selected tab closes. */
internal class TabGroupsCloseListener : FileEditorManagerListener.Before {
  override fun beforeFileClosed(source: FileEditorManager, file: VirtualFile) {
    val service = source.project.serviceIfCreated<TabGroupsService>() ?: return
    for (controller in service.existingControllers()) {
      if (controller.window.isFileOpen(file)) controller.beforeClose(file)
    }
  }
}
