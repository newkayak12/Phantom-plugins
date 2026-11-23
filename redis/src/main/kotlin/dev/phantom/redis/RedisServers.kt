// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.redis

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection
import java.util.UUID
import javax.swing.JComponent
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

@Tag("server")
class RedisServer {
  var id: String = UUID.randomUUID().toString()
  var name: String = "localhost"
  var host: String = "localhost"
  var port: Int = 6379
  var user: String = ""
  var database: Int = 0
  var tls: Boolean = false

  fun copy(): RedisServer = RedisServer().also {
    it.id = id; it.name = name; it.host = host; it.port = port; it.user = user; it.database = database; it.tls = tls
  }

  override fun toString(): String = if (name == "$host:$port" || name.isBlank()) "$host:$port/$database" else "$name ($host:$port/$database)"
}

/** Saved Redis servers of the project, in the workspace file; passwords go to the password safe. */
@Service(Service.Level.PROJECT)
@State(name = "PhantomRedisServers", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class RedisServers : PersistentStateComponent<RedisServers.State> {
  class State {
    @XCollection(style = XCollection.Style.v2)
    var servers: MutableList<RedisServer> = ArrayList()
  }

  private var state = State()
  override fun getState(): State = state
  override fun loadState(state: State) { this.state = state }

  /** With nothing saved yet, the local default, so a project with Redis in compose works at once. */
  val servers: List<RedisServer> get() = state.servers.toList().ifEmpty { listOf(RedisServer().apply { id = LOCAL_ID; name = "localhost:6379" }) }

  /** The server only; its password goes through [storePassword], off the EDT. */
  fun save(server: RedisServer) {
    val i = state.servers.indexOfFirst { it.id == server.id }
    if (i >= 0) state.servers[i] = server else state.servers.add(server)
  }

  /** The server only; its password is dropped with [storePassword] and null, off the EDT. */
  fun remove(id: String) {
    state.servers.removeIf { it.id == id }
  }

  /** Blocking: may ask the OS keychain. An empty or null [password] removes the entry. */
  fun storePassword(id: String, password: String?) {
    PasswordSafe.instance.setPassword(credentials(id), password?.ifEmpty { null })
  }

  /** Blocking: may ask the OS keychain. */
  fun password(id: String): String? = PasswordSafe.instance.getPassword(credentials(id))

  companion object {
    const val LOCAL_ID: String = "local-default"

    fun getInstance(project: Project): RedisServers = project.service()

    private fun credentials(id: String) = CredentialAttributes(generateServiceName("Phantom Redis", id))  // keychain entries are stored under this name; kept when the plugin was renamed
  }
}

class RedisServerDialog(project: Project, private val server: RedisServer) : DialogWrapper(project) {
  private val name = JBTextField(server.name)
  private val host = JBTextField(server.host)
  private val port = JSpinner(SpinnerNumberModel(server.port, 1, 65535, 1)).apply { editor = JSpinner.NumberEditor(this, "#") }
  private val user = JBTextField(server.user).apply { emptyText.text = "default" }
  private val password = JBPasswordField().apply { emptyText.text = "unchanged" }
  private val database = JSpinner(SpinnerNumberModel(server.database, 0, 255, 1))
  private val tls = JBCheckBox("TLS", server.tls)

  init {
    title = "Redis Server"
    init()
  }

  override fun createCenterPanel(): JComponent = FormBuilder.createFormBuilder()
    .addLabeledComponent("Name:", name)
    .addLabeledComponent("Host:", host)
    .addLabeledComponent("Port:", port)
    .addLabeledComponent("User:", user)
    .addLabeledComponent("Password:", password)
    .addLabeledComponent("Database:", database)
    .addComponent(tls)
    .panel

  /** The edited server, and the password if one was typed (null: keep the saved one). */
  fun result(): Pair<RedisServer, CharArray?> {
    val s = server.copy()
    s.host = host.text.trim().ifEmpty { "localhost" }
    s.port = port.value as Int
    s.name = name.text.trim().ifEmpty { "${s.host}:${s.port}" }
    s.user = user.text.trim()
    s.database = database.value as Int
    s.tls = tls.isSelected
    if (s.id == RedisServers.LOCAL_ID) s.id = UUID.randomUUID().toString()
    return s to password.password.takeIf { it.isNotEmpty() }
  }
}
