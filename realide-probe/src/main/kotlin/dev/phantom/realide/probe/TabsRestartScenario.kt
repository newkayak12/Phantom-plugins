// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.realide.probe

import com.intellij.openapi.fileEditor.FileEditorManager
import kotlinx.coroutines.delay
import java.nio.file.Files

/**
 * Run right after `tabs`, same sandbox config, system and fixtures: the IDE reopens alpha with the editors it saved.
 * Compares groups, colours, collapsed state and titles with what `tabs` recorded before it exited.
 */
internal object TabsRestartScenario : ProbeScenario {
  override suspend fun run(ctx: ProbeContext) {
    val t = TabsProbe(ctx)
    // chosen by popup.diagnose in `tabs` (every posted variant opened the platform popup there)
    t.trigger = ProbePopup.VARIANTS.first()
    val file = snapshotFile(ctx)
    val lines = if (Files.isRegularFile(file)) Files.readAllLines(file).filter { it.isNotBlank() }.map { it.split('\t') } else emptyList()
    val beforeGroups = lines.filter { it[0] == "group" }
    val beforeTabs = lines.filter { it[0] == "tab" }
    val panes = (beforeTabs.maxOfOrNull { it[1].toInt() } ?: 0) + 1

    t.resolve(beforeTabs.map { it[2] }.distinct())
    // the editors come back one by one; then the platform's own restore settles
    ctx.waitUntil(20_000) { t.windows().size >= panes && FileEditorManager.getInstance(ctx.project).openFiles.isNotEmpty() }
    ctx.waitUntil(10_000) { (0 until panes).all { p -> beforeTabs.filter { it[1].toInt() == p }.all { it[2] in t.order(p) } } }
    // the Project view the IDE reopens takes half the width, which pushes tabs off pane 0's strip: closed, as a user would
    ctx.edt { com.intellij.openapi.wm.ToolWindowManager.getInstance(ctx.project).getToolWindow("Project")?.hide() }
    delay(3000)
    val untouched = ctx.edt { mapOf("state" to t.state(), "controllers" to t.controllerCount(), "tabs" to (0 until t.windows().size).map { t.tabs(it) }) }
    fun groupsNow(): List<List<String>> = t.state().flatMapIndexed { pane, gs ->
      gs.map { g -> listOf("group", pane.toString(), g["name"].toString(), g["color"].toString(), g["collapsed"].toString(), (g["members"] as List<*>).joinToString(",")) }
    }

    ctx.step("restore.groups", mapOf("groupsEqualBeforeRestart" to true)) {
      ctx.edt {
        val now = groupsNow()
        mapOf(
          // the plugin's live models hold them (one controller per pane), not only the saved copy getState() falls back to;
          // read before anything is clicked, so nothing but the IDE's own restore made them
          "groupsEqualBeforeRestart" to (beforeGroups.isNotEmpty() && t.controllerCount() == panes &&
                                         now.map { it.take(4) + it[5] }.toSet() == beforeGroups.map { it.take(4) + it[5] }.toSet()),
          "before" to beforeGroups.map { it.joinToString(" ") }, "now" to now.map { it.joinToString(" ") },
          "untouched" to untouched, "controllers" to t.controllerCount(), "panes" to panes,
        )
      }
    }

    // members still shown (not hidden in a collapsed group) and non-members, in pane 0
    val members = beforeGroups.filter { it[1] == "0" && it[4] == "false" }.flatMap { it[5].split(',') }
    // files in no group of any pane (a file grouped in pane 1 is also open, ungrouped, in pane 0)
    val others = beforeTabs.filter { it[1] == "0" && beforeGroups.none { g -> it[2] in g[5].split(',') } }.map { it[2] }
    ctx.step("restore.colors", mapOf("memberColorsNonNull" to true, "memberColorsMatchProvider" to true)) {
      if (members.isEmpty()) return@step mapOf("members" to "none saved")
      // a non-anchor member and a non-member with names of the same length, so the label texts weigh the same
      val member = members.drop(1).firstOrNull() ?: members.first()
      val other = others.firstOrNull { it.length == member.length && ctx.edt { t.info(it)?.let(t::label)?.let { l -> l.isShowing && l.width > 20 } == true && t.tabs().none { tab -> tab["file"] == it && tab["selected"] == true } } } ?: others.first()
      TabsScenario.colours(t, members, others) + TabsScenario.pixelsDiffer(ctx, t, "restore-colors.png", member, other) +
      mapOf("member" to member, "other" to other)
    }

    ctx.step("restore.collapsed", mapOf("collapsedEqualBeforeRestart" to true)) {
      ctx.edt {
        val now = groupsNow().associate { (it[1] + "/" + it[2]) to it[4] }
        val before = beforeGroups.associate { (it[1] + "/" + it[2]) to it[4] }
        // and the tab bar agrees: the members after the first of a collapsed group are hidden
        val hiddenOk = beforeGroups.filter { it[4] == "true" }.all { g ->
          g[5].split(',').drop(1).all { m -> t.hidden(m, g[1].toInt()) == true }
        }
        mapOf(
          "collapsedEqualBeforeRestart" to (before.isNotEmpty() && now == before && hiddenOk),
          "before" to before, "now" to now, "hiddenAsCollapsed" to hiddenOk,
          "hidden" to beforeTabs.associate { (it[1] + "/" + it[2]) to t.hidden(it[2], it[1].toInt()) },
        )
      }
    }

    ctx.step("restore.titles", mapOf("titlesEqualBeforeRestart" to true)) {
      ctx.edt {
        val before = beforeTabs.associate { (it[1] + "/" + it[2]) to it[3] }
        val now = beforeTabs.associate { (it[1] + "/" + it[2]) to t.text(it[2], it[1].toInt()) }
        mapOf("titlesEqualBeforeRestart" to (before.isNotEmpty() && now == before), "before" to before, "now" to now)
      }
    }

    ctx.step("log.noPluginErrors", mapOf("pluginErrors" to 0)) { TabsScenario.pluginErrors() }
  }
}
