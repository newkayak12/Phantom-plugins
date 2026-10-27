// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.realide.probe

import com.intellij.configurationStore.saveSettings
import com.intellij.ide.ui.UISettings
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import kotlinx.coroutines.delay
import java.awt.Point
import java.awt.event.MouseEvent
import java.nio.file.Files
import javax.swing.SwingUtilities
import javax.swing.SwingConstants

private const val NEW_GROUP = "Add Tab to New Group"
private const val ADD_TO_GROUP = "Add Tab to Group"
private val FILES = listOf("a.txt", "b.txt", "c.txt", "d.txt", "e.txt", "f.txt")

/** Records one step; an exception inside [observe] becomes observed.error (so the step fails, the run goes on). */
internal suspend fun ProbeContext.step(name: String, expected: Map<String, Any?>, observe: suspend () -> Map<String, Any?>) {
  val observed = try {
    observe()
  }
  catch (e: Throwable) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    mapOf("error" to e.toString())
  }
  report.step(name, expected, observed)
}

/** Lines saved by `tabs` for `tabs-restart`: tab-separated, see [TabsScenario.snapshot]. */
internal fun snapshotFile(ctx: ProbeContext) = ctx.fixtures.resolve("tabs-before-restart.tsv")

/**
 * Tab groups in a real IDE, as a user makes them: six tabs in one split, groups made, named, coloured, collapsed and
 * expanded through the tab's right-click menu, tabs closed, dragged in and out, the tab limit, a second split, and the
 * state saved for `tabs-restart`. Runs on project alpha with freshly generated fixtures (the Gradle task deletes the old
 * ones, so no saved group from an earlier run is left).
 */
