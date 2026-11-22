// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.jpa.core

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.ModificationTracker
import com.intellij.psi.util.PsiModificationTracker

/**
 * Dependencies for project-wide caches narrower than `PsiModificationTracker.MODIFICATION_COUNT`, which moves on every
 * PSI change of any language: a cache of repositories and entities then stops being rebuilt on each SQL, YAML or XML
 * keystroke.
 */
object JpaCacheDeps {
  // Groovy too: Groovy sources can declare repositories and entities like Java and Kotlin ones
  private val JVM_LANGUAGES = setOf("JAVA", "kotlin", "Groovy")

  /** PSI changes in Java, Kotlin and Groovy sources. Roots (libraries) are not included. */
  fun jvm(project: Project): ModificationTracker = PsiModificationTracker.getInstance(project).forLanguages { it.id in JVM_LANGUAGES }

  /** For a cache of JVM code: its sources and the roots and libraries it resolves against. */
  fun code(project: Project): Array<Any> = arrayOf(jvm(project), ProjectRootManager.getInstance(project))
}
