// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.jvm

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.util.PsiModificationTracker

/**
 * Narrow dependencies for project-wide caches, so they are not recomputed on every keystroke in any file (which
 * `PsiModificationTracker.MODIFICATION_COUNT` does).
 */
internal object CacheDeps {
  // Groovy too: entities and beans may be written in it
  private val JVM = setOf("JAVA", "kotlin", "Groovy")
  // TEXT: a config file whose language plugin is off, or that is mapped to plain text
  private val CONFIG = setOf("yaml", "Properties", "TEXT")

  /** Changes to Java, Kotlin or Groovy sources. */
  fun jvm(project: Project): ModificationTracker =
    PsiModificationTracker.getInstance(project).forLanguages { it.id in JVM }

  /**
   * What text read from config [files] depends on: edits to config PSI, each file's content on disk (changed while
   * not open in an editor), files created, deleted or renamed, and source roots.
   */
  fun config(project: Project, files: Collection<VirtualFile>): Array<Any> = arrayOf(
    PsiModificationTracker.getInstance(project).forLanguages { it.id in CONFIG },
    VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
    ProjectRootManager.getInstance(project),
    *files.toTypedArray(),
  )
}
