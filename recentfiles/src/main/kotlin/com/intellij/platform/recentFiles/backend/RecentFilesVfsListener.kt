// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.
package com.intellij.platform.recentFiles.backend

import com.intellij.ide.actions.shouldUseFallbackSwitcher
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.diagnostic.trace
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.AsyncFileListener
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.isFile
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.platform.recentFiles.shared.FileChangeKind
import com.intellij.platform.recentFiles.shared.RecentFileKind

internal class RecentFilesVfsListener : AsyncFileListener {
  override fun prepareChange(events: List<VFileEvent>): AsyncFileListener.ChangeApplier? {
    if (ApplicationManager.getApplication().isUnitTestMode || shouldUseFallbackSwitcher()) return null

    // phantom-idea: only files the recent files lists hold are passed on, since BackendRecentFileEventsModel drops the
    // others; without this every save, VCS update or build output paid for a ProjectFileIndex lookup per file and project
    val known = knownRecentFiles()
    if (known.isEmpty()) return null
    val removedFiles = collectRemovedFiles(events).filter { it in known }
    val movedFiles = collectMovedFiles(events).filter { it in known }
    val renamedFiles = collectRenamedFiles(events).filter { it in known }
    val filesWithChangedContents = collectFilesWithChangedContents(events).filter { it in known }
    if (listOf(removedFiles, movedFiles, renamedFiles, filesWithChangedContents).all { it.isEmpty() }) return null

    return object : AsyncFileListener.ChangeApplier {
      override fun beforeVfsChange() {
        for (project in ProjectManager.getInstance().openProjects) {
          val projectFilesToRemove = filterProjectFiles(removedFiles, project)
          if (projectFilesToRemove.isNotEmpty()) {
            thisLogger().trace { "Removed files to apply changes for: ${projectFilesToRemove.joinToString { it.name }}" }
            BackendRecentFileEventsController.applyRelevantEventsToModel(projectFilesToRemove, FileChangeKind.REMOVED, project)
          }

          val projectFilesToRename = filterProjectFiles(renamedFiles, project)
          if (projectFilesToRename.isNotEmpty()) {
            thisLogger().trace { "Renamed files to apply changes for: ${projectFilesToRename.joinToString { it.name }}" }
            BackendRecentFileEventsController.applyRelevantEventsToModel(projectFilesToRename, FileChangeKind.UPDATED, project)
          }

          val projectFilesToMove = filterProjectFiles(movedFiles, project)
          val projectFilesWithChangedContents = filterProjectFiles(filesWithChangedContents, project)
          val projectFilesToUpdate = (projectFilesToMove + projectFilesWithChangedContents).distinct()
          if (projectFilesToUpdate.isNotEmpty()) {
            thisLogger().trace { "Moved files to apply changes for: ${projectFilesToMove.joinToString { it.name }}" }
            thisLogger().trace { "Changed content files to apply changes for: ${projectFilesWithChangedContents.joinToString { it.name }}" }
            BackendRecentFileEventsController.applyRelevantEventsToModel(projectFilesToUpdate, FileChangeKind.UPDATED, project)
          }
        }
      }
    }
  }

  // phantom-idea: the files of every recent files list of the open projects whose model exists
  private fun knownRecentFiles(): Set<VirtualFile> =
    ProjectManager.getInstance().openProjects.flatMapTo(HashSet()) { project ->
      val model = project.serviceIfCreated<BackendRecentFilesModel>() ?: return@flatMapTo emptyList()
      RecentFileKind.entries.flatMap(model::getFilesByKind)
    }

  private fun filterProjectFiles(
    files: List<VirtualFile>,
    project: Project,
  ): List<VirtualFile> {
    return files.filter { file ->
      file.isFile && file.isValid && ProjectFileIndex.getInstance(project).isInContent(file)
    }
  }

  private fun collectRenamedFiles(events: List<VFileEvent>): List<VirtualFile> {
    return events.filterIsInstance<VFilePropertyChangeEvent>()
      .filter { it.propertyName == VirtualFile.PROP_NAME }
      .map { it.file }
  }

  private fun collectMovedFiles(events: List<VFileEvent>): List<VirtualFile> {
    return events.filterIsInstance<VFileMoveEvent>()
      .map { it.file }
  }

  private fun collectRemovedFiles(events: List<VFileEvent>): List<VirtualFile> {
    return events.filterIsInstance<VFileDeleteEvent>()
      .map { it.file }
  }

  private fun collectFilesWithChangedContents(events: List<VFileEvent>): List<VirtualFile> {
    return events.filterIsInstance<VFileContentChangeEvent>()
      .map { it.file }
  }
}