internal object TabsScenario : ProbeScenario {
  override suspend fun run(ctx: ProbeContext) {
    val t = TabsProbe(ctx)
    val settings = UISettings.getInstance()
    val oldAtEnd = settings.openTabsAtTheEnd
    val oldLimit = settings.editorTabLimit

    ctx.step("setup", mapOf("groupsAtStart" to 0, "tabs" to FILES, "collapseSupported" to true)) {
      t.resolve(FILES)
      ctx.waitUntil { ProbeSnapshot.frame(ctx.project)?.isShowing == true }
      val groupsAtStart = ctx.edt { t.state().sumOf { it.size } }
      ctx.edt {
        FileEditorManager.getInstance(ctx.project).openFiles.forEach { FileEditorManager.getInstance(ctx.project).closeFile(it) }
        // new tabs go to the end, so the six tabs stand a..f in the order they are opened
        settings.openTabsAtTheEnd = true
      }
      t.open(FILES)
      ctx.waitUntil { t.order() == FILES && FILES.all { n -> t.info(n)?.let(t::label)?.isShowing == true } }
      ctx.edt {
        mapOf(
          "groupsAtStart" to groupsAtStart, "tabs" to t.order(),
          "collapseSupported" to (settings.editorTabPlacement == SwingConstants.TOP && settings.scrollTabLayoutInEditor && !settings.sortTabsAlphabetically),
          "openTabsAtTheEndWas" to oldAtEnd, "editorTabLimit" to oldLimit, "laf" to javax.swing.UIManager.getLookAndFeel().name,
        )
      }
    }

    // gate-2 of s1: how a right-click reaches the tab, per variant, before any menu step depends on it
    ctx.step("popup.diagnose", mapOf("realTriggerOpened" to true)) {
      val focus = ctx.edt { ProbeInput.focus(ctx.project) }
      val label = ctx.edt { t.info("b.txt")?.let(t::label) } ?: error("no label for b.txt")
      val attempts = ArrayList<Map<String, Any?>>()
      for (v in ProbePopup.VARIANTS) {
        val a = ProbePopup.attempt(ctx, label, v)
        attempts.add(a)
        ctx.edt { ProbePopup.closeAll() }
        delay(300)
        if (a["opened"] == true && t.trigger == null) t.trigger = v
      }
      val control = ProbePopup.attempt(ctx, label, "direct")
      ctx.edt { ProbePopup.closeAll() }
      // s1's smoke right-clicked the selected tab right after focus(): the same two sequences on the selected tab f.txt
      val selected = ctx.edt { t.info("f.txt")?.let(t::label) }
      val onSelected = listOf("label.pressTrigger", "label.s1Sequence").map { v ->
        selected?.let { ProbePopup.attempt(ctx, it, v) }.also { ctx.edt { ProbePopup.closeAll() }; delay(300) }
      }
      val afterFocus = selected?.let {
        ctx.edt { ProbeInput.focus(ctx.project) }
        ProbePopup.attempt(ctx, it, "label.s1Sequence", waitMs = 3000).also { ctx.edt { ProbePopup.closeAll() } }
      }
      t.clickVariant = if (t.trigger?.startsWith("window.") == true) "window.pressTrigger" else "label.pressTrigger"
      mapOf("realTriggerOpened" to (t.trigger != null), "chosen" to t.trigger, "attempts" to attempts, "directDispatchControl" to control, "selectedTab" to onSelected, "selectedTabRightAfterFocus" to afterFocus) + focus.observed()
    }

    ctx.step("nogroup.unchanged", mapOf("titles" to FILES, "tabColors" to FILES.map { null })) {
      ctx.edt { mapOf("titles" to FILES.map(t::text), "tabColors" to FILES.map { t.color(it) }, "tabs" to t.tabs()) }
    }

    ctx.step("menu.items", mapOf("containsItems" to listOf(NEW_GROUP))) {
      val (popup, attempt) = t.rightClick("a.txt")
      val texts = popup?.let { ctx.edt { ProbePopup.texts(it) } }.orEmpty()
      ctx.edt { ProbePopup.closeAll() }
      mapOf(
        "via" to (if (t.trigger == "robot") "robot" else ProbeInput.VIA_QUEUE), "trigger" to t.trigger,
        "containsItems" to listOf(NEW_GROUP).filter { it in texts }, "items" to texts, "attempt" to attempt,
      )
    }

    ctx.step("group.create", mapOf("members" to mapOf("Work" to listOf("a.txt")))) {
      val m = t.menu("a.txt", listOf(NEW_GROUP), answer = "Work")
      ctx.waitUntil(3000) { t.members().containsKey("Work") }
      m + ctx.edt { mapOf("members" to t.members(), "tabs" to t.tabs()) }
    }

    ctx.step("group.addSecond", mapOf("members" to mapOf("Work" to listOf("a.txt", "b.txt")), "containsItems" to listOf(NEW_GROUP, ADD_TO_GROUP))) {
      val m = t.menu("b.txt", listOf(ADD_TO_GROUP, "Work"))
      ctx.waitUntil(3000) { t.members()["Work"]?.size == 2 }
      val items = m["items"] as? List<*> ?: emptyList<Any?>()
      m + ctx.edt { mapOf("members" to t.members(), "containsItems" to listOf(NEW_GROUP, ADD_TO_GROUP).filter { it in items }) }
    }

    // the menu while the IDE indexes (dumb mode), as right after a project opens: the same items as in smart mode
    ctx.step("menu.dumb", mapOf("dumb" to true, "nonMemberItems" to listOf(NEW_GROUP, ADD_TO_GROUP), "memberItems" to listOf("Color", "Rename Group…", "Collapse Group"))) {
      val release = java.util.concurrent.CountDownLatch(1)
      ctx.edt {
        object : com.intellij.openapi.project.DumbModeTask() {
          override fun performInDumbMode(indicator: com.intellij.openapi.progress.ProgressIndicator) {
            release.await(30, java.util.concurrent.TimeUnit.SECONDS)
          }
        }.queue(ctx.project)
      }
      val dumb = ctx.waitUntil(10_000) { com.intellij.openapi.project.DumbService.isDumb(ctx.project) }
      try {
        val (p1, a1) = t.rightClick("c.txt")
        val nonMember = p1?.let { ctx.edt { ProbePopup.texts(it) } }.orEmpty()
        ctx.edt { ProbePopup.closeAll() }
        delay(300)
        val (p2, a2) = t.rightClick("a.txt")
        val member = p2?.let { ctx.edt { ProbePopup.texts(it) } }.orEmpty()
        ctx.edt { ProbePopup.closeAll() }
        mapOf(
          "via" to ProbeInput.VIA_QUEUE, "dumb" to dumb,
          "nonMemberItems" to listOf(NEW_GROUP, ADD_TO_GROUP).filter { it in nonMember },
          "memberItems" to listOf("Color", "Rename Group…", "Collapse Group").filter { it in member },
          "nonMemberAll" to nonMember, "memberAll" to member, "attempts" to listOf(a1["tries"], a2["tries"]),
        )
      }
      finally {
        release.countDown()
        ctx.waitUntil(10_000) { !com.intellij.openapi.project.DumbService.isDumb(ctx.project) }
      }
    }

    // members unselected from here on: a tab outside the group is selected
    t.select("f.txt")

    ctx.step("group.title", mapOf("texts" to mapOf("a.txt" to "Work  a.txt", "b.txt" to "b.txt"))) {
      ctx.waitUntil(4000) { t.text("a.txt") == "Work  a.txt" }
      ctx.edt { mapOf("texts" to mapOf("a.txt" to t.text("a.txt"), "b.txt" to t.text("b.txt")), "tabs" to t.tabs()) }
    }

    ctx.step("group.color", mapOf("memberColorsNonNull" to true, "memberColorsMatchProvider" to true, "nonMemberColorsDiffer" to true)) {
      ctx.waitUntil(4000) { t.color("a.txt") != null && t.color("b.txt") != null }
      colours(t, listOf("a.txt", "b.txt"), listOf("c.txt", "d.txt", "e.txt", "f.txt")) +
      pixelsDiffer(ctx, t, "group-color.png", "b.txt", "c.txt")
    }

    ctx.step("group.recolor", mapOf("colorChanged" to true, "memberColorsMatchProvider" to true)) {
      val before = ctx.edt { mapOf("tabColor" to t.color("b.txt"), "provider" to t.provider("b.txt")) }
      val rect = ctx.edt { t.labelRect("b.txt") }
      val png1 = ctx.edt { ProbeSnapshot.paint(ctx.project, ctx.out, "recolor-before.png") }
      val m = t.menu("a.txt", listOf("Color", "Red"))
      ctx.waitUntil(4000) { t.color("b.txt") != before["tabColor"] }
      val png2 = ctx.edt { ProbeSnapshot.paint(ctx.project, ctx.out, "recolor-after.png") }
      val rect2 = ctx.edt { t.labelRect("b.txt") }
      m + ctx.edt {
        val after = mapOf("tabColor" to t.color("b.txt"), "provider" to t.provider("b.txt"))
        mapOf(
          "before" to before, "after" to after, "colorChanged" to (after["tabColor"] != before["tabColor"]),
          "memberColorsMatchProvider" to listOf("a.txt", "b.txt").all { t.color(it) == t.provider(it) },
          "pngs" to listOf(png1, png2),
          "pixels" to listOf(mapOf("kind" to "differ", "min" to 12, "a" to mapOf("png" to "recolor-before.png", "rect" to rect), "b" to mapOf("png" to "recolor-after.png", "rect" to rect2))),
        )
      }
    }

    ctx.step("group.rename", mapOf("anchorText" to "Ops  a.txt")) {
      val m = t.menu("a.txt", listOf("Rename Group…"), answer = "Ops")
      ctx.waitUntil(4000) { t.text("a.txt") == "Ops  a.txt" }
      m + ctx.edt { mapOf("anchorText" to t.text("a.txt"), "members" to t.members()) }
    }

    var orderBeforeCollapse: List<String> = emptyList()
    ctx.step("group.collapse", mapOf("anchorText" to "Ops  2", "hidden" to mapOf("b.txt" to true), "anchorVisible" to true)) {
      orderBeforeCollapse = ctx.edt { t.order() }
      val m = t.menu("a.txt", listOf("Collapse Group"))
      ctx.waitUntil(4000) { t.text("a.txt") == "Ops  2" && t.hidden("b.txt") == true }
      val png = ctx.edt { ProbeSnapshot.paint(ctx.project, ctx.out, "collapsed.png") }
      m + ctx.edt {
        mapOf(
          "anchorText" to t.text("a.txt"), "hidden" to mapOf("b.txt" to t.hidden("b.txt")),
          "anchorVisible" to (t.hidden("a.txt") == false && t.info("a.txt")?.let(t::label)?.isShowing == true),
          "state" to t.state(), "tabs" to t.tabs(), "png" to png,
        )
      }
    }

    ctx.step("group.expand.click", mapOf("anchorText" to "Ops  a.txt", "hidden" to mapOf("b.txt" to false), "orderRestored" to true)) {
      val clicked = t.leftClick("a.txt")
      ctx.waitUntil(4000) { t.text("a.txt") == "Ops  a.txt" && t.hidden("b.txt") == false }
      ctx.edt {
        mapOf(
          "via" to ProbeInput.VIA_QUEUE, "clickVariant" to t.clickVariant, "posted" to clicked,
          "anchorText" to t.text("a.txt"), "hidden" to mapOf("b.txt" to t.hidden("b.txt")),
          "orderRestored" to (t.order() == orderBeforeCollapse), "order" to t.order(), "orderBefore" to orderBeforeCollapse,
          "selected" to t.tabs().firstOrNull { it["selected"] == true }?.get("file"),
        )
      }
    }

    t.select("f.txt")
    ctx.step("group.toggle.menu", mapOf("anchorTextAfterCollapse" to "Ops  2", "anchorTextAfterExpand" to "Ops  a.txt")) {
      val m1 = t.menu("a.txt", listOf("Collapse Group"))
      ctx.waitUntil(4000) { t.text("a.txt") == "Ops  2" }
      val afterCollapse = ctx.edt { t.text("a.txt") }
      val m2 = t.menu("a.txt", listOf("Expand Group"))
      ctx.waitUntil(4000) { t.text("a.txt") == "Ops  a.txt" }
      val afterExpand = ctx.edt { t.text("a.txt") }
      mapOf("via" to m2["via"], "collapseMenu" to m1, "expandMenu" to m2, "anchorTextAfterCollapse" to afterCollapse, "anchorTextAfterExpand" to afterExpand)
    }

    // Chrome: a click on the group's chip collapses it, another opens it
    ctx.step("group.chip.click", mapOf("hiddenAfterFirst" to true, "hiddenAfterSecond" to false, "chipPng" to true)) {
      suspend fun clickChip(): Boolean {
        val label = ctx.edt { t.info("a.txt")?.let(t::label) } as? com.intellij.ui.tabs.impl.TabLabel ?: return false
        return ctx.edt {
          val text = label.labelComponent as com.intellij.ui.SimpleColoredComponent
          val x = (0 until text.width).firstOrNull { text.findFragmentAt(it) == 0 } ?: return@edt false
          val p = SwingUtilities.convertPoint(text, Point(x + 3, text.height / 2), label)
          ProbePopup.postClick(label, p, t.clickVariant, MouseEvent.BUTTON1)
          true
        }
      }
      val first = clickChip()
      ctx.waitUntil(4000) { t.hidden("b.txt") == true }
      val hiddenAfterFirst = ctx.edt { t.hidden("b.txt") }
      val png = ctx.edt { ProbeSnapshot.paint(ctx.project, ctx.out, "chip-collapsed.png") }
      val second = clickChip()
      ctx.waitUntil(4000) { t.hidden("b.txt") == false }
      val hiddenAfterSecond = ctx.edt { t.hidden("b.txt") }
      val png2 = ctx.edt { ProbeSnapshot.paint(ctx.project, ctx.out, "chip-expanded.png") }
      mapOf("clicked" to listOf(first, second), "hiddenAfterFirst" to hiddenAfterFirst, "hiddenAfterSecond" to hiddenAfterSecond,
            "chipPng" to (png != null && png2 != null), "texts" to listOf(t.text("a.txt")))
    }

    ctx.step("close.member", mapOf("members" to mapOf("Ops" to listOf("a.txt", "c.txt")), "closedOpen" to false)) {
      val add = t.menu("c.txt", listOf(ADD_TO_GROUP, "Ops"))
      ctx.waitUntil(3000) { t.members()["Ops"]?.size == 3 }
      val close = t.menu("b.txt", listOf("Close"))
      ctx.waitUntil(3000) { "b.txt" !in t.order() }
      delay(500)
      ctx.edt { mapOf("via" to close["via"], "add" to add, "close" to close, "members" to t.members(), "closedOpen" to ("b.txt" in t.order()), "tabs" to t.tabs()) }
    }

    ctx.step("close.collapsedAnchor", mapOf("orphanHiddenTabs" to 0)) {
      t.select("f.txt")
      val col = t.menu("a.txt", listOf("Collapse Group"))
      ctx.waitUntil(3000) { t.hidden("c.txt") == true }
      val before = ctx.edt { t.tabs() }
      val close = t.menu("a.txt", listOf("Close"))
      ctx.waitUntil(3000) { "a.txt" !in t.order() }
      delay(800)
      ctx.edt { mapOf("via" to close["via"], "collapse" to col, "close" to close, "tabsBefore" to before, "orphanHiddenTabs" to orphans(t), "tabs" to t.tabs(), "state" to t.state()) }
    }

    ctx.step("group.close", mapOf("closedFiles" to listOf("c.txt"), "groupGone" to true)) {
      val showing = ctx.edt { t.info("c.txt")?.let(t::label)?.isShowing == true }
      val m = if (showing) t.menu("c.txt", listOf("Close Group")) else mapOf("via" to "none", "reason" to "c.txt label not showing (hidden orphan)")
      ctx.waitUntil(3000) { "c.txt" !in t.order() }
      val r = ctx.edt { mapOf("closedFiles" to listOf("c.txt").filter { it !in t.order() }, "groupGone" to !t.members().containsKey("Ops"), "tabs" to t.tabs(), "state" to t.state()) }
      // what is left open of the group is closed so later steps start clean
      ctx.edt { t.file("c.txt")?.let { FileEditorManager.getInstance(ctx.project).closeFile(it) } }
      m + r
    }

    // T8: the name dialog cancelled. The plan accepts an unnamed group (Chrome keeps one too); the tab keeps its plain title.
    // The plugin's state (a BaseState string) keeps an empty name as null: groupName reads that null as ""
    ctx.step("group.nameDialog.cancel", mapOf("dialogClosed" to true, "groupCreated" to true, "groupName" to "", "title" to "f.txt", "modalLeft" to false)) {
      val before = ctx.edt { t.state().getOrNull(0).orEmpty() }
      val errorsBefore = pluginErrors()["pluginErrors"]
      val m = t.menu("f.txt", listOf(NEW_GROUP), cancel = true)
      ctx.waitUntil(2000) { t.state().getOrNull(0).orEmpty().size > before.size }
      delay(500)
      val r = ctx.edt {
        val after = t.state().getOrNull(0).orEmpty()
        val made = after.firstOrNull { g -> "f.txt" in (g["members"] as List<*>) }
        mapOf(
          "dialogClosed" to ((m["dialog"] as? Map<*, *>)?.get("closed") == true), "groupCreated" to (made != null), "groupName" to made?.let { it["name"] ?: "" }, "groupNameSaved" to made?.get("name"),
          "title" to t.text("f.txt"), "modalLeft" to (java.awt.Window.getWindows().any { it.isShowing && it is com.intellij.openapi.ui.DialogWrapperDialog }),
          "groupColor" to made?.get("color"), "tabColor" to t.color("f.txt"), "provider" to t.provider("f.txt"), "groupsBefore" to before, "groupsAfter" to after,
          "tabs" to t.tabs(),
        )
      }
      val errorsAfter = pluginErrors()["pluginErrors"]
      // clean up: the unnamed group is ungrouped through the popup (its item is there because f is a member)
      val u = if (r["groupCreated"] == true) t.menu("f.txt", listOf("Ungroup")) else null
      ctx.waitUntil(3000) { t.state().getOrNull(0).orEmpty().isEmpty() }
      m + r + mapOf("pluginErrorsBefore" to errorsBefore, "pluginErrorsAfter" to errorsAfter, "cleanup" to u, "groupsAfterCleanup" to ctx.edt { t.state() })
    }

    // T7: Close Group on the anchor of a collapsed group whose other members are hidden: every member closes, none is left
    // hidden without a group (closeFile on hidden TabInfos)
    ctx.step("group.close.collapsed", mapOf("closedFiles" to listOf("a.txt", "b.txt", "c.txt"), "groupGone" to true, "orphanHiddenTabs" to 0, "hiddenBeforeClose" to listOf("b.txt", "c.txt"))) {
      t.open(listOf("a.txt", "b.txt", "c.txt"))
      ctx.waitUntil(3000) { t.order() == listOf("d.txt", "e.txt", "f.txt", "a.txt", "b.txt", "c.txt") }
      val m1 = t.menu("a.txt", listOf(NEW_GROUP), answer = "Cg")
      val m2 = t.menu("b.txt", listOf(ADD_TO_GROUP, "Cg"))
      val m3 = t.menu("c.txt", listOf(ADD_TO_GROUP, "Cg"))
      ctx.waitUntil(3000) { t.members()["Cg"]?.size == 3 }
      t.select("f.txt")
      val col = t.menu("a.txt", listOf("Collapse Group"))
      ctx.waitUntil(3000) { t.hidden("b.txt") == true && t.hidden("c.txt") == true }
      val before = ctx.edt { mapOf("tabs" to t.tabs(), "state" to t.state(), "hidden" to listOf("a.txt", "b.txt", "c.txt").filter { t.hidden(it) == true }) }
      val errorsBefore = pluginErrors()["pluginErrors"]
      val close = t.menu("a.txt", listOf("Close Group"))
      ctx.waitUntil(4000) { listOf("a.txt", "b.txt", "c.txt").none { it in t.order() } }
      delay(800)
      val errorsAfter = pluginErrors()["pluginErrors"]
      val r = ctx.edt {
        mapOf(
          "via" to close["via"], "closedFiles" to listOf("a.txt", "b.txt", "c.txt").filter { it !in t.order() },
          "groupGone" to !t.members().containsKey("Cg"), "orphanHiddenTabs" to orphans(t), "hiddenBeforeClose" to before["hidden"],
          "tabsBefore" to before["tabs"], "stateBefore" to before["state"], "tabs" to t.tabs(), "state" to t.state(),
          "anyHiddenLeft" to t.infos(t.windows()[0]).count { it.isHidden }, "pluginErrorsBefore" to errorsBefore, "pluginErrorsAfter" to errorsAfter,
        )
      }
      // anything left open of the group is closed so later steps start from d, e, f
      ctx.edt { listOf("a.txt", "b.txt", "c.txt").mapNotNull(t::file).forEach { FileEditorManager.getInstance(ctx.project).closeFile(it) } }
      r + mapOf("setup" to listOf(m1, m2, m3), "collapse" to col, "close" to close)
    }

    // T7: a hidden member opened from outside the tab bar (Goto File / Recent Files navigate an OpenFileDescriptor, the
    // Project view double-clicks its node): the group expands, the member is selected and the order comes back
    ctx.step("open.hiddenMember", mapOf(
      "hidden" to mapOf("b.txt" to false), "selected" to "b.txt", "anchorText" to "Hid  a.txt", "orderRestored" to true,
      "projectViewOk" to true, "openFileOk" to true,
    )) {
      t.open(listOf("a.txt", "b.txt"))
      ctx.waitUntil(3000) { t.order() == listOf("d.txt", "e.txt", "f.txt", "a.txt", "b.txt") }
      val m1 = t.menu("a.txt", listOf(NEW_GROUP), answer = "Hid")
      val m2 = t.menu("b.txt", listOf(ADD_TO_GROUP, "Hid"))
      ctx.waitUntil(3000) { t.members()["Hid"]?.size == 2 }
      val orderBefore = ctx.edt { t.order() }
      val errorsBefore = pluginErrors()["pluginErrors"]

      suspend fun collapse(): Map<String, Any?> {
        t.select("f.txt")
        val c = t.menu("a.txt", listOf("Collapse Group"))
        val ok = ctx.waitUntil(3000) { t.hidden("b.txt") == true && t.text("a.txt") == "Hid  2" }
        return c + mapOf("collapsed" to ok, "tabsCollapsed" to ctx.edt { t.tabs() })
      }

      suspend fun result(how: String, extra: Map<String, Any?>): Map<String, Any?> {
        ctx.waitUntil(4000) { t.hidden("b.txt") == false && t.text("a.txt") == "Hid  a.txt" }
        delay(300)
        return ctx.edt {
          val selected = t.tabs().firstOrNull { it["selected"] == true }?.get("file")
          val r = mapOf(
            "hidden" to mapOf("b.txt" to t.hidden("b.txt")), "selected" to selected, "anchorText" to t.text("a.txt"),
            "orderRestored" to (t.order() == orderBefore), "order" to t.order(), "labelShowing" to (t.info("b.txt")?.let(t::label)?.isShowing == true),
            "selectedFileInEditor" to FileEditorManager.getInstance(ctx.project).selectedFiles.firstOrNull()?.name,
          )
          r + mapOf("how" to how, "ok" to (r["hidden"] == mapOf("b.txt" to false) && selected == "b.txt" && r["anchorText"] == "Hid  a.txt" && r["orderRestored"] == true)) + extra
        }
      }

      // 1. Goto File / Recent Files: OpenFileDescriptor.navigate(requestFocus = true)
      val c1 = collapse()
      ctx.edt { com.intellij.openapi.fileEditor.OpenFileDescriptor(ctx.project, t.file("b.txt")!!).navigate(true) }
      val nav = result("OpenFileDescriptor.navigate", mapOf("collapse" to c1))
      // 2. Project view: a double click on b.txt's node, posted through the system event queue
      val c2 = collapse()
      val pv = ProbeProjectView.doubleClick(ctx, t.file("b.txt")!!)
      val viaPv = if (pv["posted"] == true) result("projectView.doubleClick", mapOf("collapse" to c2, "projectView" to pv)) else mapOf("how" to "projectView.doubleClick", "ok" to false, "projectView" to pv)
      // 3. FileEditorManager.openFile(file, true): what most other entry points (find usages, VCS, bookmarks) end in
      val c3 = collapse()
      ctx.edt { FileEditorManager.getInstance(ctx.project).openFile(t.file("b.txt")!!, true) }
      val viaOpen = result("FileEditorManager.openFile", mapOf("collapse" to c3))
      val errorsAfter = pluginErrors()["pluginErrors"]

      // clean up: ungroup through the popup, close a and b, so group.remove starts from d, e, f with no group
      val u = t.menu("a.txt", listOf("Ungroup"))
      ctx.waitUntil(3000) { t.state().getOrNull(0).orEmpty().isEmpty() }
      ctx.edt { listOf("a.txt", "b.txt").mapNotNull(t::file).forEach { FileEditorManager.getInstance(ctx.project).closeFile(it) } }
      ctx.waitUntil(3000) { t.order() == listOf("d.txt", "e.txt", "f.txt") }

      nav.filterKeys { it in setOf("hidden", "selected", "anchorText", "orderRestored", "order") } + mapOf(
        "via" to "OpenFileDescriptor.navigate", "orderBefore" to orderBefore, "navigate" to nav,
        "projectViewOk" to viaPv["ok"], "projectView" to viaPv, "openFileOk" to viaOpen["ok"], "openFile" to viaOpen,
        "setup" to listOf(m1, m2), "pluginErrorsBefore" to errorsBefore, "pluginErrorsAfter" to errorsAfter, "cleanup" to u,
      )
    }

    ctx.step("group.remove", mapOf("members" to mapOf("Tmp" to listOf("d.txt", "e.txt")), "removedTitle" to "f.txt")) {
      val m1 = t.menu("d.txt", listOf(NEW_GROUP), answer = "Tmp")
      val m2 = t.menu("e.txt", listOf(ADD_TO_GROUP, "Tmp"))
      val m3 = t.menu("f.txt", listOf(ADD_TO_GROUP, "Tmp"))
      ctx.waitUntil(3000) { t.members()["Tmp"]?.size == 3 }
      val withF = ctx.edt { t.members() }
      val m4 = t.menu("f.txt", listOf("Remove from Group"))
      ctx.waitUntil(3000) { t.members()["Tmp"]?.size == 2 }
      ctx.edt { mapOf("via" to m4["via"], "create" to m1, "add" to listOf(m2, m3), "remove" to m4, "withF" to withF, "members" to t.members(), "removedTitle" to t.text("f.txt")) }
    }

    // a pinned tab cannot be in a group: pinning a member takes it out
    ctx.step("pin.member", mapOf("members" to mapOf("Tmp" to listOf("d.txt")), "pinnedTitle" to "e.txt")) {
      val m = t.menu("e.txt", listOf("Pin Tab"))
      ctx.waitUntil(3000) { t.members()["Tmp"]?.size == 1 }
      delay(500)
      val r = ctx.edt { mapOf("via" to m["via"], "pin" to m, "members" to t.members(), "pinnedTitle" to t.text("e.txt"), "pinned" to t.info("e.txt")?.isPinned) }
      val u = t.menu("e.txt", listOf("Unpin Tab"))
      r + mapOf("unpin" to u)
    }

    ctx.step("group.ungroup", mapOf("formerMembersPlainTitles" to true, "formerMembersColorNull" to true)) {
      val grouped = ctx.edt { mapOf("members" to t.members(), "tabs" to t.tabs()) }
      val m3 = t.menu("d.txt", listOf("Ungroup"))
      ctx.waitUntil(4000) { t.text("d.txt") == "d.txt" && t.color("d.txt") == null && t.color("e.txt") == null }
      ctx.edt {
        mapOf(
          "via" to m3["via"], "ungroup" to m3, "whileGrouped" to grouped,
          "formerMembersPlainTitles" to (t.text("d.txt") == "d.txt" && t.text("e.txt") == "e.txt"),
          "formerMembersColorNull" to (t.color("d.txt") == null && t.color("e.txt") == null), "tabs" to t.tabs(), "members" to t.members(),
        )
      }
    }

    // d, e, f still open; a, b, c come back at the end: d e f a b c
    t.open(listOf("a.txt", "b.txt", "c.txt"))
    ctx.waitUntil(3000) { t.order() == listOf("d.txt", "e.txt", "f.txt", "a.txt", "b.txt", "c.txt") }

    ctx.step("drag.in", mapOf("memberAfterDrag" to true)) {
      val m1 = t.menu("d.txt", listOf(NEW_GROUP), answer = "Drag")
      val m2 = t.menu("e.txt", listOf(ADD_TO_GROUP, "Drag"))
      ctx.waitUntil(3000) { t.members()["Drag"]?.size == 2 }
      val before = ctx.edt { t.order() }
      // a dropped on the left part of e: between d and e, the two tabs of Drag
      val d = t.drag("a.txt", { r -> r.getValue("e.txt").let { it.x + it.width / 4 } })
      ctx.waitUntil(3000) { t.members()["Drag"]?.contains("a.txt") == true }
      ctx.edt {
        mapOf(
          "via" to ProbeInput.VIA_QUEUE, "create" to m1, "add" to m2, "drag" to d, "orderBefore" to before, "order" to t.order(),
          "memberAfterDrag" to (t.members()["Drag"]?.contains("a.txt") == true), "members" to t.members(), "tabs" to t.tabs(),
        )
      }
    }

    ctx.step("drag.out", mapOf("memberAfterDrag" to false, "colorNull" to true)) {
      val before = ctx.edt { t.order() }
      val d = t.drag("a.txt", { r -> r.values.maxOf { it.x + it.width } - 4 })
      ctx.waitUntil(3000) { t.members()["Drag"]?.contains("a.txt") != true && t.color("a.txt") == null }
      ctx.edt {
        mapOf(
          "via" to ProbeInput.VIA_QUEUE, "drag" to d, "orderBefore" to before, "order" to t.order(),
          "memberAfterDrag" to (t.members()["Drag"]?.contains("a.txt") == true), "colorNull" to (t.color("a.txt") == null),
          "members" to t.members(), "tabs" to t.tabs(),
        )
      }
    }

    // Chrome: a tab dropped right after a group of one tab joins it
    ctx.step("drag.intoSingle", mapOf("joined" to true)) {
      val m = t.menu("b.txt", listOf(NEW_GROUP), answer = "One")
      ctx.waitUntil(3000) { t.members()["One"]?.size == 1 }
      val before = ctx.edt { t.order() }
      // c (right of it) dropped on the right part of b: the tabs left of c do not shift while it is dragged
      val d = t.drag("c.txt", { r -> r.getValue("b.txt").let { it.x + it.width - it.width / 4 } })
      ctx.waitUntil(3000) { t.members()["One"]?.contains("c.txt") == true }
      ctx.edt {
        mapOf("create" to m, "drag" to d, "orderBefore" to before, "order" to t.order(),
              "joined" to (t.members()["One"]?.contains("c.txt") == true), "members" to t.members())
      }
    }

    ctx.step("tablimit", mapOf("groupedTabsClosed" to 0)) {
      val grouped = ctx.edt { t.allMembers().values.flatten() }
      ctx.edt { settings.editorTabLimit = 3 }
      val extra = listOf("g.txt", "h.txt", "i.txt", "j.txt")
      extra.forEach(t::writeFixture)
      t.open(extra)
      delay(1500)
      val r = ctx.edt {
        mapOf("grouped" to grouped, "groupedTabsClosed" to grouped.count { it !in t.order() }, "order" to t.order(), "members" to t.members(), "limit" to 3)
      }
      ctx.edt { settings.editorTabLimit = oldLimit }
      r
    }

    // the six tabs back for what follows (the tab limit may have closed some)
    t.open(FILES)
    // groups belong to one split: the same file open in the other split, outside any group there, keeps its own title
    ctx.step("split.group", mapOf("panes" to 2, "pane1Members" to mapOf("Side" to listOf("j.txt")), "pane0Unchanged" to true, "otherPaneSameFileText" to "j.txt")) {
      t.select("j.txt")
      val pane0 = ctx.edt { t.members(0) }
      ctx.edt {
        val fem = FileEditorManagerEx.getInstanceEx(ctx.project)
        fem.createSplitter(SwingConstants.VERTICAL, fem.currentWindow)
      }
      ctx.waitUntil(5000) { t.windows().size == 2 && t.order(1).isNotEmpty() && t.info("j.txt", 1)?.let(t::label)?.isShowing == true }
      val m = t.menu("j.txt", listOf(NEW_GROUP), answer = "Side", pane = 1)
      ctx.waitUntil(3000) { t.members(1).containsKey("Side") }
      ctx.waitUntil(3000) { t.text("j.txt", 1) == "Side · j.txt" }
      delay(1000)
      m + ctx.edt {
        mapOf(
          "otherPaneSameFileText" to t.text("j.txt", 0), "groupedPaneText" to t.text("j.txt", 1),
          "otherPaneSameFileProvider" to t.provider("j.txt"),
          "panes" to t.windows().size, "pane1Members" to t.members(1), "pane0Unchanged" to (t.members(0) == pane0),
          "pane0Before" to pane0, "pane0" to t.members(0), "pane1Tabs" to t.tabs(1),
        )
      }
    }

    ctx.step("persist.save", mapOf("savedContainsGroups" to true)) {
      // what restart has to bring back: Drag expanded (coloured member e beside plain f), Fold collapsed, Side in pane 1.
      // Pane 0 is half as wide now: tabs no group needs are closed so b and c are in view to be right-clicked
      ctx.edt {
        val fem = FileEditorManagerEx.getInstanceEx(ctx.project)
        val w0 = t.windows()[0]
        listOf("h.txt", "i.txt", "a.txt").mapNotNull(t::file).filter { w0.isFileOpen(it) }.forEach { fem.closeFile(it, w0) }
      }
      ctx.waitUntil(3000) { listOf("b.txt", "c.txt", "d.txt").all { n -> t.info(n)?.let(t::label)?.isShowing == true } }
      val f1 = t.menu("b.txt", listOf(NEW_GROUP), answer = "Fold")
      val f2 = t.menu("c.txt", listOf(ADD_TO_GROUP, "Fold"))
      ctx.waitUntil(3000) { t.members()["Fold"]?.size == 2 }
      // d (Drag's first tab) selected in pane 0 by a click: e and f unselected for restore.colors, Fold not selected
      t.leftClick("d.txt")
      ctx.waitUntil(2000) { t.tabs().any { it["file"] == "d.txt" && it["selected"] == true } }
      val f3 = t.menu("b.txt", listOf("Collapse Group"))
      ctx.waitUntil(3000) { t.hidden("c.txt") == true }
      delay(500)
      val snapshot = ctx.edt { snapshot(t) }
      Files.writeString(snapshotFile(ctx), snapshot.joinToString("\n") + "\n")
      saveSettings(ctx.project, forceSavingAllSettings = true)
      val ws = ctx.fixtures.resolve("alpha/.idea/workspace.xml")
      val xml = if (Files.isRegularFile(ws)) Files.readString(ws) else ""
      val names = ctx.edt { t.allMembers().keys }
      val component = Regex("(?s)<component name=\"PhantomTabGroups\">.*?</component>").find(xml)?.value.orEmpty()
      ctx.edt { settings.openTabsAtTheEnd = oldAtEnd }
      mapOf(
        "fold" to listOf(f1, f2, f3), "groups" to names, "workspaceXml" to Files.isRegularFile(ws),
        "savedContainsGroups" to (component.isNotEmpty() && names.isNotEmpty() && names.all { "\"$it\"" in component }),
        "component" to component.take(1500), "snapshot" to snapshot, "state" to t.state(),
      )
    }

    ctx.step("log.noPluginErrors", mapOf("pluginErrors" to 0)) { pluginErrors() }
  }

