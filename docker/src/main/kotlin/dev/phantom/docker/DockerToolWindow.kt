// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.docker

import com.intellij.execution.RunContentExecutor
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.IdeFrame
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.JBColor
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.table.JBTable
import com.intellij.util.Alarm
import dev.phantom.docker.compose.PhantomDockerBundle
import org.jetbrains.annotations.PropertyKey
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseEvent
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel

/** Offered in projects with a compose file or a Dockerfile at the top. */
class DockerToolWindowFactory : ToolWindowFactory, DumbAware {
  override suspend fun isApplicableAsync(project: Project): Boolean {
    val dir = project.guessProjectDir() ?: return false
    return dir.children.any { ComposeFiles.isComposeFile(it.name) || it.name == "Dockerfile" || it.name.endsWith(".Dockerfile") }
  }

  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val panel = ContainersPanel(project, toolWindow.disposable)
    toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(panel, PhantomDockerBundle.message("containers.tab"), false))
  }
}

/**
 * `docker ps -a` as a table, grouped by compose project. Refreshes every three seconds while it is on screen and the IDE is
 * the active application, never in the background; switching back to the IDE refreshes at once. One poll runs at a time,
 * and failing polls back off up to [MAX_PERIOD_MS]. Start, stop, restart, remove and logs run the docker command for the selected containers.
 */
class ContainersPanel(private val project: Project, parent: Disposable) : JPanel(BorderLayout()), Disposable {
  private var rows: List<DockerCli.Container> = emptyList()
  private val model = object : AbstractTableModel() {
    override fun getRowCount() = rows.size
    override fun getColumnCount() = COLUMNS.size
    override fun getColumnName(column: Int) = PhantomDockerBundle.message(COLUMNS[column])
    override fun getValueAt(row: Int, column: Int): Any = rows[row]
  }
  private val table = JBTable(model)
  private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
  private val polling = AtomicBoolean()
  @Volatile private var failures = 0

  init {
    Disposer.register(parent, this)
    ApplicationManager.getApplication().messageBus.connect(this).subscribe(ApplicationActivationListener.TOPIC, object : ApplicationActivationListener {
      override fun applicationActivated(ideFrame: IdeFrame) {
        if (isShowing) schedule(0)
      }
    })
    table.setShowGrid(false)
    table.selectionModel.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
    table.emptyText.text = PhantomDockerBundle.message("containers.loading")
    table.setDefaultRenderer(Any::class.java, Renderer())
    object : DoubleClickListener() {
      override fun onDoubleClick(event: MouseEvent): Boolean { selected().firstOrNull()?.let(::showLogs); return true }
    }.installOn(table)

    val actions = DefaultActionGroup(
      action("containers.action.refresh", AllIcons.Actions.Refresh, { true }) { refresh() },
      Separator.getInstance(),
      action("containers.action.start", AllIcons.Actions.Execute, { it.any { c -> !c.running } }) { run("start", it) },
      action("containers.action.stop", AllIcons.Actions.Suspend, { it.any { c -> c.running } }) { run("stop", it) },
      action("containers.action.restart", AllIcons.Actions.Restart, { it.isNotEmpty() }) { run("restart", it) },
      action("containers.action.logs", AllIcons.Debugger.Console, { it.size == 1 }) { showLogs(it.single()) },
      action("containers.action.remove", AllIcons.General.Remove, { it.isNotEmpty() }) { remove(it) },
      Separator.getInstance(),
      action("containers.action.compose.down", AllIcons.Actions.Cancel, { it.any { c -> c.composeProject.isNotEmpty() } }) { composeDown(it) },
      action("containers.action.copy.id", AllIcons.Actions.Copy, { it.isNotEmpty() }) {
        CopyPasteManager.getInstance().setContents(StringSelection(it.joinToString("\n") { c -> c.id.take(12) }))
      },
    )
    val toolbar = ActionManager.getInstance().createActionToolbar("PhantomDockerContainers", actions, true)
    toolbar.targetComponent = table
    PopupHandler.installPopupMenu(table, actions, "PhantomDockerContainersPopup")
    add(toolbar.component, BorderLayout.NORTH)
    add(ScrollPaneFactory.createScrollPane(table, true), BorderLayout.CENTER)
  }

  private fun selected(): List<DockerCli.Container> {
    val list = rows
    return table.selectedRows.map { row -> table.convertRowIndexToModel(row) }.filter { index -> index in list.indices }.map { index -> list[index] }
  }

  private fun action(@PropertyKey(resourceBundle = "messages.PhantomDockerBundle") key: String, icon: javax.swing.Icon,
                     enabled: (List<DockerCli.Container>) -> Boolean,
                     perform: (List<DockerCli.Container>) -> Unit) = object : DumbAwareAction(PhantomDockerBundle.messagePointer(key), icon) {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun update(e: AnActionEvent) { e.presentation.isEnabled = enabled(selected()) }
    override fun actionPerformed(e: AnActionEvent) = perform(selected())
  }

  override fun addNotify() {
    super.addNotify()
    schedule(0)
  }

  override fun removeNotify() {
    super.removeNotify()
    alarm.cancelAllRequests()
  }

  fun refresh() = schedule(0)

  private fun schedule(delay: Int) {
    alarm.cancelAllRequests()
    alarm.addRequest(::poll, delay)
  }

