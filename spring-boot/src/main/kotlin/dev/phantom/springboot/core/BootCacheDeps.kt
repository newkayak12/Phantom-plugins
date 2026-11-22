// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.springboot.core

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.util.PsiModificationTracker

/**
 * Dependencies of the project-wide caches, narrower than `MODIFICATION_COUNT` so that typing in `application.yml` does
 * not rerun a source search and typing in Java does not re-read the configuration files.
 */
internal object BootCacheDeps {
  private val JVM_LANGUAGES = setOf("JAVA", "kotlin", "Groovy")
  private val CONFIG_LANGUAGES = setOf("yaml", "Properties", "TEXT")

  /** Java, Kotlin and Groovy sources (Groovy beans and `@Value` count too) and the project's roots and libraries. */
  fun jvm(project: Project): Array<Any> =
    arrayOf(PsiModificationTracker.getInstance(project).forLanguages { it.id in JVM_LANGUAGES }, ProjectRootManager.getInstance(project))

  /**
   * Configuration text: committed edits in YAML, `.properties` or plain-text files (a config file mapped to TEXT), the
   * [files] read, so a change on disk of a file not open in the editor counts, files created, deleted or renamed, and roots.
   */
  fun config(project: Project, files: Collection<VirtualFile>): Array<Any> =
    arrayOf(PsiModificationTracker.getInstance(project).forLanguages { it.id in CONFIG_LANGUAGES },
            VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS, ProjectRootManager.getInstance(project), *files.toTypedArray())
}