  /** ERROR/SEVERE entries in this run's idea.log so far that mention dev.phantom.tabs. */
  fun pluginErrors(): Map<String, Any?> {
    val log = com.intellij.openapi.application.PathManager.getLogDir().resolve("idea.log")
    val errors = ProbeReport.phantomErrors(log).filter { "dev.phantom.tabs" in it }
    return mapOf("pluginErrors" to errors.size, "log" to log.toString(), "first" to errors.take(3))
  }

  /** Hidden tabs with no visible tab of their group left: they cannot be reached from the tab bar. */
  fun orphans(t: TabsProbe): Int {
    val groups = t.state().getOrNull(0).orEmpty()
    return t.infos(t.windows()[0]).count { info ->
      if (!info.isHidden) return@count false
      val g = groups.firstOrNull { t.nameOf(info) in (it["members"] as List<*>) } ?: return@count true
      g["collapsed"] != true || (g["members"] as List<*>).none { m -> t.hidden(m.toString()) == false }
    }
  }

  /** group / title / color / hidden lines, tab separated, per pane, of what the tab bars and the plugin show now. */
  fun snapshot(t: TabsProbe): List<String> {
    val out = ArrayList<String>()
    t.state().forEachIndexed { pane, groups ->
      for (g in groups) out.add(listOf("group", pane, g["name"], g["color"], g["collapsed"], (g["members"] as List<*>).joinToString(",")).joinToString("\t"))
    }
    t.windows().indices.forEach { pane ->
      for (tab in t.tabs(pane)) {
        out.add(listOf("tab", pane, tab["file"], tab["text"], tab["tabColor"], tab["provider"], tab["hidden"]).joinToString("\t"))
      }
    }
    return out
  }

