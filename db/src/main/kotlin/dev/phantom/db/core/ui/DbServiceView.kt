// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.ui

import com.intellij.execution.services.ServiceEventListener
import com.intellij.execution.services.ServiceViewContributor
import com.intellij.execution.services.ServiceViewDescriptor
import com.intellij.execution.services.SimpleServiceViewDescriptor
import com.intellij.icons.AllIcons
import com.intellij.ide.projectView.PresentationData
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.ui.SimpleTextAttributes
import dev.phantom.db.core.DbDataSource
import dev.phantom.db.core.DbDataSourceService
import dev.phantom.db.core.PhantomDbBundle
import dev.phantom.db.core.PhantomDbIcons
import dev.phantom.db.core.exec.DbConnectionService
import dev.phantom.db.core.exec.DbConnectionService.SessionInfo
import dev.phantom.db.core.exec.DbConsoles
import dev.phantom.db.core.exec.DbTransactions
import java.text.SimpleDateFormat
import java.util.Date

/**
 * The Services tool window's Database node, as in DataGrip: the connected data sources, each with its open sessions
 * (the shared console connection, a console's own, the schema tree's). Disconnect and Close Session are in their popups.
 */
class DbServiceViewContributor : ServiceViewContributor<DbServiceViewContributor.Connected> {
  override fun getViewDescriptor(project: Project): ServiceViewDescriptor =
    SimpleServiceViewDescriptor(PhantomDbBundle.message("db.services.root"), AllIcons.Nodes.DataTables)

  override fun getServices(project: Project): List<Connected> {
    val sessions = DbConnectionService.getInstance(project).openSessions().groupBy { it.key.dataSourceId }
    val sources = DbDataSourceService.getInstance(project)
    return sessions.mapNotNull { (id, list) -> sources.find(id)?.let { Connected(project, it, list) } }
  }

  override fun getServiceDescriptor(project: Project, service: Connected): ServiceViewDescriptor = service.descriptor()

  /** A connected data source; its sessions are its children. */
  class Connected(private val project: Project, val dataSource: DbDataSource, private val sessions: List<SessionInfo>) :
    ServiceViewContributor<SessionInfo> {
    override fun getViewDescriptor(project: Project): ServiceViewDescriptor = descriptor()

    override fun getServices(project: Project): List<SessionInfo> = sessions

    override fun getServiceDescriptor(project: Project, service: SessionInfo): ServiceViewDescriptor = object : ServiceViewDescriptor {
      override fun getPresentation(): ItemPresentation = PresentationData().apply {
        setIcon(AllIcons.Debugger.Console)
        addText(DbServiceView.name(service), SimpleTextAttributes.REGULAR_ATTRIBUTES)
        addText("  " + PhantomDbBundle.message("db.sessions.since", SimpleDateFormat("HH:mm:ss").format(Date(service.openedAt))),
                SimpleTextAttributes.GRAYED_ATTRIBUTES)
        if (service.inTransaction) addText("  " + PhantomDbBundle.message("db.sessions.transaction"), SimpleTextAttributes.ERROR_ATTRIBUTES)
      }

      override fun getId(): String = dataSource.id + "|" + service.key.purpose + "|" + service.key.console.orEmpty()

      override fun getPopupActions(): ActionGroup = DefaultActionGroup(action(PhantomDbBundle.message("db.sessions.close")) {
        if (service.key.console != null || service.key.purpose != DbConnectionService.Purpose.CONSOLE ||
            DbTransactions.endOrKeep(project, dataSource, PhantomDbBundle.message("db.sessions.close"))) {
          DbConnectionService.getInstance(project).close(service.key)
        }
      })
    }

    fun descriptor(): ServiceViewDescriptor = object : ServiceViewDescriptor {
      override fun getPresentation(): ItemPresentation = PresentationData().apply {
        setIcon(PhantomDbIcons.forDataSource(dataSource))
        addText(dataSource.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
        addText("  " + PhantomDbBundle.message("db.services.sessions", sessions.size), SimpleTextAttributes.GRAYED_ATTRIBUTES)
      }

      override fun getId(): String = dataSource.id

      override fun getPopupActions(): ActionGroup = DefaultActionGroup(action(PhantomDbBundle.message("action.PhantomDb.Tree.Disconnect.text")) {
        if (DbTransactions.endOrKeep(project, dataSource, PhantomDbBundle.message("action.PhantomDb.Tree.Disconnect.text"))) {
          DbConnectionService.getInstance(project).disconnect(dataSource.id)
        }
      })
    }

    override fun equals(other: Any?): Boolean = other is Connected && other.dataSource.id == dataSource.id

    override fun hashCode(): Int = dataSource.id.hashCode()
  }

  private companion object {
    fun action(text: String, run: () -> Unit) = object : DumbAwareAction(text) {
      override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
      override fun actionPerformed(e: AnActionEvent) = run()
    }
  }
}

object DbServiceView {
  /** How a session is called in Services and Sessions: the console's, Shared, the schema tree's. */
  fun name(info: SessionInfo): String = when {
    info.key.purpose == DbConnectionService.Purpose.METADATA -> PhantomDbBundle.message("db.sessions.metadata")
    info.key.console != null -> DbConsoles.sessionName(info.key.console)
    else -> PhantomDbBundle.message("db.sessions.shared")
  }

  /** Shows the sessions as they are now in the Services tool window. */
  fun refresh(project: Project) {
    if (project.isDisposed) return
    project.messageBus.syncPublisher(ServiceEventListener.TOPIC)
      .handle(ServiceEventListener.ServiceEvent.createResetEvent(DbServiceViewContributor::class.java))
  }
}
