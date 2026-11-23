// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.kafka

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
import com.intellij.ui.components.*
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.table.JBTable
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.ExecutionException
import javax.swing.*
import javax.swing.table.AbstractTableModel

class KafkaToolWindowFactory : ToolWindowFactory, DumbAware {
  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val panel = KafkaPanel(project, toolWindow.disposable)
    toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(panel, null, false))
  }
}

/** A table over a list of rows; [cells] turns a row into its column values. */
private class RowsModel<T>(private val columns: List<String>, private val cells: (T) -> List<Any?>) : AbstractTableModel() {
  var rows: List<T> = emptyList()
    set(value) { field = value; fireTableDataChanged() }

  override fun getRowCount() = rows.size
  override fun getColumnCount() = columns.size
  override fun getColumnName(column: Int) = columns[column]
  override fun getValueAt(row: Int, column: Int): Any? = cells(rows[row])[column]
}

/**
 * Topics on the left; on the right the selected topic's messages (read on demand, newest first), a form to send one,
 * its partitions, and the cluster's consumer groups with lag. Clients are created on first use on one background
 * thread and closed when the window is hidden.
 */
class KafkaPanel(private val project: Project, parent: Disposable) : JPanel(BorderLayout()), Disposable {
  private val executor = AppExecutorUtil.createBoundedApplicationPoolExecutor("Kafka Support", 1)
  private val clusters = ComboBox<KafkaCluster>()
  private val topicFilter = SearchTextField(false)
  private val showInternal = JBCheckBox("Internal")
  private var allTopics: List<KafkaTopic> = emptyList()
  private val topicsModel = DefaultListModel<KafkaTopic>()
  private val topics = JBList(topicsModel)
  private val status = JBLabel().apply { border = JBUI.Borders.empty(2, 6); foreground = UIUtil.getContextHelpForeground() }

  private val messagesModel = RowsModel<KafkaMessage>(listOf("Partition", "Offset", "Time", "Key", "Value")) {
    listOf(it.partition, it.offset, TIME.format(Date(it.timestamp)), it.key ?: "", it.value?.replace('\n', ' ') ?: "null")
  }
  private val messages = JBTable(messagesModel)
  private val startMode = ComboBox(arrayOf("Latest", "Earliest", "Since minutes ago"))
  private val count = JBIntSpinner(50, 1, 10_000)
  private val minutes = JBIntSpinner(15, 1, 60 * 24 * 30)

  private val produceKey = JBTextField()
  private val produceValue = JBTextArea(6, 40)
  private val produceHeaders = JBTextArea(2, 40).apply { emptyText.text = "name=value per line" }
  private val producePartition = JBTextField().apply { emptyText.text = "any" }

  private val partitionsModel = RowsModel<KafkaPartition>(listOf("Partition", "Leader", "Replicas", "In sync", "First offset", "End offset", "Messages")) {
    listOf(it.partition, it.leader, it.replicas, it.inSync, it.begin, it.end, it.end - it.begin)
  }
  private val groupsModel = RowsModel<KafkaGroupLag>(listOf("Group", "State", "Members", "Topic", "Lag")) {
    listOf(it.group, it.state, it.members, it.topic, it.lag)
  }
  private val tabs = JBTabbedPane()
  private val reads = KafkaTopicLoads()

  // touched only on the executor thread
  private var session: KafkaSession? = null
  private var sessionFor: String? = null

