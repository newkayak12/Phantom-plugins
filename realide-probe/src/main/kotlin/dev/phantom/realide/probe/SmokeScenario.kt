// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.realide.probe

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.BuildNumber
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.tabs.impl.TabLabel
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import java.awt.AWTEvent
import java.awt.event.AWTEventListener
import java.awt.event.MouseEvent
import java.awt.Toolkit
import java.awt.Component
import java.awt.Container
import java.awt.Point
import javax.swing.MenuSelectionManager

/** The plugin under test is loaded and enabled, the project frame is showing, and one PNG of it is painted. */
internal object SmokeScenario : ProbeScenario {
  override suspend fun run(ctx: ProbeContext) {
    val id = PluginId.getId(ctx.pluginId)
    ctx.report.step(
      "plugin.loaded",
      expected = mapOf("loaded" to true, "enabled" to true),
      observed = ctx.edt {
        val d = PluginManagerCore.getPlugin(id)
        mapOf("loaded" to (d != null), "enabled" to (d != null && !PluginManagerCore.isDisabled(id)), "plugin" to ctx.pluginId, "version" to d?.version)
      },
    )
    // what the platform itself makes of the plugin's since/until against this IDE: the same rule an installed copy meets
    ctx.report.step(
      "plugin.compatible",
      expected = mapOf("sinceAdmits" to true, "untilAdmits" to true, "incompatible" to false),
      observed = ctx.edt {
        val d = PluginManagerCore.getPlugin(id)
        val build = ApplicationInfo.getInstance().build
        val since = d?.sinceBuild?.let { BuildNumber.fromString(it) }
        val until = d?.untilBuild?.let { BuildNumber.fromString(it) }
        mapOf(
          "build" to build.asString(),
          "pluginsBuild" to PluginManagerCore.buildNumber.asString(),
          "compatibleBuildOverride" to System.getProperty("idea.plugins.compatible.build"),
          "since" to d?.sinceBuild, "until" to d?.untilBuild,
          "incompatible" to (d != null && PluginManagerCore.isIncompatible(d)),
          "nonLoadReason" to (d?.let { PluginManagerCore.checkBuildNumberCompatibility(it, PluginManagerCore.buildNumber)?.toString() }),
          "ignoreCompatibility" to PluginManagerCore.isIgnoreCompatibility,
          "sinceAdmits" to (since == null || since <= PluginManagerCore.buildNumber), "untilAdmits" to (until == null || PluginManagerCore.buildNumber <= until),
        )
      },
    )
    val showing = ctx.waitUntil { ProbeSnapshot.frame(ctx.project)?.let { it.isShowing && it.rootPane.width > 0 } == true }
    ctx.report.step(
      "frame.showing",
      expected = mapOf("showing" to true, "projectOpen" to true),
      observed = ctx.edt { mapOf("showing" to showing, "projectOpen" to !ctx.project.isDisposed) + ProbeSnapshot.bounds(ctx.project) },
    )
    // input path S2/S3 rely on: two editors open, a TabLabel right-clicked through the event queue, the real popup found and read
    val files = listOf("a.txt", "b.txt").map { ctx.fixtures.resolve("alpha").resolve(it) }
    val opened = ctx.edt {
      val fem = FileEditorManager.getInstance(ctx.project)
      files.mapNotNull { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(it) }.forEach { fem.openFile(it, true) }
      fem.openFiles.size
    }
    val tabs = ctx.waitUntil { tabLabels(ctx).size >= 2 }
    ctx.report.step(
      "editor.tabs",
      expected = mapOf("opened" to 2, "tabLabels" to 2),
      observed = ctx.edt { mapOf("opened" to opened, "tabLabels" to tabLabels(ctx).size, "texts" to tabLabels(ctx).map { it.info.text }, "waited" to tabs) },
    )
    val focus = ctx.edt { ProbeInput.focus(ctx.project) }
    val seen = java.util.concurrent.CopyOnWriteArrayList<String>()
    val spy = AWTEventListener { e -> seen.add("${e.id}@${e.source.javaClass.simpleName}") }
    Toolkit.getDefaultToolkit().addAWTEventListener(spy, AWTEvent.MOUSE_EVENT_MASK)
    ctx.edt { tabLabels(ctx).firstOrNull()?.let { ProbeInput.popupTrigger(it, Point(it.width / 2, it.height / 2)) } }
    val viaEvent = ctx.waitUntil(if (focus.active) 15_000 else 3_000) { ProbeInput.openPopup() != null }
    Toolkit.getDefaultToolkit().removeAWTEventListener(spy)
    // the platform opens a tab popup from a right-click only in an active app; when the app is not active (env) only
    // delivery is asserted here and the outcome is recorded, S2/S3 gate on ProbeInput.focus() the same way
    ctx.report.step(
      "tab.popup.event",
      expected = mapOf("via" to ProbeInput.VIA_QUEUE, "delivered" to true),
      observed = mapOf(
        "via" to ProbeInput.VIA_QUEUE, "delivered" to seen.any { it.startsWith("${MouseEvent.MOUSE_RELEASED}@") },
        "popupOpened" to viaEvent, "env" to focus.env,
      ) + focus.observed(),
    )
    if (viaEvent) ctx.edt { MenuSelectionManager.defaultManager().clearSelectedPath(); ProbeInput.openPopup()?.isVisible = false }
    // the same EditorTabPopupMenu the tab builds, shown by the platform: found, read and clickable through the helpers
    val popupObserved = ctx.edt {
      val label = tabLabels(ctx).first()
      val group = ActionManager.getInstance().getAction("EditorTabPopupMenu") as ActionGroup
      ActionManager.getInstance().createActionPopupMenu("EditorTabPopup", group).component.show(label, 5, 5)
      emptyMap<String, Any?>()
    }
    val found = ctx.waitUntil { ProbeInput.openPopup() != null }
    ctx.report.step(
      "tab.popup",
      expected = mapOf("popup" to true, "hasItems" to true, "doClickReachable" to true, "tabsMenuItem" to true),
      observed = ctx.edt {
        val popup = ProbeInput.openPopup()
        val items = popup?.let { ProbeInput.items(it) }.orEmpty()
        mapOf(
          // shown by createActionPopupMenu(...).show, not from a right-click: never labelled popup.doClick
          "via" to ProbeInput.VIA_PROGRAMMATIC, "popup" to (popup != null), "hasItems" to items.isNotEmpty(), "items" to items.size,
          "doClickReachable" to items.any { it.isEnabled && it.text != null }, "waited" to found,
          // an item the plugin under test contributes shows its action group is live in the editor tab menu
          "tabsMenuItem" to (ctx.pluginId != "dev.phantom.tabs" || items.any { it.text == "Add Tab to New Group" }),
          "itemTexts" to items.mapNotNull { it.text }.take(30),
        ) + popupObserved
      },
    )
    ctx.edt { MenuSelectionManager.defaultManager().clearSelectedPath(); ProbeInput.openPopup()?.isVisible = false }
    ctx.report.step(
      "tab.popup.closed",
      expected = mapOf("popup" to false),
      observed = ctx.waitUntil { ProbeInput.openPopup() == null }.let { mapOf("popup" to !it) },
    )
    // the path S2/S3 use: a right-click posted through the event queue opens the platform's popup (its handlePopup binds
    // the clicked tab), and an item is run by JMenuItem.doClick: Pin Tab on a.txt, observed, then unpinned
    val target = ctx.edt { tabLabels(ctx).firstOrNull { (it.info.`object` as? com.intellij.openapi.vfs.VirtualFile)?.name == "a.txt" } }
    val attempt = target?.let { ProbePopup.attempt(ctx, it, "label.pressTrigger", waitMs = 4000) }
    val popup = ctx.edt { ProbeInput.openPopup() }
    val click = popup?.let { ProbePopup.click(ctx, it, listOf("Pin Tab")) }
    val window = ctx.edt { com.intellij.openapi.fileEditor.ex.FileEditorManagerEx.getInstanceEx(ctx.project).currentWindow }
    val aFile = ctx.edt { (target?.info?.`object` as? com.intellij.openapi.vfs.VirtualFile) }
    val pinned = ctx.waitUntil(4000) { aFile != null && window?.isFilePinned(aFile) == true }
    ctx.edt { ProbePopup.closeAll(); if (aFile != null && pinned) window?.setFilePinned(aFile, false) }
    ctx.report.step(
      "tab.popup.doClick",
      expected = mapOf("via" to ProbeInput.VIA_POPUP, "opened" to true, "pinned" to true),
      observed = mapOf(
        "via" to (if (attempt?.get("opened") == true && click?.get("clicked") == true) ProbeInput.VIA_POPUP else "none"),
        "opened" to (attempt?.get("opened") == true), "pinned" to pinned, "click" to click, "attempt" to attempt,
      ),
    )
    ctx.report.step(
      "paint.png",
      expected = mapOf("written" to true),
      observed = ctx.edt { ProbeSnapshot.paint(ctx.project, ctx.out, "smoke-alpha.png") },
    )
  }

  private fun tabLabels(ctx: ProbeContext): List<TabLabel> {
    val root = ProbeSnapshot.frame(ctx.project)?.rootPane ?: return emptyList()
    val out = ArrayList<TabLabel>()
    fun walk(c: Component) {
      if (c is TabLabel && c.isShowing) out.add(c)
      if (c is Container) c.components.forEach(::walk)
    }
    walk(root)
    return out
  }
}
