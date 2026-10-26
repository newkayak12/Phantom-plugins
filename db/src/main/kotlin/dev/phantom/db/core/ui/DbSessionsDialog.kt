// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import dev.phantom.db.core.DbDataSourceService
import dev.phantom.db.core.PhantomDbBundle
import dev.phantom.db.core.PhantomDbIcons
import dev.phantom.db.core.exec.DbConnectionService
import dev.phantom.db.core.exec.DbConnectionService.SessionInfo
import dev.phantom.db.core.exec.DbTransactions
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JList

/**
 * The open sessions of the project's data sources (DataGrip shows them in Services): which data source, the shared
 * console connection or a console's own, the schema tree's, since when and whether a transaction is open; Close
 * Session ends one (rolling its transaction back after asking), Close All all of them.
 */
class DbSessionsDialog(private val project: Project) : DialogWrapper(project) {
  private val service = DbConnectionService.getInstance(project)
  private val model = DefaultListModel<SessionInfo>()
  internal val list = JBList(model)

  init {
    title = PhantomDbBundle.message("db.sessions.title")
    list.emptyText.text = PhantomDbBundle.message("db.sessions.empty")
    list.cellRenderer = Renderer()
    service.addSessionListener(disposable) { ApplicationManager.getApplication().invokeLater({ if (!isDisposed) fill() }, { isDisposed }) }
    fill()
    init()
  }

  internal fun fill() {
    val selected = list.selectedValue?.key
    model.clear()
    service.openSessions().forEach(model::addElement)
    (0 until model.size()).firstOrNull { model[it].key == selected }?.let { list.selectedIndex = it }
  }

  override fun createCenterPanel(): JComponent = ToolbarDecorator.createDecorator(list)
    .disableAddAction()
    .disableUpDownActions()
    .setRemoveAction { closeSelected() }
    .setRemoveActionName(PhantomDbBundle.message("db.sessions.close"))
    .createPanel().apply { preferredSize = JBUI.size(560, 320) }

  override fun createActions() = arrayOf(okAction)

  override fun createLeftSideActions() = arrayOf(object : DialogWrapperAction(PhantomDbBundle.message("db.sessions.close.all")) {
    override fun doAction(e: java.awt.event.ActionEvent?) {
      close((0 until model.size()).map { model[it] })
    }
  })

  private fun closeSelected() = close(list.selectedValuesList)

  /** A shared console session with pending changes asks first, as Remove and Disconnect do. */
  private fun close(sessions: List<SessionInfo>) {
    val sources = DbDataSourceService.getInstance(project)
    for (info in sessions) {
      val ds = sources.find(info.key.dataSourceId)
      if (ds != null && info.key.console == null && info.key.purpose == DbConnectionService.Purpose.CONSOLE &&
          !DbTransactions.endOrKeep(project, ds, PhantomDbBundle.message("db.sessions.close"))) continue
      service.close(info.key)
    }
    fill()
  }

  private inner class Renderer : ColoredListCellRenderer<SessionInfo>() {
    override fun customizeCellRenderer(list: JList<out SessionInfo>, value: SessionInfo, index: Int, selected: Boolean, hasFocus: Boolean) {
      val ds = DbDataSourceService.getInstance(project).find(value.key.dataSourceId)
      icon = ds?.let { PhantomDbIcons.forDataSource(it) }
      append(ds?.name ?: value.key.dataSourceId)
      val what = DbServiceView.name(value)
      append("  $what", SimpleTextAttributes.GRAYED_ATTRIBUTES)
      append("  " + PhantomDbBundle.message("db.sessions.since", SimpleDateFormat("HH:mm:ss").format(Date(value.openedAt))), SimpleTextAttributes.GRAYED_ATTRIBUTES)
      if (value.inTransaction) append("  " + PhantomDbBundle.message("db.sessions.transaction"), SimpleTextAttributes.ERROR_ATTRIBUTES)
    }
  }
}
