// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.core

data class Group(val id: String, val name: String, val parentId: String?, val order: Int, val collapsed: Boolean = false)

data class Repo(val path: String, val groupId: String?, val order: Int)

/** Groups and repos after normalizing; [childrenOf] / [reposOf] with null mean the root / ungrouped. */
class GroupTree(val groups: List<Group>, val repos: List<Repo>) {
  fun childrenOf(parentId: String?): List<Group> = groups.filter { it.parentId == parentId }.sortedBy { it.order }

  fun reposOf(groupId: String?): List<Repo> = repos.filter { it.groupId == groupId }.sortedBy { it.order }

  companion object {
    /**
     * A repeated group id or repo path keeps its first entry; a parent that is missing, the group itself or part of
     * a cycle becomes root; a repo of no known group is ungrouped; order is by (order, input position) and renumbered 0..n.
     */
    fun normalize(groups: List<Group>, repos: List<Repo>): GroupTree {
      val byId = LinkedHashMap<String, Group>()
      for (g in groups) byId.putIfAbsent(g.id, g)
      val parent = HashMap<String, String?>()
      for (g in byId.values) parent[g.id] = g.parentId?.takeIf { it != g.id && it in byId }
      for (g in byId.values) {
        val seen = hashSetOf(g.id)
        var p = parent[g.id]
        while (p != null) {
          if (!seen.add(p)) {
            parent[g.id] = null
            break
          }
          p = parent[p]
        }
      }
      val position = byId.keys.withIndex().associate { it.value to it.index }
      val outGroups = byId.values.groupBy { parent[it.id] }.flatMap { (pid, siblings) ->
        siblings.sortedWith(compareBy({ it.order }, { position.getValue(it.id) }))
          .mapIndexed { i, g -> g.copy(parentId = pid, order = i) }
      }
      val uniqueRepos = LinkedHashMap<String, Repo>()
      for (r in repos) uniqueRepos.putIfAbsent(r.path, r)
      val rpos = uniqueRepos.keys.withIndex().associate { it.value to it.index }
      val outRepos = uniqueRepos.values.groupBy { r -> r.groupId?.takeIf { it in byId } }.flatMap { (gid, rs) ->
        rs.sortedWith(compareBy({ it.order }, { rpos.getValue(it.path) }))
          .mapIndexed { i, r -> r.copy(groupId = gid, order = i) }
      }
      return GroupTree(outGroups, outRepos)
    }
  }
}
