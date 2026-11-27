// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.redis

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.*
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.table.JBTable
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.*
import javax.swing.table.DefaultTableModel

class RedisToolWindowFactory : ToolWindowFactory, DumbAware {
  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val panel = RedisPanel(project, toolWindow.disposable)
    toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(panel, null, false))
  }
}

/**
 * Keys on the left (SCAN with a pattern), the selected key's value on the right, a command line below. One
 * connection, opened on first use on a single background thread and closed when the window is hidden.
 */
class RedisPanel(private val project: Project, parent: Disposable) : JPanel(BorderLayout()), Disposable {
  private val executor = AppExecutorUtil.createBoundedApplicationPoolExecutor("Phantom Key-Value Console", 1)
  private val servers = ComboBox<RedisServer>()
  private val pattern = SearchTextField(false).apply { text = "*" }
  private val keysModel = DefaultListModel<RedisKey>()
  private val keys = JBList(keysModel)
  private val keysStatus = JBLabel().apply { border = JBUI.Borders.empty(2, 6); foreground = UIUtil.getContextHelpForeground() }
  private val valueHeader = JBLabel().apply { border = JBUI.Borders.empty(4, 8) }
  private val valueHolder = JPanel(BorderLayout())
  private val consoleOutput = JBTextArea().apply { isEditable = false; font = JBUI.Fonts.create(java.awt.Font.MONOSPACED, font.size) }
  private val consoleInput = JBTextField().apply { emptyText.text = PhantomRedisBundle.message("console.input.empty") }
  private val history = ArrayList<String>()
  private var historyIndex = 0
  // Bumped by each request; a reply to an older one is dropped (the server or pattern changed meanwhile) and a request
  // still queued behind a slow one is skipped, so a long scan of the previous server no longer runs first.
  private val keysGeneration = AtomicInteger()
  private val valueGeneration = AtomicInteger()
  // touched only on the executor thread
  private var connection: RedisConnection? = null
  private var connectedTo: String? = null