  private fun poll() {
    // A refresh while a poll runs used to start a second chain of polls; the running poll's result is fresh enough.
    if (!polling.compareAndSet(false, true)) return
    val result = try {
      // paused while another application is in front; applicationActivated starts it again
      if (!ApplicationManager.getApplication().isActive) return
      runCatching { DockerCli.containers() }
    }
    finally {
      polling.set(false)
    }
    failures = if (result.isSuccess) 0 else failures + 1
    ApplicationManager.getApplication().invokeLater({
      result.onSuccess { list ->
        val keep = selected().map { it.id }.toSet()
        rows = list.sortedWith(compareBy({ it.composeProject.ifEmpty { "￿" } }, { !it.running }, { it.name }))
        model.fireTableDataChanged()
        rows.forEachIndexed { i, c -> if (c.id in keep) table.selectionModel.addSelectionInterval(table.convertRowIndexToView(i), table.convertRowIndexToView(i)) }
        table.emptyText.text = PhantomDockerBundle.message("containers.empty")
      }.onFailure { e ->
        rows = emptyList()
        model.fireTableDataChanged()
        table.emptyText.setText(e.message ?: PhantomDockerBundle.message("containers.docker.failed"), SimpleTextAttributes.ERROR_ATTRIBUTES)
      }
    }, ModalityState.any()) { project.isDisposed }
    // a missing docker command will not appear by itself; the Refresh button retries
    // schedule() drops any request queued meanwhile, so one chain of polls remains
    if (result.exceptionOrNull() !is DockerNotFoundException && isShowing) schedule(nextDelay(failures))
  }

  private fun run(command: String, containers: List<DockerCli.Container>) {
    if (containers.isEmpty()) return
    background("docker $command") { DockerCli.run(command, *containers.map { it.id }.toTypedArray(), timeoutMs = 60_000) }
  }

  private fun remove(containers: List<DockerCli.Container>) {
    val names = containers.joinToString(", ") { it.name }
    if (Messages.showYesNoDialog(project, PhantomDockerBundle.message("containers.remove.question", names),
                                 PhantomDockerBundle.message("containers.remove.title"), null) != Messages.YES) return
    background("docker rm") { DockerCli.run("rm", "-f", *containers.map { it.id }.toTypedArray(), timeoutMs = 60_000) }
  }

  private fun composeDown(containers: List<DockerCli.Container>) {
    val projects = containers.map { it.composeProject }.filter { it.isNotEmpty() }.distinct()
    if (Messages.showYesNoDialog(project, PhantomDockerBundle.message("containers.compose.down.question", projects.joinToString(", ")),
                                 PhantomDockerBundle.message("containers.action.compose.down"), null) != Messages.YES) return
    for (p in projects) background("docker compose down") { DockerCli.run("compose", "-p", p, "down", timeoutMs = 120_000) }
  }

  private fun background(title: String, task: () -> com.intellij.execution.process.ProcessOutput) {
    ApplicationManager.getApplication().executeOnPooledThread {
      val output = runCatching(task)
      val failure = output.exceptionOrNull()?.message
                    ?: output.getOrNull()?.takeIf { it.exitCode != 0 || it.isTimeout }?.let { it.stderr.trim().ifEmpty { PhantomDockerBundle.message("containers.exit.code", it.exitCode) } }
      if (failure != null) {
        NotificationGroupManager.getInstance().getNotificationGroup("Docker Support").createNotification(PhantomDockerBundle.message("containers.command.failed", title), failure, NotificationType.ERROR).notify(project)
      }
      schedule(0)
    }
  }

  private fun showLogs(container: DockerCli.Container) {
    val handler = try {
      KillableColoredProcessHandler(DockerCli.command("logs", "-f", "--tail", "1000", container.id))
    }
    catch (e: Exception) {
      Messages.showErrorDialog(project, e.message, PhantomDockerBundle.message("containers.logs.title"))
      return
    }
    RunContentExecutor(project, handler).withTitle(PhantomDockerBundle.message("containers.logs.tab", container.name)).withActivateToolWindow(true).run()
  }

  override fun dispose() {}

  private inner class Renderer : ColoredTableCellRenderer() {
    override fun customizeCellRenderer(table: JTable, value: Any?, selected: Boolean, hasFocus: Boolean, row: Int, column: Int) {
      val c = value as? DockerCli.Container ?: return
      when (column) {
        0 -> {
          icon = if (c.running) AllIcons.RunConfigurations.TestState.Run else AllIcons.Actions.Suspend
          append(c.name, if (c.running) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES else SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
        1 -> append(c.composeProject.let { p -> if (p.isEmpty()) "" else if (c.composeService.isEmpty()) p else "$p / ${c.composeService}" })
        2 -> append(c.image, SimpleTextAttributes.GRAYED_ATTRIBUTES)
        3 -> append(c.status, SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, if (c.running) JBColor(0x2E7D32, 0x6AAB73) else null))
        // 0.0.0.0:3306->3306/tcp, [::]:3306->3306/tcp → 3306->3306/tcp
        4 -> append(c.ports.split(", ").map { it.substringAfter("0.0.0.0:").substringAfter("[::]:") }.distinct().joinToString(", "),
                    SimpleTextAttributes.GRAYED_ATTRIBUTES)
      }
    }
  }

  companion object {
    private val COLUMNS = listOf("containers.column.name", "containers.column.compose", "containers.column.image", "containers.column.status",
                                 "containers.column.ports")
    private const val PERIOD_MS = 3_000
    private const val MAX_PERIOD_MS = 60_000

    /** Three seconds, doubled after each failure in a row, at most a minute. */
    internal fun nextDelay(failures: Int): Int = (PERIOD_MS.toLong() shl failures.coerceIn(0, 5)).coerceAtMost(MAX_PERIOD_MS.toLong()).toInt()
  }
}
