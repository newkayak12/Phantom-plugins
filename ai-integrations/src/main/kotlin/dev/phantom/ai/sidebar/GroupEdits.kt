// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.sidebar

import dev.phantom.ai.core.Group
import dev.phantom.ai.core.GroupTree
import dev.phantom.ai.core.Repo
import java.util.UUID

/** Pure edits of the group tree; each returns a normalized tree. */
object GroupEdits {
  fun addGroup(t: GroupTree, name: String, parentId: String?, id: String = UUID.randomUUID().toString()): GroupTree =
    GroupTree.normalize(t.groups + Group(id, name.trim().ifEmpty { "Untitled group" }, parentId, Int.MAX_VALUE), t.repos)

  fun rename(t: GroupTree, id: String, name: String): GroupTree =
    name.trim().takeIf { it.isNotEmpty() }
      ?.let { n -> GroupTree.normalize(t.groups.map { if (it.id == id) it.copy(name = n) else it }, t.repos) } ?: t

  /** Child groups and repos move up to the parent of the deleted group. */
  fun delete(t: GroupTree, id: String): GroupTree {
    val parent = t.groups.firstOrNull { it.id == id }?.parentId
    return GroupTree.normalize(
      t.groups.filter { it.id != id }.map { if (it.parentId == id) it.copy(parentId = parent, order = Int.MAX_VALUE) else it },
      t.repos.map { if (it.groupId == id) it.copy(groupId = parent, order = Int.MAX_VALUE) else it },
    )
  }

  fun setCollapsed(t: GroupTree, id: String, collapsed: Boolean): GroupTree =
    GroupTree.normalize(t.groups.map { if (it.id == id) it.copy(collapsed = collapsed) else it }, t.repos)

  /** True when [candidate] is [id] itself or one of its descendants. */
  fun isInSubtree(t: GroupTree, id: String, candidate: String?): Boolean {
    var cur = candidate
    val seen = HashSet<String>()
    while (cur != null && seen.add(cur)) {
      if (cur == id) return true
      cur = t.groups.firstOrNull { it.id == cur }?.parentId
    }
    return false
  }

  /** Moves a group under [parentId] (null = root) as the last child; into itself or a descendant is refused (tree unchanged). */
  fun nest(t: GroupTree, id: String, parentId: String?): GroupTree =
    if (isInSubtree(t, id, parentId)) t else GroupTree.normalize(t.groups.map { if (it.id == id) it.copy(parentId = parentId, order = Int.MAX_VALUE) else it }, t.repos)

  fun moveGroup(t: GroupTree, id: String, delta: Int): GroupTree {
    val g = t.groups.firstOrNull { it.id == id } ?: return t
    val siblings = t.childrenOf(g.parentId).map { it.id }.toMutableList()
    val i = siblings.indexOf(id)
    val j = (i + delta).coerceIn(0, siblings.lastIndex)
    siblings.add(j, siblings.removeAt(i))
    val order = siblings.withIndex().associate { it.value to it.index }
    return GroupTree.normalize(t.groups.map { if (it.id in order) it.copy(order = order.getValue(it.id)) else it }, t.repos)
  }

  fun addRepo(t: GroupTree, path: String, groupId: String?): GroupTree =
    if (t.repos.any { it.path == path }) t else GroupTree.normalize(t.groups, t.repos + Repo(path, groupId, Int.MAX_VALUE))

  fun moveRepoToGroup(t: GroupTree, path: String, groupId: String?): GroupTree =
    GroupTree.normalize(t.groups, t.repos.map { if (it.path == path) it.copy(groupId = groupId, order = Int.MAX_VALUE) else it })

  fun moveRepo(t: GroupTree, path: String, delta: Int): GroupTree {
    val r = t.repos.firstOrNull { it.path == path } ?: return t
    val siblings = t.reposOf(r.groupId).map { it.path }.toMutableList()
    val i = siblings.indexOf(path)
    siblings.add((i + delta).coerceIn(0, siblings.lastIndex), siblings.removeAt(i))
    val order = siblings.withIndex().associate { it.value to it.index }
    return GroupTree.normalize(t.groups, t.repos.map { if (it.path in order) it.copy(order = order.getValue(it.path)) else it })
  }

  fun removeRepo(t: GroupTree, path: String): GroupTree = GroupTree.normalize(t.groups, t.repos.filter { it.path != path })

  /** False when dropping [source] on [target] would put a group into its own subtree. */
  fun canDrop(t: GroupTree, source: String, target: String?): Boolean {
    if (!source.startsWith("group:") || target == null) return true
    val targetGroup = when {
      target.startsWith("repo:") -> t.repos.firstOrNull { it.path == target.removePrefix("repo:") }?.groupId
      else -> target.removePrefix("group:")
    }
    return !isInSubtree(t, source.removePrefix("group:"), targetGroup)
  }

  /** Drag and drop: [source] is "group:<id>" or "repo:<path>"; [target] a group id, a repo path (as "repo:<path>") or null = root. */
  fun drop(t: GroupTree, source: String, target: String?): GroupTree {
    val kind = source.substringBefore(':')
    val id = source.substringAfter(':')
    val targetGroup: String? = when {
      target == null -> null
      target.startsWith("repo:") -> t.repos.firstOrNull { it.path == target.removePrefix("repo:") }?.groupId
      else -> target.removePrefix("group:")
    }
    return when (kind) {
      "group" -> if (target == source) t else nest(t, id, targetGroup)
      "repo" -> {
        if (target == source) return t
        if (target != null && target.startsWith("repo:")) {
          val before = target.removePrefix("repo:")
          val moved = t.repos.firstOrNull { it.path == id } ?: return t
          val siblings = t.reposOf(targetGroup).map { it.path }.filter { it != id }.toMutableList()
          siblings.add(siblings.indexOf(before).coerceAtLeast(0), id)
          val order = siblings.withIndex().associate { it.value to it.index }
          GroupTree.normalize(t.groups, t.repos.map { r ->
            when {
              r.path == moved.path -> r.copy(groupId = targetGroup, order = order.getValue(r.path))
              r.path in order -> r.copy(order = order.getValue(r.path))
              else -> r
            }
          })
        } else moveRepoToGroup(t, id, targetGroup)
      }
      else -> t
    }
  }
}