  /** Member and non-member TabInfo colours against the provider's colour for each. Call on the EDT through [ProbeContext.edt]. */
  suspend fun colours(t: TabsProbe, members: List<String>, others: List<String>): Map<String, Any?> = t.ctx.edt {
    val m = members.associateWith { t.color(it) }
    val o = others.associateWith { t.color(it) }
    mapOf(
      "memberColorsNonNull" to m.values.all { it != null },
      "memberColorsMatchProvider" to members.all { t.color(it) == t.provider(it) },
      "nonMemberColorsDiffer" to o.values.all { it != m[members.first()] },
      "memberTabColor" to m, "nonMemberTabColor" to o, "provider" to (members + others).associateWith { t.provider(it) },
    )
  }

  /** Paints the frame and asserts that an unselected [member] label and an unselected [other] label differ in colour. */
  suspend fun pixelsDiffer(ctx: ProbeContext, t: TabsProbe, png: String, member: String, other: String, pane: Int = 0): Map<String, Any?> = ctx.edt {
    val painted = ProbeSnapshot.paint(ctx.project, ctx.out, png)
    mapOf(
      "png" to painted,
      "pixels" to listOf(mapOf("kind" to "differ", "min" to 12, "a" to mapOf("png" to png, "rect" to t.labelRect(member, pane)), "b" to mapOf("png" to png, "rect" to t.labelRect(other, pane)))),
    )
  }
}
