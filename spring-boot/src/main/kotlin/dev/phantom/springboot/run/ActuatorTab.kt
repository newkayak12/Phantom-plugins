// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.springboot.run

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.diagnostic.logging.AdditionalTabComponent
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.util.Key
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.table.JBTable
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableRowSorter

/**
 * Where the running application listens, read from its own startup log: `Tomcat started on port 8080 (http) with
 * context path '/api'` (Boot 3) or `Tomcat started on port(s): 8080 (http) with context path ''` (Boot 2). The second
 * server line, if any, is the management server.
 */
object ActuatorAddress {
  private val STARTED = Regex("""(Tomcat|Netty|Jetty|Undertow) started on port(?:\(s\))?:?\s*(\d+)(?:\s*\((https?)\))?(?:.*?with context path '([^']*)')?""")

  data class Server(val port: Int, val scheme: String, val contextPath: String)

  fun parse(line: String): Server? {
    val m = STARTED.find(line) ?: return null
    return Server(m.groupValues[2].toInt(), m.groupValues[3].ifEmpty { "http" }, m.groupValues[4].trim('/'))
  }

  /** The actuator root: the management server if one started, else the application server. */
  fun baseUrl(servers: List<Server>, basePath: String = "actuator"): String? {
    val server = (if (servers.size > 1) servers[1] else servers.firstOrNull()) ?: return null
    val context = if (servers.size > 1) "" else server.contextPath.let { if (it.isEmpty()) "" else "/$it" }
    return "${server.scheme}://localhost:${server.port}$context/${basePath.trim('/')}"
  }
}

/** Rows of the four views, from the actuator JSON of Boot 2 and 3. */
object ActuatorJson {
  data class Bean(val name: String, val type: String, val scope: String, val dependencies: String, val context: String)
  data class Mapping(val methods: String, val path: String, val handler: String)
  data class Property(val name: String, val value: String, val source: String)

  fun beans(root: JsonElement): List<Bean> {
    val out = ArrayList<Bean>()
    val contexts = root.asJsonObject.getAsJsonObject("contexts") ?: return out
    for ((contextName, context) in contexts.entrySet()) {
      val beans = context.asJsonObject.getAsJsonObject("beans") ?: continue
      for ((name, bean) in beans.entrySet()) {
        val o = bean.asJsonObject
        out.add(Bean(name, o.str("type"), o.str("scope"), o.getAsJsonArray("dependencies")?.joinToString(", ") { it.asString }.orEmpty(), contextName))
      }
    }
    return out.sortedBy { it.name.lowercase() }
  }

  fun mappings(root: JsonElement): List<Mapping> {
    val out = ArrayList<Mapping>()
    val contexts = root.asJsonObject.getAsJsonObject("contexts") ?: return out
    for ((_, context) in contexts.entrySet()) {
      val mappings = context.asJsonObject.getAsJsonObject("mappings") ?: continue
      // servlet apps: dispatcherServlets.<name>[]; WebFlux: dispatcherHandlers.<name>[]
      for (kind in listOf("dispatcherServlets", "dispatcherHandlers")) {
        val dispatchers = mappings.getAsJsonObject(kind) ?: continue
        for ((_, list) in dispatchers.entrySet()) {
          for (item in list.asJsonArray) {
            val o = item.asJsonObject
            val conditions = o.getAsJsonObject("details")?.getAsJsonObject("requestMappingConditions")
            if (conditions != null) {
              val methods = conditions.getAsJsonArray("methods")?.joinToString("|") { it.asString }.orEmpty().ifEmpty { "ANY" }
              val patterns = conditions.getAsJsonArray("patterns")?.map { it.asString }.orEmpty()
              val handler = o.getAsJsonObject("details")?.getAsJsonObject("handlerMethod")?.let { h ->
                h.str("className").substringAfterLast('.') + "." + h.str("name")
              } ?: o.str("handler")
              for (p in patterns.ifEmpty { listOf(o.str("predicate")) }) out.add(Mapping(methods, p, handler))
            }
            else {
              out.add(Mapping("", o.str("predicate"), o.str("handler")))
            }
          }
        }
      }
    }
    return out.sortedWith(compareBy({ it.path }, { it.methods }))
  }

