// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.workspaces.core

/**
 * A project folder the Projects and Files list knows of: an open project ([key] is its
 * [com.intellij.openapi.project.Project]), a recent one, or a checkout of a repository one of those belongs to (a worktree
 * nobody opened, a main checkout). [repository] is the repository's `.git` directory, shared by its main checkout
 * ([isMain]) and its worktrees.
 */
data class KnownProject(
  val path: String,
  val name: String,
  val repository: String? = null,
  val branch: String? = null,
  val isMain: Boolean = false,
  val key: Any? = null,
)

/** One line of the list. [isIndented]: a worktree under its main checkout. [key]: the open project, null when not open. */
data class WorkspaceRow(
  val path: String,
  val name: String,
  val location: String,
  val branch: String?,
  val repository: String?,
  val isIndented: Boolean,
  val isMain: Boolean,
  val key: Any?,
  val isCurrent: Boolean,
) {
  val isOpen: Boolean get() = key != null
}

/** What the Projects and Files list shows. No UI and no platform state, so it is tested as is. */
object WorkspaceList {
  /**
   * The [known] projects grouped by repository: a repository's main checkout first, its other checkouts indented under it
   * by name; a folder outside git on its own. Groups keep the order their first project has in [known] (open ones first,
   * as the caller lists them). A path known twice counts once, as an open project if it is one. [current] is marked.
   */
  fun rows(known: List<KnownProject>, current: Any?, userHome: String): List<WorkspaceRow> {
    val byPath = LinkedHashMap<String, KnownProject>()
    for (project in known) {
      val seen = byPath[project.path]
      if (seen == null || (seen.key == null && project.key != null)) byPath[project.path] = project
    }
    val groups = LinkedHashMap<Any, MutableList<KnownProject>>()
    for (project in byPath.values) groups.getOrPut(project.repository ?: project.path) { ArrayList() } += project
    return groups.values.flatMap { members ->
      val main = members.firstOrNull { it.isMain }
      val rest = members.filter { it !== main }.sortedBy { it.name.lowercase() }
      listOfNotNull(main).map { row(it, false, current, userHome) } + rest.map { row(it, main != null, current, userHome) }
    }
  }

  /**
   * The [rows] whose name, location or branch contains [query], ignoring case; a worktree that matches keeps its main
   * checkout above it. All of them for a blank query.
   */
  fun filtered(rows: List<WorkspaceRow>, query: String): List<WorkspaceRow> {
    val q = query.trim()
    if (q.isEmpty()) return rows
    fun matches(row: WorkspaceRow) = listOfNotNull(row.name, row.location, row.path, row.branch).any { it.contains(q, ignoreCase = true) }
    val wanted = rows.filter(::matches).mapNotNullTo(HashSet()) { row -> row.repository.takeIf { row.isIndented } }
    return rows.filter { matches(it) || (it.isMain && it.repository in wanted) }
  }

  private fun row(project: KnownProject, indented: Boolean, current: Any?, home: String) = WorkspaceRow(
    path = project.path,
    name = project.name,
    location = ProjectTabs.shortened(project.path, home),
    branch = project.branch,
    repository = project.repository,
    isIndented = indented,
    isMain = project.isMain,
    key = project.key,
    isCurrent = project.key != null && project.key == current,
  )
}