  init {
    Disposer.register(parent, this)
    reloadServers(null)
    servers.addActionListener { closeConnection(); loadKeys() }

    keys.cellRenderer = object : ColoredListCellRenderer<RedisKey>() {
      override fun customizeCellRenderer(list: JList<out RedisKey>, value: RedisKey, index: Int, selected: Boolean, hasFocus: Boolean) {
        append(value.type.padEnd(7).take(7), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        append(" ")
        append(value.name)
        if (value.ttlSeconds >= 0) append(PhantomRedisBundle.message("key.ttl", value.ttlSeconds), SimpleTextAttributes.GRAYED_ATTRIBUTES)
      }
    }
    keys.selectionMode = ListSelectionModel.SINGLE_SELECTION
    keys.addListSelectionListener { if (!it.valueIsAdjusting) keys.selectedValue?.let(::loadValue) }
    keys.emptyText.text = PhantomRedisBundle.message("keys.empty")
    pattern.addKeyboardListener(object : KeyAdapter() {
      override fun keyPressed(e: KeyEvent) { if (e.keyCode == KeyEvent.VK_ENTER) loadKeys() }
    })
    pattern.textEditor.emptyText.text = PhantomRedisBundle.message("keys.pattern.empty")

    consoleInput.addKeyListener(object : KeyAdapter() {
      override fun keyPressed(e: KeyEvent) {
        when (e.keyCode) {
          KeyEvent.VK_ENTER -> runConsole()
          KeyEvent.VK_UP -> if (history.isNotEmpty()) { historyIndex = (historyIndex - 1).coerceAtLeast(0); consoleInput.text = history[historyIndex] }
          KeyEvent.VK_DOWN -> if (history.isNotEmpty()) {
            historyIndex = (historyIndex + 1).coerceAtMost(history.size)
            consoleInput.text = history.getOrElse(historyIndex) { "" }
          }
        }
      }
    })

    val actions = DefaultActionGroup(
      action(PhantomRedisBundle.message("action.add.server"), AllIcons.General.Add) { editServer(RedisServer()) },
      action(PhantomRedisBundle.message("action.edit.server"), AllIcons.Actions.Edit) { (servers.selectedItem as? RedisServer)?.let { editServer(it.copy()) } },
      action(PhantomRedisBundle.message("action.remove.server"), AllIcons.General.Remove) { removeServer() },
      Separator.getInstance(),
      action(PhantomRedisBundle.message("action.refresh.keys"), AllIcons.Actions.Refresh) { loadKeys() },
      action(PhantomRedisBundle.message("action.copy.key"), AllIcons.Actions.Copy) { keys.selectedValue?.let { CopyPasteManager.getInstance().setContents(StringSelection(it.name)) } },
      action(PhantomRedisBundle.message("action.delete.key"), AllIcons.Actions.GC) { deleteKey() },
    )
    val toolbar = ActionManager.getInstance().createActionToolbar("PhantomRedis", actions, true).apply { targetComponent = this@RedisPanel }
    val top = JPanel(BorderLayout()).apply {
      add(servers, BorderLayout.WEST)
      add(toolbar.component, BorderLayout.CENTER)
    }

    val left = JPanel(BorderLayout()).apply {
      add(pattern, BorderLayout.NORTH)
      add(ScrollPaneFactory.createScrollPane(keys, true), BorderLayout.CENTER)
      add(keysStatus, BorderLayout.SOUTH)
    }
    val right = JPanel(BorderLayout()).apply {
      add(valueHeader, BorderLayout.NORTH)
      add(valueHolder, BorderLayout.CENTER)
    }
    val browser = JBSplitter(false, "phantom.redis.keys", 0.35f).apply { firstComponent = left; secondComponent = right }
    val console = JPanel(BorderLayout()).apply {
      add(JBScrollPane(consoleOutput), BorderLayout.CENTER)
      add(consoleInput, BorderLayout.SOUTH)
    }
    val split = JBSplitter(true, "phantom.redis.console", 0.7f).apply { firstComponent = browser; secondComponent = console }
    add(top, BorderLayout.NORTH)
    add(split, BorderLayout.CENTER)
  }

  private fun action(text: String, icon: Icon, perform: () -> Unit) = object : DumbAwareAction(text, null, icon) {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun actionPerformed(e: AnActionEvent) = perform()
  }

  override fun addNotify() {
    super.addNotify()
    if (keysModel.isEmpty) loadKeys()
  }

  override fun removeNotify() {
    super.removeNotify()
    closeConnection()
  }

  private fun reloadServers(select: String?) {
    val list = RedisServers.getInstance(project).servers
    servers.model = DefaultComboBoxModel(list.toTypedArray())
    servers.selectedItem = list.firstOrNull { it.id == select } ?: list.firstOrNull()
  }

  private fun editServer(server: RedisServer) {
    val dialog = RedisServerDialog(project, server)
    if (!dialog.showAndGet()) return
    val (edited, password) = dialog.result()
    val servers = RedisServers.getInstance(project)
    servers.save(edited)
    // The password safe may block on the OS keychain. On the connection executor the write also lands before the
    // connection that reloadServers opens reads the password.
    if (password != null) executor.execute { servers.storePassword(edited.id, String(password)) }
    // The open connection is keyed by server id, which an edit keeps: without this it stayed on the old host, port,
    // user, database or TLS setting until the window was hidden.
    closeConnection()
    reloadServers(edited.id)
  }

  private fun removeServer() {
    val server = servers.selectedItem as? RedisServer ?: return
    if (server.id == RedisServers.LOCAL_ID) return
    if (Messages.showYesNoDialog(project, PhantomRedisBundle.message("dialog.remove.server.message", server.toString()),
                                 PhantomRedisBundle.message("dialog.remove.server.title"), null) != Messages.YES) return
    val servers = RedisServers.getInstance(project)
    servers.remove(server.id)
    // The password safe may block on the OS keychain.
    executor.execute { servers.storePassword(server.id, null) }
    reloadServers(null)
  }

  /**
   * Runs [task] with the connection on the background thread, then [done] on the EDT; errors go to the status line.
   * Once [stale] is true the task is skipped if it has not started and its result is dropped.
   */
  private fun <T> onConnection(task: (RedisConnection) -> T, failed: (String) -> Unit, stale: () -> Boolean = { false }, done: (T) -> Unit) {
    val server = servers.selectedItem as? RedisServer ?: return
    executor.execute {
      if (stale()) return@execute
      val result = runCatching {
        val c = connection?.takeIf { connectedTo == server.id } ?: run {
          closeConnectionNow()
          val password = RedisServers.getInstance(project).password(server.id)
          RedisConnection.open(server.host, server.port, server.user.ifBlank { null }, password, server.database, server.tls)
            .also { connection = it; connectedTo = server.id }
        }
        task(c)
      }
      // a broken connection is dropped, the next command opens a new one
      if (result.isFailure) closeConnectionNow()
      ApplicationManager.getApplication().invokeLater({
        if (stale()) return@invokeLater
        result.fold(done) { e ->
          failed(if (e is java.net.ConnectException) PhantomRedisBundle.message("error.cannot.connect", "${server.host}:${server.port}") else e.message ?: e.javaClass.simpleName)
        }
      }, ModalityState.any()) { Disposer.isDisposed(this) }
    }
  }

  private fun loadKeys() {
    val query = pattern.text.trim().ifEmpty { "*" }
    keysStatus.text = PhantomRedisBundle.message("keys.scanning")
    val ticket = keysGeneration.incrementAndGet()
    val stale = { keysGeneration.get() != ticket }
    onConnection({ RedisBrowser(it).scan(query, stale) }, { keysStatus.text = it; keysModel.clear() }, stale) { (found, more) ->
      val selected = keys.selectedValue?.name
      keysModel.clear()
      found.forEach(keysModel::addElement)
      keysStatus.text = if (more) PhantomRedisBundle.message("keys.more", found.size) else PhantomRedisBundle.message("keys.count", found.size)
      found.indexOfFirst { it.name == selected }.takeIf { it >= 0 }?.let { keys.selectedIndex = it }
    }
  }

  private fun loadValue(key: RedisKey) {
    valueHeader.text = key.name
    val ticket = valueGeneration.incrementAndGet()
    onConnection({ RedisBrowser(it).value(key.name) }, { valueHeader.text = it }, { valueGeneration.get() != ticket }) { value ->
      val size = when (value) {
        is RedisValue.Rows -> if (value.total > value.rows.size) PhantomRedisBundle.message("value.first.items", value.rows.size, value.total)
                              else PhantomRedisBundle.message("value.items", value.total)
        is RedisValue.Text -> if (value.size >= 0) PhantomRedisBundle.message("value.first.bytes", RedisBrowser.MAX_STRING_BYTES, value.size) else ""
      }
      val ttl = if (key.ttlSeconds >= 0) PhantomRedisBundle.message("value.ttl", key.ttlSeconds) else ""
      valueHeader.text = PhantomRedisBundle.message("value.header", key.name, value.type, size, ttl)
      valueHolder.removeAll()
      valueHolder.add(when (value) {
        is RedisValue.Text -> JBScrollPane(JBTextArea(value.text).apply { isEditable = false; lineWrap = true; font = JBUI.Fonts.create(java.awt.Font.MONOSPACED, font.size) })
        is RedisValue.Rows -> JBScrollPane(JBTable(object : DefaultTableModel(value.rows.map { it.toTypedArray<Any>() }.toTypedArray(), value.columns.toTypedArray()) {
          override fun isCellEditable(row: Int, column: Int) = false
        }))
      }, BorderLayout.CENTER)
      valueHolder.revalidate()
      valueHolder.repaint()
    }
  }

  private fun deleteKey() {
    val key = keys.selectedValue ?: return
    if (Messages.showYesNoDialog(project, PhantomRedisBundle.message("dialog.delete.key.message", key.name),
                                 PhantomRedisBundle.message("dialog.delete.key.title"), null) != Messages.YES) return
    onConnection({ it.command("DEL", key.name) }, { keysStatus.text = it }) { loadKeys() }
  }

  private fun runConsole() {
    val typed = consoleInput.text.trim()
    if (typed.isEmpty()) return
    val args = RedisText.split(typed)
    val bytes = RedisText.splitBytes(typed)
    // passwords never reach the output or the history (screen sharing, the up arrow)
    val line = RedisConsole.shown(typed, args)
    if (line == typed && history.lastOrNull() != typed) history.add(typed)
    historyIndex = history.size
    consoleInput.text = ""
    RedisConsole.refusal(args)?.let {
      append("> $line\n(error) $it\n")
      return
    }
    // a key deleted from the list asks first; so does a typed command deleting all of them, naming the server
    val server = servers.selectedItem as? RedisServer ?: return
    RedisConsole.wipeScope(args, "$server (${server.host}:${server.port})")?.let { scope ->
      val answer = Messages.showOkCancelDialog(project, PhantomRedisBundle.message("dialog.wipe.message", args[0].uppercase(), scope),
                                               PhantomRedisBundle.message("dialog.wipe.title"), PhantomRedisBundle.message("dialog.wipe.ok"),
                                               Messages.getCancelButton(), Messages.getWarningIcon())
      if (answer != Messages.OK) {
        append("> $line\n${PhantomRedisBundle.message("console.cancelled")}\n")
        return
      }
    }
    // rendered and clipped off the EDT: a reply of a million lines froze the text area
    onConnection({ RedisConsole.clip(it.command(bytes).render()) }, { append("> $line\n(error) $it\n") }) { reply ->
      append("> $line\n$reply\n")
      if (args.firstOrNull()?.uppercase() in WRITES) loadKeys()
    }
  }

  private fun append(text: String) {
    consoleOutput.append(text)
    // the output keeps only its end, so a long session does not grow without bound
    val document = consoleOutput.document
    if (document.length > CONSOLE_CHARS) document.remove(0, document.length - CONSOLE_CHARS)
    consoleOutput.caretPosition = consoleOutput.document.length
  }

  private fun closeConnection() = executor.execute { closeConnectionNow() }

  private fun closeConnectionNow() {
    connection?.close()
    connection = null
    connectedTo = null
  }

  override fun dispose() {
    closeConnection()
  }

  private companion object {
    const val CONSOLE_CHARS = 1_000_000
    val WRITES = setOf("SET", "DEL", "UNLINK", "EXPIRE", "PERSIST", "RENAME", "HSET", "HDEL", "LPUSH", "RPUSH", "SADD", "SREM", "ZADD", "ZREM",
                       "XADD", "FLUSHDB", "FLUSHALL", "MSET", "SETEX", "INCR", "DECR", "APPEND")
  }
}
