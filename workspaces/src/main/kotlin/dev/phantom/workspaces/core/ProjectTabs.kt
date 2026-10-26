// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.workspaces.core

/** An open project as the tab strip sees it. [key] identifies it (the [com.intellij.openapi.project.Project] in the IDE). */
data class OpenProject(val key: Any, val name: String, val location: String?)

data class ProjectTab(val key: Any, val title: String, val tooltip: String?, val isCurrent: Boolean)

/** What the project tab strip shows. No UI and no platform state, so it is tested as is. */
object ProjectTabs {
  const val SETTING_ID: String = "phantom.workspaces.project.tabs"
  const val EXTENSION_KEY: String = "phantom.workspaces.project.tabs"
  const val CLOSE_PROJECT_ACTION_ID: String = "CloseProject"

  /** One tab per project in [open] order; projects that share a name are titled by their location instead. */
  fun tabs(open: List<OpenProject>, current: Any?, userHome: String): List<ProjectTab> {
    val sharedNames = open.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
    return open.map { project ->
      val where = project.location?.let { shortened(it, userHome) }
      val title = if (project.name in sharedNames && where != null) where else project.name
      ProjectTab(project.key, title, where, project.key == current)
    }
  }

  /** The strip is only worth its height when there is another project to switch to. */
  fun isStripVisible(enabled: Boolean, tabCount: Int): Boolean = enabled && tabCount > 1

  private fun shortened(path: String, home: String): String {
    if (home.isEmpty() || !path.startsWith(home)) return path
    if (path.length == home.length) return "~"
    val next = path[home.length]
    return if (next == '/' || next == '\\') "~" + path.substring(home.length) else path
  }
}
