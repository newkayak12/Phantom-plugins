// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring.core

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.util.registry.RegistryValue
import com.intellij.openapi.util.registry.RegistryValueListener

/**
 * Ghost Mode keeps coloring, syntax errors, completion and navigation and turns off this plugin's inspections and gutter icons.
 * Phantom registers and toggles the `phantom.ghost` registry key; this copy only reads it, so the plugin needs no dependency
 * on another plugin and an IDE without the key reads it as off.
 */
object GhostMode {
  const val KEY: String = "phantom.ghost"

  val enabled: Boolean
    get() = Registry.`is`(KEY, false)
}

/** Reruns highlighting so inspections and gutter icons appear or disappear as soon as Ghost Mode is switched. */
class GhostModeListener : RegistryValueListener {
  override fun afterValueChanged(value: RegistryValue) {
    if (value.key != GhostMode.KEY) return
    for (project in ProjectManager.getInstance().openProjects) {
      DaemonCodeAnalyzer.getInstance(project).restart()
    }
  }
}
