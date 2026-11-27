// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.mongo

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.*
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import dev.phantom.db.core.PhantomDbBundle
import com.mongodb.ConnectionString
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.table.DefaultTableModel

/**
 * Opens the MongoDB tool window. It is registered here, on the user's request, rather than in plugin.xml, so projects
 * that never use MongoDB get no extra stripe button.
 */
class MongoConsoleAction : AnAction(), DumbAware {
  override fun getActionUpdateThread() = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = e.project != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val manager = ToolWindowManager.getInstance(project)
    val window = manager.getToolWindow(TOOL_WINDOW_ID) ?: manager.registerToolWindow(TOOL_WINDOW_ID) {
      anchor = ToolWindowAnchor.BOTTOM
      icon = AllIcons.Nodes.DataTables
      canCloseContent = false
      stripeTitle = { PhantomDbBundle.message("mongo.toolwindow") }
    }.also { it.contentManager.addContent(ContentFactory.getInstance().createContent(MongoConsolePanel(project), null, false)) }
    window.activate(null)
  }

  companion object {
    const val TOOL_WINDOW_ID = "MongoDB"
  }
}

private class MongoConsolePanel(private val project: Project) : JPanel(BorderLayout()) {
  private val connections = ComboBox<MongoConnection>()
  private val database = JBTextField(14)
  private val query = JBTextArea("db.collection.find({})", 6, 80).apply { font = Font(Font.MONOSPACED, Font.PLAIN, font.size) }
  private val model = object : DefaultTableModel() {
    override fun isCellEditable(row: Int, column: Int) = false
  }
  private val status = JBLabel()

  init {
    connections.renderer = SimpleListCellRenderer.create("") { it.toString() }
    connections.addActionListener { (connections.selectedItem as? MongoConnection)?.let { database.text = defaultDatabase(it.uri) } }
    reloadConnections(null)
    val top = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
      add(connections)
      add(button("mongo.connection.add") { edit(MongoConnection()) })
      add(button("mongo.connection.edit") { (connections.selectedItem as? MongoConnection)?.let(::edit) })
      add(button("mongo.connection.remove") { remove() })
      add(JBLabel(PhantomDbBundle.message("mongo.database")))
      add(database)
      add(button("mongo.run") { run() })
    }
    val table = JBTable(model).apply { autoResizeMode = JBTable.AUTO_RESIZE_OFF }
    val split = JSplitPane(JSplitPane.VERTICAL_SPLIT, JBScrollPane(query), JBScrollPane(table)).apply { resizeWeight = 0.3 }
    add(top, BorderLayout.NORTH)
    add(split, BorderLayout.CENTER)
    add(status.apply { border = JBUI.Borders.empty(2, 6) }, BorderLayout.SOUTH)
  }

  private fun button(key: String, action: () -> Unit) = JButton(PhantomDbBundle.message(key)).apply { addActionListener { action() } }

  private fun reloadConnections(selected: MongoConnection?) {
    val all = MongoConnections.getInstance(project).state.connections
    connections.model = DefaultComboBoxModel(all.toTypedArray())
    connections.selectedItem = selected ?: all.firstOrNull()
    (connections.selectedItem as? MongoConnection)?.let { database.text = defaultDatabase(it.uri) }
  }

  private fun edit(connection: MongoConnection) {
    if (!MongoConnectionDialog(project, connection).showAndGet()) return
    val all = MongoConnections.getInstance(project).state.connections
    if (all.none { it.id == connection.id }) all += connection
    reloadConnections(connection)
  }

  private fun remove() {
    val connection = connections.selectedItem as? MongoConnection ?: return
    MongoConnections.getInstance(project).state.connections.removeIf { it.id == connection.id }
    MongoConnections.setPassword(connection.id, null)
    reloadConnections(null)
  }

  private fun run() {
    val connection = connections.selectedItem as? MongoConnection
    val command = try {
      if (connection == null) throw MongoQueryException(PhantomDbBundle.message("mongo.connection.none"))
      if (database.text.isBlank()) throw MongoQueryException(PhantomDbBundle.message("mongo.error.database"))
      MongoQuery.parse(query.text)
    }
    catch (e: MongoQueryException) {
      status.text = e.message
      return
    }
    val db = database.text.trim()
    status.text = PhantomDbBundle.message("mongo.running", command.collection, connection!!.toString())
    object : Task.Backgroundable(project, PhantomDbBundle.message("mongo.running", command.collection, connection.toString()), true) {
      private var result: MongoTable? = null
      private var millis = 0L

      override fun run(indicator: ProgressIndicator) {
        val start = System.currentTimeMillis()
        val uri = MongoUris.withPassword(connection.uri, MongoConnections.password(connection.id))
        result = MongoQuery.table(MongoRunner.run(uri, db, command))
        millis = System.currentTimeMillis() - start
      }

      override fun onSuccess() {
        val table = result ?: return
        model.setDataVector(table.rows.map { it.toTypedArray<Any>() }.toTypedArray(), table.columns.toTypedArray())
        status.text = PhantomDbBundle.message("mongo.status.rows", table.rows.size, millis, MongoRunner.MAX_DOCUMENTS)
      }

      override fun onThrowable(error: Throwable) {
        status.text = error.message ?: error.javaClass.simpleName
      }
    }.queue()
  }

  private fun defaultDatabase(uri: String): String = runCatching { ConnectionString(uri).database }.getOrNull().orEmpty()
}

private class MongoConnectionDialog(project: Project, private val connection: MongoConnection) : DialogWrapper(project) {
  private val name = JBTextField(connection.name, 30)
  private val uri = JBTextField(connection.uri, 40)
  private val password = JBPasswordField()

  init {
    title = PhantomDbBundle.message("mongo.connection.dialog.title")
    init()
  }

  override fun createCenterPanel() = panel {
    row(PhantomDbBundle.message("mongo.connection.name")) { cell(name).align(AlignX.FILL) }
    row(PhantomDbBundle.message("mongo.connection.uri")) { cell(uri).align(AlignX.FILL) }
    row(PhantomDbBundle.message("mongo.connection.password")) { cell(password).align(AlignX.FILL).comment(PhantomDbBundle.message("mongo.connection.password.comment")) }
  }

  override fun doValidate() = runCatching { ConnectionString(uri.text.trim()) }.exceptionOrNull()?.let {
    com.intellij.openapi.ui.ValidationInfo(it.message.orEmpty(), uri)
  }

  override fun doOKAction() {
    val (plain, inline) = MongoUris.splitPassword(uri.text)
    connection.name = name.text.trim()
    connection.uri = plain
    val typed = String(password.password)
    when {
      typed.isNotEmpty() -> MongoConnections.setPassword(connection.id, typed)
      inline != null -> MongoConnections.setPassword(connection.id, inline)
    }
    super.doOKAction()
  }
}