  init {
    Disposer.register(parent, this)
    reloadClusters(null)
    clusters.addActionListener { closeSession(); loadTopics() }

    topics.cellRenderer = object : ColoredListCellRenderer<KafkaTopic>() {
      override fun customizeCellRenderer(list: JList<out KafkaTopic>, value: KafkaTopic, index: Int, selected: Boolean, hasFocus: Boolean) {
        append(value.name, if (value.internal) SimpleTextAttributes.GRAYED_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
        append("  ${value.partitions}p", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
      }
    }
    topics.selectionMode = ListSelectionModel.SINGLE_SELECTION
    topics.addListSelectionListener { if (!it.valueIsAdjusting) topicSelected() }
    topicFilter.addDocumentListener(object : DocumentAdapter() {
      override fun textChanged(e: javax.swing.event.DocumentEvent) = showTopics()
    })
    showInternal.addActionListener { loadTopics() }

    messages.emptyText.text = "Choose a topic, then Read"
    object : DoubleClickListener() {
      override fun onDoubleClick(event: MouseEvent): Boolean { showMessage(); return true }
    }.installOn(messages)

    val read = JButton("Read").apply { addActionListener { readMessages() } }
    val readBar = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
      add(startMode); add(JBLabel("count per partition:")); add(count); add(JBLabel("minutes:")); add(minutes); add(read)
    }
    minutes.isVisible = false
    startMode.addActionListener { minutes.isVisible = startMode.selectedIndex == 2; readBar.revalidate() }
    val messagesTab = JPanel(BorderLayout()).apply {
      add(readBar, BorderLayout.NORTH)
      add(ScrollPaneFactory.createScrollPane(messages, true), BorderLayout.CENTER)
    }
    val send = JButton("Send").apply { addActionListener { produce() } }
    val produceTab = FormBuilder.createFormBuilder()
      .addLabeledComponent("Key:", produceKey)
      .addLabeledComponent("Value:", JBScrollPane(produceValue), true)
      .addLabeledComponent("Headers:", JBScrollPane(produceHeaders), true)
      .addLabeledComponent("Partition:", producePartition)
      .addComponent(send)
      .addComponentFillVertically(JPanel(), 0)
      .panel.apply { border = JBUI.Borders.empty(6) }
    tabs.addTab("Messages", messagesTab)
    tabs.addTab("Send", produceTab)
    tabs.addTab("Partitions", ScrollPaneFactory.createScrollPane(JBTable(partitionsModel), true))
    tabs.addTab("Consumer Groups", ScrollPaneFactory.createScrollPane(JBTable(groupsModel), true))
    tabs.addChangeListener { if (tabs.selectedIndex == 3) loadGroups() else if (tabs.selectedIndex == 2) loadPartitions() }

    val actions = DefaultActionGroup(
      action("Add Cluster…", AllIcons.General.Add) { editCluster(KafkaCluster()) },
      action("Edit Cluster…", AllIcons.Actions.Edit) { (clusters.selectedItem as? KafkaCluster)?.let { editCluster(it.copy()) } },
      action("Remove Cluster", AllIcons.General.Remove) { removeCluster() },
      Separator.getInstance(),
      action("Refresh", AllIcons.Actions.Refresh) { loadTopics() },
      action("Create Topic…", AllIcons.Actions.AddList) { createTopic() },
      action("Delete Topic…", AllIcons.Actions.GC) { deleteTopic() },
    )
    val toolbar = ActionManager.getInstance().createActionToolbar("PhantomKafka", actions, true).apply { targetComponent = this@KafkaPanel }
    val top = JPanel(BorderLayout()).apply {
      add(clusters, BorderLayout.WEST)
      add(toolbar.component, BorderLayout.CENTER)
    }
    val left = JPanel(BorderLayout()).apply {
      add(JPanel(BorderLayout()).apply { add(topicFilter, BorderLayout.CENTER); add(showInternal, BorderLayout.EAST) }, BorderLayout.NORTH)
      add(ScrollPaneFactory.createScrollPane(topics, true), BorderLayout.CENTER)
      add(status, BorderLayout.SOUTH)
    }
    add(top, BorderLayout.NORTH)
    add(JBSplitter(false, "phantom.kafka.topics", 0.3f).apply { firstComponent = left; secondComponent = tabs }, BorderLayout.CENTER)
  }

  private fun action(text: String, icon: Icon, perform: () -> Unit) = object : DumbAwareAction(text, null, icon) {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun actionPerformed(e: AnActionEvent) = perform()
  }

  override fun addNotify() {
    super.addNotify()
    if (allTopics.isEmpty()) loadTopics()
  }

  override fun removeNotify() {
    super.removeNotify()
    closeSession()
  }

  private fun reloadClusters(select: String?) {
    val list = KafkaClusters.getInstance(project).clusters
    clusters.model = DefaultComboBoxModel(list.toTypedArray())
    clusters.selectedItem = list.firstOrNull { it.id == select } ?: list.firstOrNull()
  }

  private fun editCluster(cluster: KafkaCluster) {
    executor.execute {
      val props = KafkaClusters.getInstance(project).properties(cluster.id).orEmpty()
      ApplicationManager.getApplication().invokeLater({
        val dialog = KafkaClusterDialog(project, cluster, props)
        if (dialog.showAndGet()) {
          val (edited, properties) = dialog.result()
          val clusters = KafkaClusters.getInstance(project)
          clusters.save(edited)
          // The password safe may block on the OS keychain. On the session executor the write also lands before the
          // session that reloadClusters starts reads the properties.
          executor.execute { clusters.storeProperties(edited.id, properties) }
          reloadClusters(edited.id)
        }
      }, ModalityState.nonModal())
    }
  }

  private fun removeCluster() {
    val cluster = clusters.selectedItem as? KafkaCluster ?: return
    if (cluster.id == KafkaClusters.LOCAL_ID) return
    if (Messages.showYesNoDialog(project, "Remove $cluster?", "Remove Kafka Cluster", null) != Messages.YES) return
    val clusters = KafkaClusters.getInstance(project)
    clusters.remove(cluster.id)
    // The password safe may block on the OS keychain.
    executor.execute { clusters.storeProperties(cluster.id, null) }
    reloadClusters(null)
  }

  /** [current] false when the result arrives means it no longer belongs on screen: dropped, status included. */
  private fun <T> onSession(what: String, task: (KafkaSession) -> T, current: () -> Boolean = { true }, done: (T) -> Unit) {
    val cluster = clusters.selectedItem as? KafkaCluster ?: return
    status.text = "$what…"
    executor.execute {
      val result = runCatching {
        val s = session?.takeIf { sessionFor == cluster.id } ?: run {
          closeSessionNow()
          KafkaSession(cluster.bootstrap, KafkaClusters.getInstance(project).properties(cluster.id)).also { session = it; sessionFor = cluster.id }
        }
        task(s)
      }
      ApplicationManager.getApplication().invokeLater({
        if (!current()) return@invokeLater
        result.fold({ status.text = ""; done(it) }) { e ->
          val cause = if (e is ExecutionException) e.cause ?: e else e
          status.text = "${cause.javaClass.simpleName}: ${cause.message ?: ""}".take(200)
        }
      }, ModalityState.any()) { Disposer.isDisposed(this) }
    }
  }

  private fun loadTopics() {
    reads.invalidate()
    onSession("Loading topics", { it.topics(showInternal.isSelected) }) { list ->
      allTopics = list
      showTopics()
      status.text = "${list.size} topics"
    }
  }

  private fun showTopics() {
    val selected = topics.selectedValue?.name
    val query = topicFilter.text.trim().lowercase()
    topicsModel.clear()
    allTopics.filter { query.isEmpty() || query in it.name.lowercase() }.forEach(topicsModel::addElement)
    (0 until topicsModel.size()).firstOrNull { topicsModel[it].name == selected }?.let { topics.selectedIndex = it }
    topics.emptyText.text = if (allTopics.isEmpty()) "No topics" else "No topic matches"
  }

  private fun topicSelected() {
    reads.invalidate()
    messagesModel.rows = emptyList()
    partitionsModel.rows = emptyList()
    if (tabs.selectedIndex == 2) loadPartitions()
  }

  private fun readMessages() {
    val topic = topics.selectedValue?.name ?: return
    val n = count.number
    val start = when (startMode.selectedIndex) {
      0 -> KafkaStart.Latest(n)
      1 -> KafkaStart.Earliest(n)
      else -> KafkaStart.Since(System.currentTimeMillis() - minutes.number * 60_000L, n)
    }
    messages.emptyText.text = "Reading…"
    val request = reads.messages.start(topic)
    onSession("Reading $topic", { it.consume(topic, start, cancelled = { request.cancelled }) },
              { reads.messages.isCurrent(request, topics.selectedValue?.name) }) { result ->
      messagesModel.rows = result.messages
      val (empty, summary) = KafkaReads.describe(result)
      messages.emptyText.text = empty
      status.text = summary
    }
  }

  private fun showMessage() {
    val row = messages.selectedRow.takeIf { it >= 0 } ?: return
    val m = messagesModel.rows[messages.convertRowIndexToModel(row)]
    val text = buildString {
      append("partition ${m.partition} · offset ${m.offset} · ${TIME.format(Date(m.timestamp))}\n")
      if (m.headers.isNotEmpty()) append("headers: ${m.headers.joinToString(", ") { "${it.first}=${it.second}" }}\n")
      append("key: ${m.key ?: "null"}\n\n")
      append(KafkaJson.pretty(m.value ?: "null"))
    }
    val area = JBTextArea(text).apply { isEditable = false; font = JBUI.Fonts.create(java.awt.Font.MONOSPACED, font.size) }
    val dialog = object : com.intellij.openapi.ui.DialogWrapper(project) {
      init { title = "Message"; setOKButtonText("Copy Value"); init() }
      override fun createCenterPanel(): JComponent = JBScrollPane(area).apply { preferredSize = JBUI.size(640, 420) }
      override fun doOKAction() { CopyPasteManager.getInstance().setContents(StringSelection(m.value ?: "")); super.doOKAction() }
    }
    dialog.show()
  }

  private fun produce() {
    val topic = topics.selectedValue?.name ?: run { status.text = "Choose a topic first"; return }
    val key = produceKey.text.takeIf { it.isNotEmpty() }
    val value = produceValue.text
    val headers = KafkaText.headers(produceHeaders.text)
    val partition = producePartition.text.trim().toIntOrNull()
    onSession("Sending to $topic", { it.produce(topic, key, value, headers, partition) }) { (p, offset) ->
      status.text = "Sent to partition $p at offset $offset"
    }
  }

  private fun loadPartitions() {
    val topic = topics.selectedValue?.name ?: return
    val request = reads.partitions.start(topic)
    onSession("Describing $topic", { it.partitions(topic) }, { reads.partitions.isCurrent(request, topics.selectedValue?.name) }) {
      partitionsModel.rows = it
    }
  }

  private fun loadGroups() {
    onSession("Loading consumer groups", { it.groups() }) { groupsModel.rows = it }
  }

  private fun createTopic() {
    val name = Messages.showInputDialog(project, "Topic name:", "Create Topic", null)?.trim()?.takeIf { it.isNotEmpty() } ?: return
    val partitions = Messages.showInputDialog(project, "Partitions:", "Create Topic", null, "3", null)?.trim()?.toIntOrNull() ?: return
    // empty (the default): the cluster's default.replication.factor, not a value that would override it
    val typed = Messages.showInputDialog(project, "Replication factor (empty: the cluster's default):", "Create Topic", null, "", null)?.trim() ?: return
    val replication = if (typed.isEmpty()) null else typed.toShortOrNull() ?: return
    if (replication == 1.toShort() && Messages.showOkCancelDialog(project,
        "'$name' will have a single replica: its data is lost if that broker fails. Create it anyway?",
        "Create Topic", "Create", Messages.getCancelButton(), Messages.getWarningIcon()) != Messages.OK) return
    onSession("Creating $name", { it.createTopic(name, partitions, replication) }) { loadTopics() }
  }

  private fun deleteTopic() {
    val topic = topics.selectedValue?.name ?: return
    if (Messages.showYesNoDialog(project, "Delete topic '$topic' and all its messages?", "Delete Topic", Messages.getWarningIcon()) != Messages.YES) return
    onSession("Deleting $topic", { it.deleteTopic(topic) }) { loadTopics() }
  }

  private fun closeSession() {
    reads.invalidate()
    executor.execute { closeSessionNow() }
  }

  private fun closeSessionNow() {
    session?.close()
    session = null
    sessionFor = null
  }

  override fun dispose() = closeSession()

  private companion object {
    val TIME = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")
  }
}

/**
 * Which message read may still fill the table. Each read gets a fresh id; choosing another topic, refreshing, switching
 * cluster or closing the window cancels the one in flight, so a slow read of topic A can never land under topic B.
 * EDT only, except [Request.cancelled], which the poll loop reads.
 */
internal class KafkaReads {
  class Request(val id: Long, val topic: String) {
    @Volatile var cancelled = false
  }

