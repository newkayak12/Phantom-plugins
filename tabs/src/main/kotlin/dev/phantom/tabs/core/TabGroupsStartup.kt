// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.tabs.core

import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Brings back the saved groups when a project opens. Editor events alone are not enough: the editors can all come back
 * before the plugin sees their panes, and then nothing happens until the user clicks a tab. The editor manager tells
 * nothing public when its panes are back, so they are looked for every [STEP_MS] until every saved pane with groups is
 * listed. Restoring takes seconds; past [LIMIT_MS] (a slow start, or a saved pane that did not come back) the panes still
 * missing are left to editor events, and that is logged.
 */
internal class TabGroupsStartup : ProjectActivity {
  override suspend fun execute(project: Project) {
    val service = TabGroupsService.getInstance(project)
    // nothing saved, nothing to bring back: no trip to the EDT
    if (!service.hasSavedGroups) return
    var waited = 0L
    while (!withContext(Dispatchers.EDT) { service.restorePanes() }) {
      if (waited >= LIMIT_MS) {
        LOG.warn("Saved tab groups: not every saved editor pane was listed ${LIMIT_MS / 1000} s after ${project.name} opened; " +
                 "the groups of the missing panes come back with their next editor event")
        return
      }
      delay(STEP_MS)
      waited += STEP_MS
    }
  }

  private companion object {
    const val STEP_MS = 250L
    const val LIMIT_MS = 300_000L
    val LOG = logger<TabGroupsStartup>()
  }
}
