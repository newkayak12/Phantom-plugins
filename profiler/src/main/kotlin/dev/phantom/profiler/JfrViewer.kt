// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.profiler

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.JBSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBLoadingPanel
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.table.JBTable
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.event.MouseEvent
import java.beans.PropertyChangeListener
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.event.TreeModelListener
import javax.swing.table.AbstractTableModel
import javax.swing.tree.TreeModel
import javax.swing.tree.TreePath

object JfrFileType : FileType {
  override fun getName(): String = "JFR"
  override fun getDescription(): String = PhantomProfilerBundle.message("filetype.description")
  override fun getDefaultExtension(): String = "jfr"
  override fun getIcon(): Icon = AllIcons.Actions.Profile
  override fun isBinary(): Boolean = true
  override fun isReadOnly(): Boolean = true
}

/** Opens `.jfr` recordings in the profiler view instead of as an unknown binary file. */
class JfrEditorProvider : FileEditorProvider, DumbAware {
  override fun accept(project: Project, file: VirtualFile): Boolean = file.extension.equals("jfr", ignoreCase = true)
  override fun acceptRequiresReadAction(): Boolean = false
  override fun createEditor(project: Project, file: VirtualFile): FileEditor = JfrEditor(project, file)
  override fun getEditorTypeId(): String = "phantom-jfr"
  override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

/** Reads the recording off the UI thread, then shows Flame Graph, Call Tree, Methods, Allocations and Summary. */
class JfrEditor(private val project: Project, private val file: VirtualFile) : UserDataHolderBase(), FileEditor {
  private val loading = JBLoadingPanel(BorderLayout(), this)

  init {
    loading.startLoading()
    ApplicationManager.getApplication().executeOnPooledThread {
      val result = runCatching {
        val profile = JfrProfile.read(file.toNioPath())
        val classes = HashSet<String>()
        collectClasses(profile.cpu, classes)
        // expireWith: closing the editor cancels the lookup instead of resolving thousands of classes for nobody
        val own = ReadAction.nonBlocking<Set<String>> { FrameNavigation.projectClasses(project, classes) }
          .expireWith(this).executeSynchronously()
        profile to own
      }
      ApplicationManager.getApplication().invokeLater({
        loading.stopLoading()
        result.onSuccess { (profile, own) -> loading.add(content(profile, own), BorderLayout.CENTER) }
          .onFailure { loading.add(JBLabel(PhantomProfilerBundle.message("viewer.failed", it.message ?: it.javaClass.simpleName)), BorderLayout.CENTER) }
        loading.revalidate()
      }, { Disposer.isDisposed(this) || project.isDisposed })
    }
  }

  private fun collectClasses(node: CallNode, into: MutableSet<String>) {
    node.frame?.let { into.add(it.className) }
    node.children.forEach { collectClasses(it, into) }
  }

  private fun content(profile: JfrProfile, own: Set<String>): JComponent {
    val navigate: (Frame) -> Unit = { FrameNavigation.navigate(project, it) }
    val samples: (Long) -> String = { PhantomProfilerBundle.message("unit.samples", it) }
    val bytes: (Long) -> String = { StringUtil.formatFileSize(it) }
    val tabs = JBTabbedPane()
    tabs.addTab(PhantomProfilerBundle.message("tab.flame"), flame(profile.cpu, samples, own, navigate))
    tabs.addTab(PhantomProfilerBundle.message("tab.tree"), ScrollPaneFactory.createScrollPane(callTree(profile.cpu, samples, navigate)))
    tabs.addTab(PhantomProfilerBundle.message("tab.methods"), ScrollPaneFactory.createScrollPane(methods(profile, navigate)))
    tabs.addTab(PhantomProfilerBundle.message("tab.allocations"), JBSplitter(false, 0.6f).apply {
      firstComponent = flame(profile.allocations, bytes, own, navigate)
      secondComponent = ScrollPaneFactory.createScrollPane(allocationTable(profile.allocationsByClass))
    })
    tabs.addTab(PhantomProfilerBundle.message("tab.summary"), JPanel(BorderLayout()).apply {
      border = JBUI.Borders.empty(12)
      add(JBLabel(summary(profile.summary)).apply { verticalAlignment = JBLabel.TOP }, BorderLayout.NORTH)
    })
    if (profile.cpu.total == 0L) tabs.selectedIndex = tabs.tabCount - 1
    return tabs
  }

  private fun flame(root: CallNode, unit: (Long) -> String, own: Set<String>, navigate: (Frame) -> Unit): JComponent =
    if (root.total == 0L) JBLabel(PhantomProfilerBundle.message("viewer.empty"), JBLabel.CENTER)
    else ScrollPaneFactory.createScrollPane(FlameGraph(root, unit, { it in own }, navigate))

