// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.openapi

import com.intellij.json.JsonLanguage
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.psi.util.PsiModificationTracker
import org.jetbrains.yaml.YAMLLanguage

internal val SPEC_EXTENSIONS = listOf("yaml", "yml", "json")

/**
 * Bumped when the content of a yaml/yml/json file changes in the VFS. The spec list reads the first bytes of files
 * from the VFS, so a change on disk to a file that is not open (no PSI event) must still invalidate it. One counter
 * instead of one dependency per file keeps a cache check O(1).
 */
@Service(Service.Level.PROJECT)
internal class SpecFileContentChanges(project: Project) : SimpleModificationTracker(), Disposable {
  init {
    project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
      override fun after(events: List<VFileEvent>) {
        if (events.any { it is VFileContentChangeEvent && it.file.extension?.lowercase() in SPEC_EXTENSIONS }) incModificationCount()
      }
    })
  }

  override fun dispose() {}
}

/**
 * What the spec list depends on: YAML/JSON PSI (unsaved edits), file content on disk, file creation/deletion/rename,
 * project roots (the project scope) and dumb mode. Java and Kotlin edits are not among them.
 */
internal fun specFileDeps(project: Project): Array<Any> = arrayOf(
  PsiModificationTracker.getInstance(project).forLanguages { it.isKindOf(YAMLLanguage.INSTANCE) || it.isKindOf(JsonLanguage.INSTANCE) },
  project.service<SpecFileContentChanges>(),
  VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
  ProjectRootManager.getInstance(project),
  DumbService.getInstance(project).modificationTracker,
)
