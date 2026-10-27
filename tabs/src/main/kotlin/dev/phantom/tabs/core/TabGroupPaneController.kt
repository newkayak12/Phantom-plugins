// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.tabs.core

import com.intellij.ide.ui.UISettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.impl.EditorTabPresentationUtil
import com.intellij.openapi.fileEditor.impl.EditorWindow
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.tabs.JBTabs
import com.intellij.ui.tabs.JBTabsEx
import com.intellij.ui.tabs.TabInfo
import com.intellij.ui.tabs.TabsListener
import java.awt.Color
import java.beans.PropertyChangeListener
import java.util.Collections
import java.util.WeakHashMap
import javax.swing.SwingConstants

/** What the first tab of a group shows besides its file name: the group's name, or the whole group when collapsed. */
data class GroupTitle(val name: String, val collapsed: Boolean, val size: Int)

/**
 * Shows the groups of one editor pane in its tab bar: colors, the group name on each group's first tab, collapsed groups
 * (only the first tab stays), and groups kept together.
 */
class TabGroupPaneController(
  val window: EditorWindow,
  val model: TabGroupModel,
  saved: PaneState? = null,
  private val onChanged: (changed: Collection<String>) -> Unit = {},
  private val isPaneSpecific: (url: String) -> Boolean = { false },
) {
  private val tabs: JBTabs
    get() = window.tabbedPane.tabs

  /** Group titles by the URL of each group's first tab. */
  var titles: Map<String, GroupTitle> = emptyMap()
    private set
  private var colors: Map<String, TabGroupColor> = emptyMap()
  private var lastOrder: List<String> = emptyList()
  private var suppressExpand = false
  private var refreshing = false
  private var applying = false

  /** The tabs [presentationListener] watches. */
  private val watched: MutableSet<TabInfo> = Collections.newSetFromMap(WeakHashMap())

  /**
   * The platform sets a tab's title and color from the providers, which answer per file and not per pane. For a file
   * shown differently in another pane they answer nothing, so whatever the platform sets there is put right again.
   */
  private val presentationListener = PropertyChangeListener { event ->
    if (applying || (event.propertyName != TabInfo.TEXT && event.propertyName != TabInfo.TAB_COLOR)) return@PropertyChangeListener
    val url = (event.source as? TabInfo)?.url ?: return@PropertyChangeListener
    if (!isPaneSpecific(url)) return@PropertyChangeListener
    ApplicationManager.getApplication().invokeLater({ applyPresentation(listOf(url)) }, { window.isDisposed })
  }

  /** Saved groups whose files are not all open yet: editors come back one by one after a restart. */
  private var pending: PaneState? = saved?.takeIf { it.groups.isNotEmpty() }
  private val restored = HashMap<Int, TabGroup>()

  init {
    tabs.addListener(object : TabsListener {
      override fun selectionChanged(oldSelection: TabInfo?, newSelection: TabInfo?) {
        if (suppressExpand || refreshing) return
        val group = newSelection?.url?.let(model::groupOf) ?: return
        if (group.collapsed) {
          group.collapsed = false
          refresh()
        }
      }

      override fun tabsMoved() {
        pending = null
        model.onMoved(lastOrder, tabs.tabs.mapNotNull { it.url }, tabs.selectedInfo?.url)
        refresh()
      }

      override fun tabRemoved(tabToRemove: TabInfo) {
        val url = tabToRemove.url ?: return
        if (model.groupOf(url) == null) return
        // the tab is being taken apart: leave its TabInfo alone
        model.remove(url)
        ApplicationManager.getApplication().invokeLater({ refresh() }, { window.isDisposed })
      }
    })
  }

  fun createGroup(files: List<VirtualFile>): TabGroup {
    pending = null
    val group = model.create(files.map { it.url }, "", model.nextColor())
    refresh()
    return group
  }

  fun addTo(file: VirtualFile, group: TabGroup) {
    pending = null
    model.add(file.url, group)
    refresh()
  }

  fun removeFromGroup(file: VirtualFile) {
    pending = null
    model.remove(file.url)
    refresh()
  }

  fun ungroup(group: TabGroup) {
    pending = null
    if (group.collapsed) {
      group.collapsed = false
      refresh()
    }
    model.ungroup(group)
    refresh()
  }

  fun closeGroup(group: TabGroup) {
    pending = null
    val files = filesOf(group)
    model.ungroup(group)
    refresh()
    for (file in files) window.manager.closeFile(file, window)
  }

  fun changed(@Suppress("UNUSED_PARAMETER") group: TabGroup) {
    refresh()
  }

  /**
   * Collapses or expands [group]. Collapsing the group of the selected tab selects the nearest tab outside it first;
   * a group holding every tab of the pane does not collapse.
   */
  fun toggleCollapsed(group: TabGroup) {
    if (!group.collapsed && !collapseSupported()) return
    if (group.collapsed) {
      group.collapsed = false
      refresh()
      return
    }
    val visible = tabs.tabs.filter { !it.isHidden }
    val outside = visible.filter { it.url !in group.members }
    if (outside.isEmpty()) return
    val selected = tabs.selectedInfo
    if (selected != null && selected.url in group.members) {
      val index = visible.indexOf(selected)
      // not the first tab of another collapsed group
      fun selectable(info: TabInfo) = info.url !in group.members && info.url?.let(model::groupOf)?.collapsed != true
      val target = visible.drop(index + 1).firstOrNull(::selectable) ?: visible.take(index).lastOrNull(::selectable) ?: return
      suppressExpand = true
      try {
        tabs.select(target, false)
      }
      finally {
        suppressExpand = false
      }
    }
    group.collapsed = true
    refresh()
  }

  /** Brings the tab bar in line with the model. Changes nothing for a pane that never had a group. */
  fun refresh() {
    if (window.isDisposed || refreshing) return
    refreshing = true
    try {
      doRefresh()
    }
    finally {
      refreshing = false
    }
  }

  private fun doRefresh() {
    val all = tabs.tabs
    for (info in all) if (watched.add(info)) info.changeSupport.addPropertyChangeListener(presentationListener)
    val open = all.mapNotNullTo(HashSet()) { it.url }
    applyPending(open)
    for (group in model.groups.toList()) {
      group.members.filter { it !in open }.forEach(model::remove)
    }
    if (model.groups.isEmpty() && titles.isEmpty() && colors.isEmpty()) {
      lastOrder = all.mapNotNull { it.url }
      return
    }

    val collapsible = collapseSupported()
    val order = model.desiredOrder(all.mapNotNull { it.url })
    // a group's first tab shows the group; the other tabs of a collapsed group are hidden
    val anchors = model.groups.associateWith { group -> order.first { it in group.members } }
    // the selected tab is never hidden: its collapsed group opens (a tab just added to it, or a group being restored)
    val selectedGroup = tabs.selectedInfo?.url?.let(model::groupOf)
    if (selectedGroup != null && selectedGroup.collapsed && anchors[selectedGroup] != tabs.selectedInfo?.url) {
      selectedGroup.collapsed = false
    }

    for (info in all) {
      val url = info.url ?: continue
      val group = model.groupOf(url)
      val hidden = collapsible && group != null && group.collapsed && anchors[group] != url
      // TabInfo fires a change, and JBTabs relayouts, even when the value does not change
      if (info.isHidden != hidden) info.isHidden = hidden
    }
    // hidden tabs come back at stale places, so sort after hiding and showing
    if (collapsible) {
      val position = order.withIndex().associate { it.value to it.index }
      (tabs as? JBTabsEx)?.sortTabs(compareBy<TabInfo> { position[it.url] ?: Int.MAX_VALUE })
    }
    val oldTitles = titles
    titles = anchors.entries.associate { (group, url) -> url to GroupTitle(group.name, collapsible && group.collapsed, group.members.size) }

    val newColors = HashMap<String, TabGroupColor>()
    for (group in model.groups) group.members.forEach { newColors[it] = group.color }
    val changed = (colors.keys + newColors.keys).filterTo(HashSet()) { colors[it] != newColors[it] }
    colors = newColors
    (oldTitles.keys + titles.keys).filterTo(changed) { oldTitles[it] != titles[it] }
    // the service updates the tabs of these files in every pane
    onChanged(changed)
    lastOrder = tabs.tabs.mapNotNull { it.url }
  }

  /** The group color of [url] in this pane. */
  fun colorOf(url: String): TabGroupColor? = colors[url]

  /**
   * Sets the title and background of this pane's tabs of [urls] to what they show here: what the providers give, or, for
   * a file shown differently in another pane, this pane's group.
   */
  fun applyPresentation(urls: Collection<String>) {
    if (window.isDisposed || applying) return
    val project = window.manager.project
    applying = true
    try {
      for (info in tabs.tabs) {
        val file = info.`object` as? VirtualFile ?: continue
        if (file.url !in urls || !file.isValid) continue
        val (text, color) = ReadAction.computeBlocking<Pair<String, Color?>, Throwable> {
          if (isPaneSpecific(file.url)) {
            val base = TabGroupTitleProvider.baseTitle(project, file)
            (titles[file.url]?.text { base } ?: base) to
              (colors[file.url]?.soft ?: EditorTabPresentationUtil.getEditorTabBackgroundColor(project, file))
          }
          else {
            EditorTabPresentationUtil.getEditorTabTitle(project, file) to EditorTabPresentationUtil.getEditorTabBackgroundColor(project, file)
          }
        }
        if (info.text != text) info.setText(text)
        if (info.tabColor != color) info.setTabColor(color)
      }
    }
    finally {
      applying = false
    }
  }

  private var unhiddenForClose = false

  /**
   * [file] is about to close. The platform cannot close a tab while others are hidden: it picks the tab to select next
   * by an index that counts visible tabs only, against a list of all tabs, so it can pick a hidden tab or the wrong one
   * (and report an error). So the hidden tabs are shown until [afterClose], which hides them again in the same event.
   * When the closing tab is selected, a visible tab is selected first: the previously selected one, else a neighbour on
   * the side the platform would take, preferring tabs outside collapsed groups.
   */
  fun beforeClose(file: VirtualFile) {
    if (window.isDisposed || tabs.tabs.none { it.isHidden }) return
    val selected = tabs.selectedInfo
    if (selected != null && selected.url == file.url) {
      val previous = selected.previousSelection?.takeIf { it !== selected && it in tabs.tabs && !it.isHidden }
      val visible = tabs.tabs.filter { !it.isHidden }
      val index = visible.indexOf(selected)
      val right = visible.drop(index + 1)
      val left = visible.take(maxOf(index, 0)).asReversed()
      val sides = if (UISettings.getInstance().activeRightEditorOnClose) right + left else left + right
      val target = previous ?: sides.firstOrNull { it.url?.let(model::groupOf)?.collapsed != true } ?: sides.firstOrNull()
      if (target != null) {
        suppressExpand = true
        try {
          tabs.select(target, false)
        }
        finally {
          suppressExpand = false
        }
      }
    }
    unhiddenForClose = true
    refreshing = true
    try {
      tabs.tabs.filter { it.isHidden }.forEach { it.isHidden = false }
    }
    finally {
      refreshing = false
    }
  }

  /** A file closed: hides again what [beforeClose] showed. */
  fun afterClose() {
    if (!unhiddenForClose) return
    unhiddenForClose = false
    refresh()
  }

  /** Gives a controller made before its pane was known the saved groups of that pane, unless it has groups already. */
  fun bindSaved(saved: PaneState?) {
    if (pending != null || model.groups.isNotEmpty()) return
    pending = saved?.takeIf { it.groups.isNotEmpty() }
    refresh()
  }

  private fun applyPending(open: Set<String>) {
    val state = pending ?: return
    var missing = false
    state.groups.forEachIndexed { index, saved ->
      if (saved.urls.any { it !in open }) missing = true
      val urls = saved.urls.filter { it in open && model.groupOf(it) == null }
      if (urls.isEmpty()) return@forEachIndexed
      val group = restored[index]?.takeIf { it in model.groups }
      if (group == null) {
        val color = TabGroupColor.entries.firstOrNull { it.name == saved.color } ?: TabGroupColor.GREY
        restored[index] = model.create(urls, saved.name.orEmpty(), color).also { it.collapsed = saved.collapsed }
      }
      else {
        urls.forEach { model.add(it, group) }
      }
    }
    if (!missing) pending = null
  }

  private fun filesOf(group: TabGroup): List<VirtualFile> =
    tabs.tabs.mapNotNull { it.`object` as? VirtualFile }.filter { it.url in group.members }
}

private val TabInfo.url: String?
  get() = (`object` as? VirtualFile)?.url

/**
 * Collapsing needs the default tab bar: one scrolling row on top, tabs in the order they are put in. Otherwise groups only
 * color their tabs and name their first tab.
 */
internal fun collapseSupported(): Boolean {
  val settings = UISettings.getInstance()
  return settings.editorTabPlacement == SwingConstants.TOP && settings.scrollTabLayoutInEditor && !settings.sortTabsAlphabetically
}
