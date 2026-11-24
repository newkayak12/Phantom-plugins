// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.profiler

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.JBSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.content.ContentFactory
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.event.MouseEvent
import java.nio.file.Files
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import kotlin.io.path.name

class ProfilerToolWindowFactory : ToolWindowFactory, DumbAware {
  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(ProfilerPanel(project), null, false))
  }
}

/**
 * Profiler home: JVMs running on this machine, with Start Recording / Stop and Open, and the recordings saved so far.
 * The JVM list is read when the window opens and on Refresh, never in the background.
 */
class ProfilerPanel(private val project: Project) : JPanel(BorderLayout()) {
  private val jvms = DefaultListModel<LocalJvm>()
  private val jvmList = JBList(jvms)
  private val recordings = DefaultListModel<SavedRecording>()
  private val recordingList = JBList(recordings)

  init {
    jvmList.emptyText.text = PhantomProfilerBundle.message("toolwindow.no.jvms")
    jvmList.cellRenderer = object : ColoredListCellRenderer<LocalJvm>() {
      override fun customizeCellRenderer(list: JList<out LocalJvm>, value: LocalJvm, index: Int, selected: Boolean, hasFocus: Boolean) {
        icon = if (JvmAttach.isRecording(value.pid)) AllIcons.Nodes.Record else AllIcons.Nodes.Console
        append(value.displayName)
        append("  ${value.pid}  ${value.mainClass}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        if (JvmAttach.isRecording(value.pid)) append("  " + PhantomProfilerBundle.message("toolwindow.recording"), SimpleTextAttributes.ERROR_ATTRIBUTES)
      }
    }
    recordingList.emptyText.text = PhantomProfilerBundle.message("toolwindow.no.recordings")
    recordingList.cellRenderer = object : ColoredListCellRenderer<SavedRecording>() {
      override fun customizeCellRenderer(
        list: JList<out SavedRecording>, value: SavedRecording, index: Int, selected: Boolean, hasFocus: Boolean,
      ) {
        icon = AllIcons.Actions.Profile
        append(value.path.name)
        // size and time come from the background refresh; this runs on the EDT for every cell on each paint
        val time = DateFormatUtil.formatPrettyDateTime(value.modified)
        append("  ${StringUtil.formatFileSize(value.size)}  $time", SimpleTextAttributes.GRAYED_ATTRIBUTES)
      }
    }
    object : DoubleClickListener() {
      override fun onDoubleClick(event: MouseEvent): Boolean {
        Recordings.open(project, recordingList.selectedValue?.path ?: return false)
        return true
      }
    }.installOn(recordingList)

    val splitter = JBSplitter(true, 0.5f)
    splitter.firstComponent = section(PhantomProfilerBundle.message("toolwindow.jvms"), jvmList,
                                      DefaultActionGroup(StartAction(), StopAction(), RefreshAction()))
    splitter.secondComponent = section(PhantomProfilerBundle.message("toolwindow.recordings"), recordingList,
                                       DefaultActionGroup(OpenRecordingAction(), DeleteRecordingAction(), RefreshAction()))
    add(splitter, BorderLayout.CENTER)
    refresh()
  }

  private fun section(title: String, list: JComponent, actions: DefaultActionGroup): JComponent {
    val panel = SimpleToolWindowPanel(true, true)
    val toolbar = ActionManager.getInstance().createActionToolbar("PhantomProfiler", actions, true)
    toolbar.targetComponent = list
    panel.toolbar = JPanel(BorderLayout()).apply {
      add(com.intellij.ui.components.JBLabel(title).apply { border = JBUI.Borders.empty(0, 8) }, BorderLayout.WEST)
      add(toolbar.component, BorderLayout.CENTER)
    }
    panel.setContent(ScrollPaneFactory.createScrollPane(list, true))
    return panel
  }

  fun refresh() {
    ApplicationManager.getApplication().executeOnPooledThread {
      val found = runCatching { JvmAttach.list() }.getOrDefault(emptyList())
      val saved = Recordings.list()
      ApplicationManager.getApplication().invokeLater({
        val selected = jvmList.selectedValue
        jvms.clear(); found.forEach(jvms::addElement)
        if (selected != null) jvmList.setSelectedValue(found.firstOrNull { it.pid == selected.pid }, false)
        recordings.clear(); saved.forEach(recordings::addElement)
      }, { project.isDisposed })
    }
  }

  private fun inBackground(task: () -> Unit) {
    ApplicationManager.getApplication().executeOnPooledThread {
      try {
        task()
      }
      catch (e: Exception) {
        Recordings.notify(project, e.message ?: e.javaClass.simpleName)
      }
      refresh()
    }
  }

  private abstract inner class JvmAction(key: String, icon: javax.swing.Icon) : DumbAwareAction(PhantomProfilerBundle.message(key), null, icon) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
  }

  private inner class StartAction : JvmAction("toolwindow.start", AllIcons.Actions.Execute) {
    override fun update(e: AnActionEvent) {
      e.presentation.isEnabled = jvmList.selectedValue?.let { !JvmAttach.isRecording(it.pid) } == true
    }
    override fun actionPerformed(e: AnActionEvent) {
      val jvm = jvmList.selectedValue ?: return
      inBackground { JvmAttach.start(jvm.pid) }
    }
  }

  private inner class StopAction : JvmAction("toolwindow.stop", AllIcons.Actions.Suspend) {
    override fun update(e: AnActionEvent) {
      e.presentation.isEnabled = jvmList.selectedValue?.let { JvmAttach.isRecording(it.pid) } == true
    }
    override fun actionPerformed(e: AnActionEvent) {
      val jvm = jvmList.selectedValue ?: return
      inBackground {
        val file = JvmAttach.stop(jvm)
        ApplicationManager.getApplication().invokeLater({ Recordings.open(project, file) }, { project.isDisposed })
      }
    }
  }

  private inner class RefreshAction : JvmAction("toolwindow.refresh", AllIcons.Actions.Refresh) {
    override fun actionPerformed(e: AnActionEvent) = refresh()
  }

  private inner class OpenRecordingAction : JvmAction("toolwindow.open", AllIcons.Actions.MenuOpen) {
    override fun update(e: AnActionEvent) {
      e.presentation.isEnabled = recordingList.selectedValue != null
    }
    override fun actionPerformed(e: AnActionEvent) {
      Recordings.open(project, recordingList.selectedValue?.path ?: return)
    }
  }

  private inner class DeleteRecordingAction : JvmAction("toolwindow.delete", AllIcons.General.Remove) {
    override fun update(e: AnActionEvent) {
      e.presentation.isEnabled = recordingList.selectedValue != null
    }
    override fun actionPerformed(e: AnActionEvent) {
      val files = recordingList.selectedValuesList
      inBackground { files.forEach { Files.deleteIfExists(it.path) } }
    }
  }
}
