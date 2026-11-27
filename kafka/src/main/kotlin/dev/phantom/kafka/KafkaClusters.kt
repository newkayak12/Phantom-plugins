// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.kafka

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection
import java.util.UUID
import javax.swing.JComponent

@Tag("cluster")
class KafkaCluster {
  var id: String = UUID.randomUUID().toString()
  var name: String = "localhost"
  var bootstrap: String = "localhost:9092"

  fun copy(): KafkaCluster = KafkaCluster().also { it.id = id; it.name = name; it.bootstrap = bootstrap }

  override fun toString(): String = if (name.isBlank() || name == bootstrap) bootstrap else "$name ($bootstrap)"
}

/**
 * Saved clusters of the project in the workspace file. Client properties (SASL, SSL) may hold secrets, so they go to
 * the password safe.
 */
@Service(Service.Level.PROJECT)
@State(name = "PhantomKafkaClusters", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class KafkaClusters : PersistentStateComponent<KafkaClusters.State> {
  class State {
    @XCollection(style = XCollection.Style.v2)
    var clusters: MutableList<KafkaCluster> = ArrayList()
  }

  private var state = State()
  override fun getState(): State = state
  override fun loadState(state: State) { this.state = state }

  val clusters: List<KafkaCluster> get() = state.clusters.toList().ifEmpty { listOf(KafkaCluster().apply { id = LOCAL_ID }) }

  /** The cluster only; its client properties go through [storeProperties], off the EDT. */
  fun save(cluster: KafkaCluster) {
    val i = state.clusters.indexOfFirst { it.id == cluster.id }
    if (i >= 0) state.clusters[i] = cluster else state.clusters.add(cluster)
  }

  /** The cluster only; its client properties are dropped with [storeProperties] and null, off the EDT. */
  fun remove(id: String) {
    state.clusters.removeIf { it.id == id }
  }

  /** Blocking: may ask the OS keychain. Blank or null [properties] removes the entry. */
  fun storeProperties(id: String, properties: String?) {
    PasswordSafe.instance.setPassword(credentials(id), properties?.ifBlank { null })
  }

  /** Blocking: may ask the OS keychain. */
  fun properties(id: String): String? = PasswordSafe.instance.getPassword(credentials(id))

  companion object {
    const val LOCAL_ID: String = "local-default"

    fun getInstance(project: Project): KafkaClusters = project.service()

    private fun credentials(id: String) = CredentialAttributes(generateServiceName("Phantom Kafka", id))  // keychain entries are stored under this name; kept when the plugin was renamed
  }
}

class KafkaClusterDialog(project: Project, private val cluster: KafkaCluster, properties: String) : DialogWrapper(project) {
  private val name = JBTextField(cluster.name)
  private val bootstrap = JBTextField(cluster.bootstrap).apply { emptyText.text = "host1:9092,host2:9092" }
  private val props = JBTextArea(properties, 6, 50).apply {
    emptyText.text = "security.protocol=SASL_SSL\nsasl.mechanism=PLAIN\nsasl.jaas.config=…"
    border = JBUI.Borders.customLine(com.intellij.ui.JBColor.border())
  }

  init {
    title = KafkaBundle.message("cluster.dialog.title")
    init()
  }

  override fun createCenterPanel(): JComponent = FormBuilder.createFormBuilder()
    .addLabeledComponent(KafkaBundle.message("cluster.name"), name)
    .addLabeledComponent(KafkaBundle.message("cluster.bootstrap"), bootstrap)
    .addLabeledComponent(KafkaBundle.message("cluster.properties"), props, true)
    .addTooltip(KafkaBundle.message("cluster.properties.tooltip"))
    .panel

  fun result(): Pair<KafkaCluster, String> {
    val c = cluster.copy()
    c.bootstrap = bootstrap.text.trim().ifEmpty { "localhost:9092" }
    c.name = name.text.trim().ifEmpty { c.bootstrap }
    if (c.id == KafkaClusters.LOCAL_ID) c.id = UUID.randomUUID().toString()
    return c to props.text
  }
}
