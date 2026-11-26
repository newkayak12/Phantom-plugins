// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.workspaces.core

/** An open project as the tab strip sees it. [key] identifies it (the [com.intellij.openapi.project.Project] in the IDE). */
data class OpenProject(val key: Any, val name: String, val location: String?, val git: GitCheckout? = null)

/**
 * [isSideBySide]: on screen beside the current one ([ProjectTabsService.openSideBySide]); highlighted as its partner.
 * [isMainCheckout]: the main checkout of a repository whose worktrees are open too.
 */
data class ProjectTab(
  val key: Any,
  val title: String,
  val tooltip: String?,
  val isCurrent: Boolean,
  val isSideBySide: Boolean = false,
  val isMainCheckout: Boolean = false,
)

/** The project tabs each window has, in the order they are switched through. No UI and no platform state, so it is tested as is. */
object ProjectTabs {
  const val SETTING_ID: String = "phantom.workspaces.project.tabs"
  const val EXTENSION_KEY: String = "phantom.workspaces.project.tabs"
  const val CLOSE_PROJECT_ACTION_ID: String = "CloseProject"

  /**
   * One tab per project in [open] order. Projects that share a git repository (its main checkout and worktrees) are titled
   * by their branch. Other projects that share a name are titled by the shortest end of their location that tells them
   * apart; the whole location is in the tooltip. The projects in [sideBySide] are marked, the current one included.
   */
  fun tabs(open: List<OpenProject>, current: Any?, userHome: String, sideBySide: List<Any> = emptyList()): List<ProjectTab> {
    val shared = open.groupBy { it.name }.filterValues { it.size > 1 }
    val repositories = open.mapNotNull { it.git }.groupBy { it.commonDir }.filterValues { it.size > 1 }
    return open.map { project ->
      val where = project.location?.let { shortened(it, userHome) }
      val checkouts = project.git?.let { repositories[it.commonDir] }
      // two checkouts detached at the same commit have the same title: they fall back to their locations
      val branch = project.git?.branch?.takeIf { b -> checkouts != null && checkouts.count { it.branch == b } == 1 }
      val namesakes = shared[project.name]
      val title = branch ?: if (namesakes != null && where != null) distinctEnd(project, namesakes, userHome) ?: where else project.name
      val isMain = checkouts != null && project.git.isMain
      ProjectTab(project.key, title, where, project.key == current, sideBySide.any { it == project.key }, isMain)
    }
  }

  /**
   * [order] with [item] moved right after the last other item of the same [repository]: a project that opens joins the
   * tabs of its repository. Unchanged when it has no repository or no other item shares it.
   */
  fun <T> placed(order: List<T>, item: T, repository: (T) -> String?): List<T> {
    val repo = repository(item) ?: return order
    val rest = order.filter { it !== item }
    val last = rest.indexOfLast { repository(it) == repo }
    if (last < 0) return order
    return rest.toMutableList().apply { add(last + 1, item) }
  }

  /** The fewest last folders of [project]'s location that no other of [namesakes] ends with; null if none does. */
  private fun distinctEnd(project: OpenProject, namesakes: List<OpenProject>, home: String): String? {
    fun parts(p: OpenProject) = p.location?.let { shortened(it, home) }?.split('/', '\\')?.filter { it.isNotEmpty() }.orEmpty()
    val mine = parts(project)
    val others = namesakes.filter { it !== project }.map(::parts)
    for (n in 1..mine.size) {
      val end = mine.takeLast(n)
      if (others.none { it.takeLast(n) == end }) return end.joinToString("/")
    }
    return null
  }

  /** [order] with [item] moved to the end: a project attached back to the tabs comes in as the last tab. */
  fun <T> attached(order: List<T>, item: T): List<T> = order.filter { it !== item } + item

  /**
   * The tab to show in place of [leaving] when it leaves the [tabs] (detached to its own window): the one after it, or
   * the one before when it is the last. Null when there is no other tab.
   */
  fun <T> neighbor(tabs: List<T>, leaving: T): T? {
    val index = tabs.indexOfFirst { it === leaving }
    if (index < 0 || tabs.size < 2) return null
    return if (index + 1 < tabs.size) tabs[index + 1] else tabs[index - 1]
  }

  /** [path] with the user's [home] written as ~. */
  internal fun shortened(path: String, home: String): String {
    if (home.isEmpty() || !path.startsWith(home)) return path
    if (path.length == home.length) return "~"
    val next = path[home.length]
    return if (next == '/' || next == '\\') "~" + path.substring(home.length) else path
  }
}
