// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.tabs.core

import com.intellij.ide.ui.UISettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditorManagerKeys
import com.intellij.openapi.fileEditor.impl.EditorTabPresentationUtil
import com.intellij.openapi.fileEditor.impl.EditorWindow
import com.intellij.openapi.ui.AbstractPainter
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.IdeGlassPaneUtil
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.tabs.JBTabs
import com.intellij.ui.tabs.JBTabsEx
import com.intellij.ui.tabs.TabInfo
import com.intellij.ui.tabs.TabsListener
import com.intellij.ui.tabs.impl.TabLabel
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Component
import java.awt.EventQueue
import java.awt.Font
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import java.beans.PropertyChangeListener
import java.util.Collections
import java.util.WeakHashMap
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

/** What the first tab of a group shows besides its file name: the group's name, or the whole group when collapsed. */
data class GroupTitle(val name: String, val collapsed: Boolean, val size: Int)

/**
 * Shows the groups of one editor pane in its tab bar, as one piece each: a line in the group's color under all its tabs,
 * a faint background in that color, the group's name in that color and bold on its first tab, collapsed groups (only
 * the first tab stays), and groups kept together.
 */
class TabGroupPaneController(
  val window: EditorWindow,
  val model: TabGroupModel,
  saved: PaneState? = null,
  private val onChanged: (changed: Collection<String>) -> Unit = {},
  private val isPaneSpecific: (url: String) -> Boolean = { false },
  parent: Disposable? = null,
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
    val info = event.source as? TabInfo ?: return@PropertyChangeListener
    val url = info.url ?: return@PropertyChangeListener
    if (isPaneSpecific(url)) {
      ApplicationManager.getApplication().invokeLater({ applyPresentation(listOf(url)) }, { window.isDisposed })
    }
    // the platform sets the provider's title as plain text; the group's name in it is colored again
    else if (event.propertyName == TabInfo.TEXT && url in titles) {
      ApplicationManager.getApplication().invokeLater({ styleName(info) }, { window.isDisposed })
    }
  }

  /** Tab labels [chipClick] listens to. */
  private val clickable: MutableSet<Component> = Collections.newSetFromMap(WeakHashMap())
  private var expandedBySelection: TabGroup? = null
  private var pressed: Pair<TabGroup, Boolean>? = null
  private var movedSincePress = false
  /** The tabs shown when a tab was last pressed: where a tab dragged out of the tab bar came from. */
  private var orderAtPress: List<String> = emptyList()

  /** Tabs this pane hid: the other tabs of collapsed groups. The platform hides a tab while it is dragged out of the bar. */
  private val hiddenHere = HashSet<String>()

  /**
   * Grouped tabs dragged out of the tab bar and dropped: the platform closes such a tab and opens it again where it was
   * dropped, after the event. Until then the tab keeps its group; dropped back into this pane it was moved, as by a drag
   * along the bar, and dropped elsewhere it leaves the group.
   */
  private val reopening = HashMap<String, Reopening>()

  private class Reopening(val file: VirtualFile, val before: List<String>, val byChip: Boolean, val openElsewhere: Set<EditorWindow>)

  /**
   * A click on a group's chip (its name on the first tab) collapses the group, or opens it when collapsed, as in Chrome.
   * The tab bar selects the tab on press, which opens a collapsed group; that click then leaves it open.
   */
  private val chipClick = object : MouseAdapter() {
    override fun mousePressed(e: MouseEvent) {
      val group = chipAt(e)?.takeIf { SwingUtilities.isLeftMouseButton(e) }
      pressed = group?.let { it to (it.collapsed || expandedBySelection === it) }
      movedSincePress = false
      if (SwingUtilities.isLeftMouseButton(e)) orderAtPress = visibleOrder()
    }

    override fun mouseReleased(e: MouseEvent) {
      val (group, wasCollapsed) = pressed ?: return
      // the tab bar may drop the dragged tab after this listener: the press stays known to tabsMoved until then
      ApplicationManager.getApplication().invokeLater { pressed = null }
      if (movedSincePress || !SwingUtilities.isLeftMouseButton(e) || chipAt(e) !== group) return
      ApplicationManager.getApplication().invokeLater({
        if (group !in model.groups) return@invokeLater
        if (!wasCollapsed && !group.collapsed) toggleCollapsed(group)
        else if (wasCollapsed && group.collapsed) toggleCollapsed(group)
      }, { window.isDisposed })
    }
  }

  /** The group whose chip is under the mouse, if any. */
  private fun chipAt(e: MouseEvent): TabGroup? {
    val label = e.component as? TabLabel ?: return null
    val info = label.info
    val url = info.url ?: return null
    if (url !in titles) return null
    val text = label.labelComponent as? SimpleColoredComponent ?: return null
    val x = SwingUtilities.convertPoint(label, e.point, text).x
    return if (text.findFragmentAt(x) == 0) model.groupOf(url) else null
  }

  /** Draws each group's line under its tabs and its chip on its first tab, over the tab bar. */
  private val linePainter = object : AbstractPainter() {
    override fun needsRepaint(): Boolean = model.groups.isNotEmpty()
    override fun executePaint(component: Component, g: Graphics2D) = paintLines(component, g)
  }

  /** Saved groups whose files are not all open yet: editors come back one by one after a restart. */
  private var pending: PaneState? = saved?.takeIf { it.groups.isNotEmpty() }
  private val restored = HashMap<Int, TabGroup>()

  init {
    if (parent != null) IdeGlassPaneUtil.installPainter(tabs.component, linePainter, parent)
    tabs.addListener(object : TabsListener {
      override fun selectionChanged(oldSelection: TabInfo?, newSelection: TabInfo?) {
        if (suppressExpand || refreshing) return
        // a tab dropped back after a drag out of the bar is selected before it counts as moved: it keeps its group closed
        if (newSelection?.url in reopening) return
        val group = newSelection?.url?.let(model::groupOf) ?: return
        // pressed on its chip: the release opens it, a drag moves it closed
        val event = EventQueue.getCurrentEvent() as? MouseEvent
        if (event != null && event.id == MouseEvent.MOUSE_PRESSED && chipAt(event) === group) return
        if (group.collapsed) {
          group.collapsed = false
          // a click on the chip selected the tab: it has opened the group, so the click must not close it again
          expandedBySelection = group
          ApplicationManager.getApplication().invokeLater { expandedBySelection = null }
          refresh()
        }
      }

      override fun tabsMoved() {
        pending = null
        val byChip = pressed != null
        movedSincePress = true
        model.onMoved(lastOrder, visibleOrder(), tabs.selectedInfo?.url, byChip)
        refresh()
      }

      override fun tabRemoved(tabToRemove: TabInfo) {
        val file = tabToRemove.`object` as? VirtualFile ?: return
        val url = file.url
        hiddenHere.remove(url)
        if (model.groupOf(url) == null) return
        if (file.getUserData(FileEditorManagerKeys.CLOSING_TO_REOPEN) == true) {
          val before = orderAtPress.takeIf { url in it } ?: lastOrder
          val elsewhere = window.manager.windows.filterTo(HashSet()) { it !== window && it.isFileOpen(file) }
          reopening[url] = Reopening(file, before, pressed != null, elsewhere)
          return
        }
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
    for (info in all) {
      if (watched.add(info)) info.changeSupport.addPropertyChangeListener(presentationListener)
      val label = tabs.getTabLabel(info)
      if (label != null && clickable.add(label)) label.addMouseListener(chipClick)
    }
    val open = all.mapNotNullTo(HashSet()) { it.url }
    applyPending(open)
    resolveReopened(open)
    for (group in model.groups.toList()) {
      group.members.filter { it !in open && it !in reopening }.forEach(model::remove)
    }
    if (model.groups.isEmpty() && titles.isEmpty() && colors.isEmpty()) {
      lastOrder = visibleOrder()
      return
    }

    val collapsible = collapseSupported()
    val order = model.desiredOrder(currentOrder())
    // a group's first tab shows the group; the other tabs of a collapsed group are hidden
    // a group whose only open tab is being dropped has no tab yet
    val anchors = model.groups.mapNotNull { group -> order.firstOrNull { it in group.members }?.let { group to it } }.toMap()
    // the selected tab is never hidden: its collapsed group opens (a tab just added to it, or a group being restored)
    val selectedGroup = tabs.selectedInfo?.url?.let(model::groupOf)
    if (selectedGroup != null && selectedGroup.collapsed && anchors[selectedGroup] != tabs.selectedInfo?.url) {
      selectedGroup.collapsed = false
    }

    for (info in all) {
      val url = info.url ?: continue
      val group = model.groupOf(url)
      val hidden = collapsible && group != null && group.collapsed && anchors[group] != url
      // TabInfo fires a change, and JBTabs relayouts, even when the value does not change; a tab hidden by the platform
      // (dragged out of the bar) is left hidden
      if (info.isHidden != hidden && (hidden || url in hiddenHere)) info.isHidden = hidden
      if (hidden) hiddenHere.add(url) else hiddenHere.remove(url)
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
    lastOrder = visibleOrder()
    for (info in tabs.tabs) if (info.url in titles) styleName(info)
    tabs.component.repaint()
  }

  /** Tabs dragged out of the bar ([reopening]) that are open again: here they moved, in another pane they left the group. */
  private fun resolveReopened(open: Set<String>) {
    for ((url, reopened) in reopening.toList()) {
      if (url in open) {
        reopening.remove(url)
        // the tabs shown in both: meanwhile another tab of a collapsed group may show in place of the dragged one
        val after = visibleOrder()
        model.onMoved(reopened.before.filter { it in after }, after.filter { it in reopened.before }, url, reopened.byChip)
      }
      else if (window.manager.windows.any { it !== window && it !in reopened.openElsewhere && it.isFileOpen(reopened.file) }) {
        reopening.remove(url)
        model.remove(url)
      }
    }
  }

  /** The tabs the tab bar shows, in order: what a drag moves. */
  private fun visibleOrder(): List<String> = tabs.tabs.filter { !it.isHidden }.mapNotNull { it.url }

  /**
   * The pane's tabs in order, the hidden tabs of each collapsed group right after its first tab. The tab bar puts a hidden
   * tab back in its list at the index it had when hidden, which goes stale as tabs move, and the hidden tabs in no fixed
   * order: there, a tab dropped beside a collapsed group could land between its hidden tabs and join it.
   */
  private fun currentOrder(): List<String> {
    val all = tabs.tabs
    val hidden = all.filter { it.isHidden }.mapNotNullTo(LinkedHashSet()) { it.url }
    val result = ArrayList<String>(all.size)
    for (info in all) {
      if (info.isHidden) continue
      val url = info.url ?: continue
      result.add(url)
      for (member in model.groupOf(url)?.members.orEmpty()) if (hidden.remove(member)) result.add(member)
    }
    result.addAll(hidden)
    return result
  }

  /**
   * The group's name on its first tab in the group's color and bold, the tab's own title after it as it was: the
   * provider's plain `Name · Title` becomes `Name  Title`, a collapsed group's `▸ Name (3)` becomes `Name  3`. The name is
   * then drawn over as a chip ([paintChip]).
   */
  private fun styleName(info: TabInfo) {
    val url = info.url ?: return
    val title = titles[url] ?: return
    val color = colors[url]?.strong ?: return
    val text = info.coloredText.toString()
    val name = title.name.ifEmpty { if (title.collapsed) PhantomTabsBundle.message("group.unnamed") else return }
    val nameStyle = SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, color)
    val rest = SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, info.defaultForeground)
    val fragments = if (title.collapsed) {
      if (text != title.text { "" } && text != "$name  ${title.size}") return
      // the chip, then how many tabs it holds
      listOf(name to nameStyle, "  ${title.size}" to rest)
    }
    else {
      val prefix = PhantomTabsBundle.message("tab.title.expanded", name, "")
      if (!text.startsWith(prefix)) return
      listOf(name to nameStyle, "  " + text.removePrefix(prefix) to rest)
    }
    if (info.coloredText.texts == fragments.map { it.first } && info.coloredText.attributes == fragments.map { it.second }) return
    applying = true
    try {
      info.clearText(false)
      for ((fragment, style) in fragments) info.append(fragment, style)
    }
    finally {
      applying = false
    }
  }

  private fun paintLines(component: Component, g: Graphics2D) {
    if (window.isDisposed || !component.isShowing) return
    val height = JBUI.scale(3)
    val inset = JBUI.scale(4)
    val clip = Rectangle(0, 0, component.width, component.height)
    for (group in model.groups) {
      val labels = tabs.tabs
        .filter { !it.isHidden && it.url in group.members }
        .mapNotNull { info -> tabs.getTabLabel(info)?.takeIf { it.isShowing && it.width > 0 } }
      if (labels.isEmpty()) continue
      val boxes = labels.map { SwingUtilities.convertRectangle(it.parent, it.bounds, component) }
      // labels in one row: from the left of the first to the right of the last, along their bottom
      val left = boxes.minOf { it.x } + inset
      val right = boxes.maxOf { it.x + it.width } - inset
      val bottom = boxes.maxOf { it.y + it.height }
      val line = Rectangle(left, bottom - height, right - left, height).intersection(clip)
      if (line.isEmpty) continue
      val g2 = g.create() as Graphics2D
      try {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = group.color.strong
        g2.fill(RoundRectangle2D.Float(line.x.toFloat(), line.y.toFloat(), line.width.toFloat(), line.height.toFloat(), height.toFloat(), height.toFloat()))
        paintChip(component, g2, group, labels.first())
      }
      finally {
        g2.dispose()
      }
    }
  }

  /** The group's name on its first tab as a chip: filled with the group's color, the name in a color that reads on it. */
  private fun paintChip(component: Component, g: Graphics2D, group: TabGroup, label: Component) {
    val tabLabel = label as? TabLabel ?: return
    val text = tabLabel.labelComponent as? SimpleColoredComponent ?: return
    if (tabLabel.info.url !in titles) return
    val name = tabLabel.info.coloredText.texts.firstOrNull() ?: return
    // where the first fragment is: the x range findFragmentAt gives it
    var start = -1
    var end = -1
    for (x in 0 until text.width) {
      if (text.findFragmentAt(x) == 0) {
        if (start < 0) start = x
        end = x + 1
      }
      else if (start >= 0) break
    }
    if (start < 0) return
    val font = text.font.deriveFont(Font.BOLD)
    val metrics = text.getFontMetrics(font)
    val pad = JBUI.scale(5)
    val chipHeight = metrics.height + JBUI.scale(2)
    val origin = SwingUtilities.convertPoint(text, start, (text.height - chipHeight) / 2, component)
    val width = maxOf(end - start, metrics.stringWidth(name))
    val chip = RoundRectangle2D.Float((origin.x - pad).toFloat(), origin.y.toFloat(), (width + 2 * pad).toFloat(), chipHeight.toFloat(),
                                      chipHeight.toFloat() / 2, chipHeight.toFloat() / 2)
    g.color = group.color.strong
    g.fill(chip)
    g.color = group.color.onStrong
    g.font = font
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    g.drawString(name, origin.x, origin.y + (chipHeight - metrics.height) / 2 + metrics.ascent)
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
        styleName(info)
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
      hiddenHere.clear()
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
