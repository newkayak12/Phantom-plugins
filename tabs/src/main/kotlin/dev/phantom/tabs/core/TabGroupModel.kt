// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.tabs.core

import java.util.UUID

/** Chrome's tab group colors, in Chrome's order. */
enum class TabGroupColor { GREY, BLUE, RED, YELLOW, GREEN, PINK, PURPLE, CYAN }

class TabGroup(
  val id: String,
  var name: String,
  var color: TabGroupColor,
  var collapsed: Boolean,
  val members: MutableList<String>,
)

/**
 * The tab groups of one editor pane. Tabs are file URLs. Decides membership and the tab order; knows nothing of Swing.
 */
class TabGroupModel {
  private val myGroups = mutableListOf<TabGroup>()

  val groups: List<TabGroup>
    get() = myGroups

  fun groupOf(url: String): TabGroup? = myGroups.firstOrNull { url in it.members }

  /** Takes [urls] out of their groups and makes a new group of them. */
  fun create(urls: List<String>, name: String, color: TabGroupColor): TabGroup {
    urls.forEach(::remove)
    val group = TabGroup(UUID.randomUUID().toString(), name, color, collapsed = false, members = urls.distinct().toMutableList())
    myGroups.add(group)
    return group
  }

  fun add(url: String, group: TabGroup) {
    if (group.members.contains(url)) return
    remove(url)
    group.members.add(url)
  }

  /** Takes [url] out of its group; a group left empty is gone. */
  fun remove(url: String) {
    val group = groupOf(url) ?: return
    group.members.remove(url)
    if (group.members.isEmpty()) myGroups.remove(group)
  }

  fun ungroup(group: TabGroup) {
    myGroups.remove(group)
  }

  /** The first palette color no group uses, or [TabGroupColor.GREY] when all are taken. */
  fun nextColor(): TabGroupColor {
    val used = myGroups.mapTo(HashSet()) { it.color }
    // grey last: a new group should stand out in color
    return (TabGroupColor.entries.drop(1) + TabGroupColor.GREY).firstOrNull { it !in used } ?: TabGroupColor.BLUE
  }

  /** A group dragged by one tab ([onMoved]): its tabs gather where that tab was dropped, once. */
  private var lead: String? = null

  /**
   * [current] reordered so that each group's tabs sit together, where the group's first tab is (or where a group dragged
   * as a whole was dropped), in the group's own order. Tabs that are in no group keep their places relative to each other.
   */
  fun desiredOrder(current: List<String>): List<String> {
    val lead = lead.also { lead = null }
    if (myGroups.isEmpty()) return current
    val leadGroup = lead?.takeIf { it in current }?.let(::groupOf)
    val result = ArrayList<String>(current.size)
    val placed = HashSet<TabGroup>()
    for (url in current) {
      val group = groupOf(url)
      if (group == null) {
        result.add(url)
      }
      else if ((group !== leadGroup || url == lead) && placed.add(group)) {
        group.members.filterTo(result) { it in current }
      }
    }
    return result
  }

  /**
   * A tab was dragged from [before] to [after]. It joins a group when it lands between two of its tabs, stays in its group
   * while it still touches a tab of it, and leaves it otherwise. [dragged] is the tab the user dragged (the selected one),
   * which settles swaps of two neighbors, where either tab could have moved.
   *
   * The whole group goes with the tab, as in Chrome, when it was dragged by the group's chip ([byChip]), when the group is
   * collapsed (only that tab shows) or when it is the group's only tab: it stays in its group as its first tab, and
   * [desiredOrder] brings the other tabs after it.
   */
  fun onMoved(before: List<String>, after: List<String>, dragged: String? = null, byChip: Boolean = false) {
    val moved = dragged?.takeIf { it in before && before.minus(it) == after.minus(it) && before != after }
                ?: movedElement(before, after) ?: return
    val group = groupOf(moved)
    if (group != null && (byChip || group.collapsed || group.members.size == 1)) {
      group.members.remove(moved)
      group.members.add(0, moved)
      lead = moved
      return
    }
    val index = after.indexOf(moved)
    val left = after.getOrNull(index - 1)?.let(::groupOf)
    val right = after.getOrNull(index + 1)?.let(::groupOf)
    val own = groupOf(moved)
    when {
      left != null && left === right -> add(moved, left)
      own != null && (left === own || right === own) -> {}
      else -> remove(moved)
    }
    groupOf(moved)?.members?.sortBy { after.indexOf(it) }
  }

  /** [oldUrl] (a file, or a folder and everything in it) is now at [newUrl]. */
  fun rename(oldUrl: String, newUrl: String) {
    for (group in myGroups) {
      group.members.replaceAll { url ->
        when {
          url == oldUrl -> newUrl
          url.startsWith("$oldUrl/") -> newUrl + url.substring(oldUrl.length)
          else -> url
        }
      }
    }
  }
}

/** The one element whose removal from both lists makes them equal, or null when no single move explains the change. */
internal fun movedElement(before: List<String>, after: List<String>): String? {
  if (before.size != after.size || before == after) return null
  return before.firstOrNull { candidate -> before.minus(candidate) == after.minus(candidate) }
}
