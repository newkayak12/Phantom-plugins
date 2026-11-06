// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.workspaces.core

import kotlin.math.abs

/** An open project as the tab strip sees it. [key] identifies it (the [com.intellij.openapi.project.Project] in the IDE). */
data class OpenProject(val key: Any, val name: String, val location: String?)

/** [isSideBySide]: on screen beside the current one ([ProjectTabsService.openSideBySide]); highlighted as its partner. */
data class ProjectTab(val key: Any, val title: String, val tooltip: String?, val isCurrent: Boolean, val isSideBySide: Boolean = false)

/** What the project tab strip shows. No UI and no platform state, so it is tested as is. */
object ProjectTabs {
  const val SETTING_ID: String = "phantom.workspaces.project.tabs"
  const val EXTENSION_KEY: String = "phantom.workspaces.project.tabs"
  const val CLOSE_PROJECT_ACTION_ID: String = "CloseProject"

  /**
   * One tab per project in [open] order; projects that share a name are titled by the shortest end of their location
   * that tells them apart (the worktrees of one repository: by their folders), the whole location in the tooltip. The
   * projects in [sideBySide] are marked, the current one included.
   */
  fun tabs(open: List<OpenProject>, current: Any?, userHome: String, sideBySide: List<Any> = emptyList()): List<ProjectTab> {
    val shared = open.groupBy { it.name }.filterValues { it.size > 1 }
    return open.map { project ->
      val where = project.location?.let { shortened(it, userHome) }
      val namesakes = shared[project.name]
      val title = if (namesakes != null && where != null) distinctEnd(project, namesakes, userHome) ?: where else project.name
      ProjectTab(project.key, title, where, project.key == current, sideBySide.any { it == project.key })
    }
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

  /**
   * [order] with the item at [from] dropped at [gap]: the gap before the item at that index, [order].size after the last.
   * Unchanged when [from] or [gap] is out of range, or the gap is on either side of the item.
   */
  fun <T> moved(order: List<T>, from: Int, gap: Int): List<T> {
    if (from !in order.indices || gap !in 0..order.size || gap == from || gap == from + 1) return order
    val result = order.toMutableList()
    val item = result.removeAt(from)
    result.add(if (gap > from) gap - 1 else gap, item)
    return result
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

  /** A tab dragged [dy] up or down from where it was pressed is torn out of a strip [stripHeight] high. */
  fun isDetachDrag(dy: Int, stripHeight: Int, threshold: Int): Boolean = abs(dy) > stripHeight + threshold

  /**
   * The widths of tabs that want [preferred] in a strip [available] wide: as wanted when they fit, else the widest are cut
   * down to one width (as browsers do), never below [minimum]. Tabs that still do not fit scroll ([scrollOffset]).
   */
  fun fitted(preferred: List<Int>, available: Int, minimum: Int): List<Int> {
    if (preferred.sum() <= available) return preferred
    var left = available
    var cap = minimum
    val sorted = preferred.sorted()
    for ((i, width) in sorted.withIndex()) {
      val rest = sorted.size - i
      if (width * rest > left) {
        cap = left / rest
        break
      }
      left -= width
    }
    val width = maxOf(cap, minimum)
    return preferred.map { minOf(it, width) }
  }

  /** [offset] kept within the [total] width of the tabs beyond a strip [available] wide. */
  fun scrollOffset(offset: Int, total: Int, available: Int): Int = offset.coerceIn(0, maxOf(0, total - available))

  /** The scroll offset that shows the tab from [start] to [end] whole, moving [offset] as little as it can. */
  fun reveal(offset: Int, start: Int, end: Int, available: Int): Int = when {
    start < offset -> start
    end > offset + available -> end - available
    else -> offset
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