  private var generation = 0L
  private var current: Request? = null

  fun start(topic: String): Request {
    invalidate()
    return Request(++generation, topic).also { current = it }
  }

  fun invalidate() {
    current?.cancelled = true
    current = null
  }

  fun isCurrent(request: Request, selectedTopic: String?): Boolean =
    !request.cancelled && request.id == current?.id && request.topic == selectedTopic

  companion object {
    /** The table's empty text and the status line; a read cut off by the timeout says so instead of looking complete. */
    fun describe(result: KafkaReadResult): Pair<String, String> {
      val n = result.messages.size
      if (!result.partial) return "No messages" to "$n messages"
      val unfinished = "${result.unfinishedPartitions} ${if (result.unfinishedPartitions == 1) "partition" else "partitions"} " +
                       "not finished within ${result.timeoutMs / 1000} s"
      val empty = "No messages read yet: $unfinished"
      return empty to if (n == 0) empty else "$n messages (partial: $unfinished)"
    }
  }
}

/** One guard per tab that loads per topic; whatever changes the topic or the cluster invalidates them all. */
internal class KafkaTopicLoads {
  val messages = KafkaReads()
  val partitions = KafkaReads()

  fun invalidate() {
    messages.invalidate()
    partitions.invalidate()
  }
}

object KafkaJson {
  /** A JSON object or array indented by two spaces; anything else unchanged. */
  fun pretty(text: String): String {
    val t = text.trim()
    if (!(t.startsWith("{") && t.endsWith("}")) && !(t.startsWith("[") && t.endsWith("]"))) return text
    val sb = StringBuilder()
    var depth = 0
    var inString = false
    var escaped = false
    fun newline() { sb.append('\n'); repeat(depth) { sb.append("  ") } }
    for (i in t.indices) {
      val c = t[i]
      if (inString) {
        sb.append(c)
        if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') inString = false
        continue
      }
      when (c) {
        '"' -> { inString = true; sb.append(c) }
        '{', '[' -> {
          val close = if (c == '{') '}' else ']'
          sb.append(c)
          depth++
          if (t.substring(i + 1).trimStart().firstOrNull() != close) newline()
        }
        '}', ']' -> {
          depth--
          if (sb.last() != '{' && sb.last() != '[') newline()
          sb.append(c)
        }
        ',' -> { sb.append(c); newline() }
        ':' -> sb.append(": ")
        else -> if (!c.isWhitespace()) sb.append(c)
      }
      if (depth < 0) return text
    }
    return if (depth == 0 && !inString) sb.toString() else text
  }
}