  /** The effective value of each property: the first property source that has it wins. */
  fun environment(root: JsonElement): List<Property> {
    val seen = LinkedHashMap<String, Property>()
    val sources = root.asJsonObject.getAsJsonArray("propertySources") ?: return emptyList()
    for (source in sources) {
      val s = source.asJsonObject
      val sourceName = s.str("name")
      val props = s.getAsJsonObject("properties") ?: continue
      for ((name, value) in props.entrySet()) {
        val v = (value as? JsonObject)?.get("value")?.let { if (it.isJsonPrimitive) it.asString else it.toString() } ?: value.toString()
        seen.putIfAbsent(name, Property(name, v, sourceName))
      }
    }
    return seen.values.sortedBy { it.name }
  }

  /** Boot 3 masks every value (`******`) unless `management.endpoint.env.show-values` says otherwise. */
  fun masked(properties: List<Property>): Boolean = properties.isNotEmpty() && properties.all { it.value == "******" }

  const val MASKED_HINT: String = "Boot hides the values. Set management.endpoint.env.show-values=always (or when-authorized) to see them."

  /** `UP`, plus one line per component: `db: UP (MySQL)`. */
  fun health(root: JsonElement): Pair<String, List<String>> {
    val o = root.asJsonObject
    val lines = ArrayList<String>()
    fun walk(prefix: String, node: JsonObject) {
      val components = node.getAsJsonObject("components") ?: node.getAsJsonObject("details")?.takeIf { d -> d.entrySet().all { it.value.isJsonObject && (it.value as JsonObject).has("status") } }
      components?.entrySet()?.forEach { (name, value) ->
        val c = value as? JsonObject ?: return@forEach
        val detail = c.getAsJsonObject("details")?.let { d -> d.get("database")?.asString ?: d.get("version")?.asString }
        lines.add("$prefix$name: ${c.str("status")}${detail?.let { " ($it)" }.orEmpty()}")
        walk("$prefix$name.", c)
      }
    }
    walk("", o)
    return o.str("status") to lines
  }

  private fun JsonObject.str(name: String): String = get(name)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
}

/** One actuator endpoint as JSON. Blocking: call it off the EDT. */
object ActuatorClient {
  fun newClient(): HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()

  fun get(client: HttpClient, base: String, endpoint: String): JsonElement {
    val response = client.send(HttpRequest.newBuilder(URI("$base/$endpoint")).timeout(Duration.ofSeconds(5))
                                 .header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofString())
    // health answers 503 with a body when the application is DOWN
    if (response.statusCode() == 404) {
      throw IllegalStateException(if (endpoint == "health") "No /actuator/health: is spring-boot-starter-actuator a dependency, and is the health endpoint exposed?"
                                  else "/actuator/$endpoint is not exposed. Add it to management.endpoints.web.exposure.include")
    }
    if (response.statusCode() >= 400 && !(endpoint == "health" && response.statusCode() == 503)) throw IllegalStateException("HTTP ${response.statusCode()} from /actuator/$endpoint")
    return JsonParser.parseString(response.body())
  }
}

/**
 * The "Actuator" tab of a running Spring Boot application, next to its console in Run and Services. It waits for the
 * startup log line with the port, then reads `/actuator/health`, `beans`, `mappings` and `env`. Requests go out only
 * while the tab is on screen; health refreshes every five seconds while its sub-tab is selected, the others on demand.
 */
