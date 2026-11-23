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
 * nothing public when its panes are back, so they are looked for after each of [RETRY_DELAYS] (from [FIRST_STEP_MS],
 * doubling up to [MAX_STEP_MS]) until every saved pane with groups is listed. Restoring takes seconds; past [LIMIT_MS]
 * (a slow start, or a saved pane that did not come back) the panes still missing are left to editor events, and that is
 * logged. Each look runs on the EDT, so they get rarer instead of running four times a second for minutes.
 */
internal class TabGroupsStartup : ProjectActivity {
  override suspend fun execute(project: Project) {
    val service = TabGroupsService.getInstance(project)
    // nothing saved, nothing to bring back: no trip to the EDT
    if (!service.hasSavedGroups) return
    for (wait in RETRY_DELAYS) {
      if (withContext(Dispatchers.EDT) { service.restorePanes() }) return
      delay(wait)
    }
    if (withContext(Dispatchers.EDT) { service.restorePanes() }) return
    LOG.warn("Saved tab groups: not every saved editor pane was listed ${LIMIT_MS / 1000} s after ${project.name} opened; " +
             "the groups of the missing panes come back with their next editor event")
  }

  internal companion object {
    const val FIRST_STEP_MS = 250L
    const val MAX_STEP_MS = 2_000L
    const val LIMIT_MS = 15_000L

    /** The waits between looks: doubling from [FIRST_STEP_MS], at most [MAX_STEP_MS] each, [LIMIT_MS] in all. */
    val RETRY_DELAYS: List<Long> = buildList {
      var step = FIRST_STEP_MS
      var total = 0L
      while (total < LIMIT_MS) {
        val wait = minOf(step, LIMIT_MS - total)
        add(wait)
        total += wait
        step = minOf(step * 2, MAX_STEP_MS)
      }
    }

    private val LOG = logger<TabGroupsStartup>()
  }
}
