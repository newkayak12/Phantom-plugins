// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.tabs.core

import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent

/** A grouped file that is renamed or moved, or sits in a folder that is, stays in its group. */
internal class TabGroupsFileListener(private val project: Project) : BulkFileListener {
  override fun after(events: List<VFileEvent>) {
    val service = project.serviceIfCreated<TabGroupsService>() ?: return
    for (event in events) {
      val (oldUrl, newUrl) = when {
        event is VFilePropertyChangeEvent && event.propertyName == VirtualFile.PROP_NAME -> {
          val parent = event.file.parent ?: continue
          "${parent.url}/${event.oldValue}" to event.file.url
        }
        event is VFileMoveEvent -> "${event.oldParent.url}/${event.file.name}" to event.file.url
        else -> continue
      }
      service.fileMoved(oldUrl, newUrl)
    }
  }
}