class ActuatorTab(private val process: ProcessHandler?) : AdditionalTabComponent(BorderLayout()) {
  private val servers = ArrayList<ActuatorAddress.Server>()
  private val status = JBLabel("Waiting for the application to start…").apply { border = JBUI.Borders.empty(4, 8) }
  private val tabs = JBTabbedPane()
  private val health = JBLabel().apply { verticalAlignment = JBLabel.TOP; border = JBUI.Borders.empty(8) }
  private val beans = RowView(listOf("Bean", "Type", "Scope", "Dependencies")) { b: ActuatorJson.Bean -> listOf(b.name, b.type, b.scope, b.dependencies) }
  private val mappings = RowView(listOf("Method", "Path", "Handler")) { m: ActuatorJson.Mapping -> listOf(m.methods, m.path, m.handler) }
  private val env = RowView(listOf("Property", "Value", "Source")) { p: ActuatorJson.Property -> listOf(p.name, p.value, p.source) }
  private val poll = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
  private val loaded = HashSet<Int>()
  private val client: HttpClient by lazy { ActuatorClient.newClient() }

  init {
    tabs.addTab("Health", ScrollPaneFactory.createScrollPane(health, true))
    tabs.addTab("Beans", beans)
    tabs.addTab("Mappings", mappings)
    tabs.addTab("Environment", env)
    tabs.addChangeListener {
      if (tabs.selectedIndex != HEALTH) poll.cancelAllRequests()
      if (isShowing) load(tabs.selectedIndex, force = false)
    }
    add(status, BorderLayout.NORTH)
    add(tabs, BorderLayout.CENTER)
    process?.addProcessListener(object : ProcessListener {
      override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
        val server = ActuatorAddress.parse(event.text) ?: return
        synchronized(servers) { servers.add(server) }
        ApplicationManager.getApplication().invokeLater({ started() }, ModalityState.any())
      }

      override fun processTerminated(event: ProcessEvent) {
        ApplicationManager.getApplication().invokeLater({ status.text = "The application has stopped"; poll.cancelAllRequests() }, ModalityState.any())
      }
    }, this)
  }

  private fun baseUrl(): String? = synchronized(servers) { ActuatorAddress.baseUrl(servers) }

  private fun started() {
    status.text = baseUrl() ?: return
    loaded.clear()
    if (isShowing) load(tabs.selectedIndex, force = true)
  }

  override fun addNotify() {
    super.addNotify()
    if (baseUrl() != null) load(tabs.selectedIndex, force = false)
  }

  override fun removeNotify() {
    super.removeNotify()
    poll.cancelAllRequests()
  }

  private fun load(tab: Int, force: Boolean) {
    val base = baseUrl() ?: return
    if (tab == 0) {
      poll.cancelAllRequests()
      poll.addRequest({ fetch(base, 0) }, 0)
      return
    }
    if (!force && tab in loaded) return
    loaded.add(tab)
    ApplicationManager.getApplication().executeOnPooledThread { fetch(base, tab) }
  }

  private fun fetch(base: String, tab: Int) {
    val endpoint = ENDPOINTS[tab]
    val result: Result<JsonElement> = runCatching { ActuatorClient.get(client, base, endpoint) }
    ApplicationManager.getApplication().invokeLater({
      result.fold({ show(tab, it) }, { e ->
        loaded.remove(tab)
        val message = if (e is java.net.ConnectException) "Cannot connect to $base" else e.message ?: e.javaClass.simpleName
        when (tab) {
          0 -> health.text = message
          1 -> beans.showError(message)
          2 -> mappings.showError(message)
          else -> env.showError(message)
        }
      })
    }, ModalityState.any())
    if (tab == HEALTH && pollsHealth(tabs.selectedIndex, isShowing, process?.isProcessTerminated == true)) {
      poll.addRequest({ fetch(base, HEALTH) }, HEALTH_PERIOD_MS)
    }
  }

  private fun show(tab: Int, json: JsonElement) {
    runCatching {
      when (tab) {
        0 -> {
          val (overall, lines) = ActuatorJson.health(json)
          health.text = "<html><b>$overall</b><br>" + lines.joinToString("<br>") { com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(it) } + "</html>"
        }
        1 -> beans.show(ActuatorJson.beans(json))
        2 -> mappings.show(ActuatorJson.mappings(json))
        else -> ActuatorJson.environment(json).let { env.show(it, if (ActuatorJson.masked(it)) ActuatorJson.MASKED_HINT else null) }
      }
    }.onFailure { e -> if (tab == 0) health.text = "Unexpected answer: ${e.message}" }
  }

  override fun getTabTitle(): String = "Actuator"
  override fun getPreferredFocusableComponent(): JComponent = tabs
  override fun getToolbarActions(): ActionGroup = DefaultActionGroup(object : DumbAwareAction("Refresh", null, AllIcons.Actions.Refresh) {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun update(e: AnActionEvent) { e.presentation.isEnabled = baseUrl() != null }
    override fun actionPerformed(e: AnActionEvent) = load(tabs.selectedIndex, force = true)
  })
  override fun getSearchComponent(): JComponent? = null
  override fun getToolbarPlace(): String = "PhantomActuator"
  override fun getToolbarContextComponent(): JComponent = this
  override fun isContentBuiltIn(): Boolean = false
  override fun dispose() {}

  /** A filterable table; double-click copies the row. */
  private class RowView<T>(columns: List<String>, private val cells: (T) -> List<String>) : JPanel(BorderLayout()) {
    private var rows: List<List<String>> = emptyList()
    private val model = object : AbstractTableModel() {
      override fun getRowCount() = rows.size
      override fun getColumnCount() = columns.size
      override fun getColumnName(column: Int) = columns[column]
      override fun getValueAt(row: Int, column: Int) = rows[row][column]
    }
    private val table = JBTable(model)
    private val note = JBLabel().apply { border = JBUI.Borders.empty(4, 8); isVisible = false }
    private val sorter = TableRowSorter(model)
    private val filter = SearchTextField(false)

    init {
      table.rowSorter = sorter
      table.emptyText.text = "Loading…"
      filter.addDocumentListener(object : DocumentAdapter() {
        override fun textChanged(e: DocumentEvent) {
          val words = filter.text.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
          sorter.rowFilter = if (words.isEmpty()) null else object : javax.swing.RowFilter<AbstractTableModel, Int>() {
            override fun include(entry: Entry<out AbstractTableModel, out Int>): Boolean {
              val text = rows[entry.identifier].joinToString(" ").lowercase()
              return words.all { it in text }
            }
          }
        }
      })
      object : com.intellij.ui.DoubleClickListener() {
        override fun onDoubleClick(event: java.awt.event.MouseEvent): Boolean {
          val row = table.selectedRow.takeIf { it >= 0 } ?: return false
          CopyPasteManager.getInstance().setContents(StringSelection(rows[table.convertRowIndexToModel(row)].joinToString("\t")))
          return true
        }
      }.installOn(table)
      filter.border = JBUI.Borders.empty(2, 4)
      add(filter, BorderLayout.NORTH)
      add(ScrollPaneFactory.createScrollPane(table, true), BorderLayout.CENTER)
      add(note, BorderLayout.SOUTH)
    }

    fun show(items: List<T>, hint: String? = null) {
      note.text = hint.orEmpty()
      note.isVisible = hint != null
      rows = items.map(cells)
      model.fireTableDataChanged()
      table.emptyText.text = "Nothing reported"
    }

    fun showError(message: String) {
      note.isVisible = false
      rows = emptyList()
      model.fireTableDataChanged()
      table.emptyText.setText(message, com.intellij.ui.SimpleTextAttributes.ERROR_ATTRIBUTES)
    }
  }

  companion object {
    private val ENDPOINTS = listOf("health", "beans", "mappings", "env")
    private const val HEALTH = 0

    /** Health is read again only while its sub-tab is selected, the Actuator tab is on screen and the application runs. */
    internal fun pollsHealth(selectedTab: Int, showing: Boolean, terminated: Boolean): Boolean =
      selectedTab == HEALTH && showing && !terminated
    private const val HEALTH_PERIOD_MS = 5_000
  }
}
