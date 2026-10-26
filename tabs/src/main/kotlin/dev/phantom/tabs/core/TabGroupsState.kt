// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.tabs.core

import com.intellij.openapi.components.BaseState

/** The groups of every editor pane of a project, in the order of the panes. */
class TabGroupsState : BaseState() {
  var panes by list<PaneState>()
}

class PaneState : BaseState() {
  var groups by list<GroupState>()
}

class GroupState : BaseState() {
  var name by string()
  var color by string()
  var collapsed by property(false)
  var urls by list<String>()
}

fun TabGroupModel.toPaneState(): PaneState = PaneState().also { pane ->
  for (group in groups) {
    pane.groups.add(GroupState().apply {
      name = group.name
      color = group.color.name
      collapsed = group.collapsed
      urls.addAll(group.members)
    })
  }
}

/** Brings back the saved groups of a pane, keeping only files open in it; a group with none of them is dropped. */
fun TabGroupModel.restore(state: PaneState, openUrls: Set<String>) {
  for (saved in state.groups) {
    val urls = saved.urls.filter { it in openUrls }
    if (urls.isEmpty()) continue
    val color = TabGroupColor.entries.firstOrNull { it.name == saved.color } ?: TabGroupColor.GREY
    create(urls, saved.name.orEmpty(), color).collapsed = saved.collapsed
  }
}
