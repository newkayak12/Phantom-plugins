// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring.core

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.util.PsiModificationTracker

/**
 * Dependencies for project-wide caches narrower than `PsiModificationTracker.MODIFICATION_COUNT`, which moves on every
 * PSI change of any language: a cache of config files then stops being rebuilt on each Java keystroke, and a cache of
 * code on each YAML keystroke.
 */
object SpringCacheDeps {
  // Groovy too: Groovy beans and @Value strings feed the same caches as Java and Kotlin ones
  private val JVM_LANGUAGES = setOf("JAVA", "kotlin", "Groovy")
  // config files are yaml or properties, or plain text when no plugin claims the extension
  private val CONFIG_LANGUAGES = setOf("yaml", "Properties", "TEXT")

  /** PSI changes in Java, Kotlin and Groovy sources. Roots (libraries) are not included. */
  fun jvm(project: Project): ModificationTracker = PsiModificationTracker.getInstance(project).forLanguages { it.id in JVM_LANGUAGES }

  /**
   * For a cache that read [files]: their PSI (unsaved edits once committed), their content on disk (changed while not
   * open), files created, deleted or renamed, and source roots.
   */
  fun config(project: Project, files: Collection<VirtualFile>): Array<Any> =
    listOf<Any>(PsiModificationTracker.getInstance(project).forLanguages { it.id in CONFIG_LANGUAGES },
                VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS, ProjectRootManager.getInstance(project)).plus(files).toTypedArray()
}