  private fun callTree(root: CallNode, unit: (Long) -> String, navigate: (Frame) -> Unit): JTree {
    val tree = Tree(CallTreeModel(root))
    tree.isRootVisible = false
    tree.showsRootHandles = true
    tree.cellRenderer = object : ColoredTreeCellRenderer() {
      override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
        val node = value as? CallNode ?: return
        val frame = node.frame ?: return
        append("%5.1f%%  ".format(node.total * 100.0 / root.total), SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES)
        append(frame.shortName)
        append("  ${frame.packageName}  ${unit(node.total)}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
      }
    }
    // open the heaviest path, which is usually where the time went
    var path = TreePath(root)
    var node = root
    while (node.children.isNotEmpty() && node.children.first().total * 2 >= node.total && path.pathCount < 40) {
      node = node.children.first()
      path = path.pathByAddingChild(node)
    }
    tree.expandPath(path.parentPath ?: path)
    object : DoubleClickListener() {
      override fun onDoubleClick(event: MouseEvent): Boolean {
        (tree.selectionPath?.lastPathComponent as? CallNode)?.frame?.let(navigate) ?: return false
        return true
      }
    }.installOn(tree)
    return tree
  }

  private fun methods(profile: JfrProfile, navigate: (Frame) -> Unit): JBTable {
    val all = profile.cpu.total.coerceAtLeast(1)
    val rows = profile.hotMethods
    val columns = listOf("column.method", "column.self.percent", "column.self", "column.total.percent", "column.total").map(PhantomProfilerBundle::message)
    val table = JBTable(object : AbstractTableModel() {
      override fun getRowCount() = rows.size
      override fun getColumnCount() = columns.size
      override fun getColumnName(column: Int) = columns[column]
      override fun getColumnClass(column: Int): Class<*> = if (column == 0) String::class.java else java.lang.Double::class.java
      override fun getValueAt(row: Int, column: Int): Any = rows[row].let { m ->
        when (column) {
          0 -> "${m.frame.shortName}  (${m.frame.packageName})"
          1 -> m.self * 100.0 / all
          2 -> m.self.toDouble()
          3 -> m.total * 100.0 / all
          else -> m.total.toDouble()
        }
      }
    })
    table.autoCreateRowSorter = true
    table.columnModel.getColumn(0).preferredWidth = JBUI.scale(520)
    object : DoubleClickListener() {
      override fun onDoubleClick(event: MouseEvent): Boolean {
        val row = table.selectedRow.takeIf { it >= 0 } ?: return false
        navigate(rows[table.convertRowIndexToModel(row)].frame)
        return true
      }
    }.installOn(table)
    return table
  }

  private fun allocationTable(byClass: List<Pair<String, Long>>): JBTable {
    val total = byClass.sumOf { it.second }.coerceAtLeast(1)
    val columns = listOf("column.class", "column.bytes", "column.share").map(PhantomProfilerBundle::message)
    return JBTable(object : AbstractTableModel() {
      override fun getRowCount() = byClass.size
      override fun getColumnCount() = columns.size
      override fun getColumnName(column: Int) = columns[column]
      override fun getValueAt(row: Int, column: Int): Any = byClass[row].let { (name, size) ->
        when (column) {
          0 -> name
          1 -> StringUtil.formatFileSize(size)
          else -> "%.1f%%".format(size * 100.0 / total)
        }
      }
    }).apply { columnModel.getColumn(0).preferredWidth = JBUI.scale(360) }
  }

  private fun summary(s: JfrSummary): String {
    fun ms(d: java.time.Duration) = "${d.toMillis()} ms"
    val rows = listOf(
      "summary.duration" to StringUtil.formatDuration(s.duration.toMillis()),
      "summary.samples" to s.cpuSamples.toString(),
      "summary.cpu" to (s.jvmCpu?.let { "%.0f%%".format(it * 100) } ?: "—"),
      "summary.allocations" to StringUtil.formatFileSize(s.sampledAllocationBytes),
      "summary.gc" to PhantomProfilerBundle.message("summary.gc.value", s.gcCount, ms(s.gcPauseTotal), ms(s.gcPauseMax)),
    )
    return "<html><table>" + rows.joinToString("") { (k, v) -> "<tr><td>${PhantomProfilerBundle.message(k)}</td><td><b>$v</b></td></tr>" } +
           "</table><br>${PhantomProfilerBundle.message("summary.file", file.path)}</html>"
  }

  override fun getComponent(): JComponent = loading
  override fun getPreferredFocusedComponent(): JComponent? = null
  override fun getName(): String = PhantomProfilerBundle.message("viewer.name")
  override fun setState(state: FileEditorState) {}
  override fun isModified(): Boolean = false
  override fun isValid(): Boolean = file.isValid
  override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
  override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
  override fun getFile(): VirtualFile = file
  override fun dispose() {}
}

/** The call tree as a Swing tree model; children are already sorted heaviest first. */
private class CallTreeModel(private val root: CallNode) : TreeModel {
  override fun getRoot(): Any = root
  override fun getChild(parent: Any, index: Int): Any = (parent as CallNode).children[index]
  override fun getChildCount(parent: Any): Int = (parent as CallNode).children.size
  override fun isLeaf(node: Any): Boolean = (node as CallNode).children.isEmpty()
  override fun valueForPathChanged(path: TreePath, newValue: Any?) {}
  override fun getIndexOfChild(parent: Any, child: Any): Int = (parent as CallNode).children.indexOf(child)
  override fun addTreeModelListener(l: TreeModelListener) {}
  override fun removeTreeModelListener(l: TreeModelListener) {}
}
