// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.drivers

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import com.intellij.util.xmlb.annotations.XCollection
import dev.phantom.db.core.Dbms
import java.util.concurrent.CopyOnWriteArrayList

/**
 * JDBC drivers known to the IDE: the built-in presets from `drivers.xml` (with the user's edits laid over them) and the
 * user's own. Loaded on first use, never at startup.
 */
@Service(Service.Level.APP)
@State(name = "PhantomDbDrivers", storages = [Storage("phantomDbDrivers.xml")])
class DbDriverRegistry : PersistentStateComponent<DbDriverRegistry.State> {
  class State {
    @XCollection(style = XCollection.Style.v2)
    var drivers: MutableList<DbDriverDefinition> = ArrayList()
    /** Edited copies of built-ins, matched by id. */
    @XCollection(style = XCollection.Style.v2)
    var overrides: MutableList<DbDriverDefinition> = ArrayList()
  }

  private var state = State()
  private val listeners = CopyOnWriteArrayList<() -> Unit>()

  override fun getState(): State = state
  override fun loadState(state: State) {
    this.state = state
  }

  /** Built-ins with the user's edits applied, then the user's drivers. */
  val all: List<DbDriverDefinition>
    get() = builtIns.map { b -> state.overrides.firstOrNull { it.id == b.id } ?: b } + state.drivers

  fun find(id: String?): DbDriverDefinition? = id?.let { i -> all.firstOrNull { it.id == i } }

  /** The driver with the longest URL prefix matching [url]. A `jdbc:p6spy:` wrapper is looked through, as in [Dbms.forUrl]. */
  fun forUrl(url: String): DbDriverDefinition? {
    val u = url.trim().removePrefix("jdbc:p6spy:").let { if (it.startsWith("jdbc:")) it else "jdbc:$it" }
    return all
      .mapNotNull { d -> d.urlPrefixes.filter { u.startsWith(it, ignoreCase = true) }.maxOfOrNull { it.length }?.let { d to it } }
      .maxByOrNull { it.second }?.first
  }

  fun builtInFor(dbms: Dbms): DbDriverDefinition? = if (dbms.hasDialect) find(dbms.name.lowercase()) else null

  /** A built-in id is stored as an edit of that built-in; any other id as a user driver. */
  fun save(definition: DbDriverDefinition) {
    val copy = definition.copy()
    val target = if (builtIns.any { it.id == copy.id }) state.overrides.also { copy.builtIn = true } else state.drivers.also { copy.builtIn = false }
    val i = target.indexOfFirst { it.id == copy.id }
    if (i >= 0) target[i] = copy else target.add(copy)
    changed()
  }

  /** Removes a user driver. A built-in goes back to its default instead. */
  fun remove(id: String) {
    if (builtIns.any { it.id == id }) return resetToDefault(id)
    if (state.drivers.removeIf { it.id == id }) changed()
  }

  fun resetToDefault(id: String) {
    if (state.overrides.removeIf { it.id == id }) changed()
  }

  fun addListener(parent: Disposable, listener: () -> Unit) {
    listeners.add(listener)
    Disposer.register(parent) { listeners.remove(listener) }
  }

  private fun changed() = listeners.forEach { it() }

  companion object {
    fun getInstance(): DbDriverRegistry = service()

    /** Read from `drivers.xml` on first use. */
    val builtIns: List<DbDriverDefinition> by lazy {
      val stream = DbDriverRegistry::class.java.getResourceAsStream("/phantom/db/drivers.xml") ?: error("drivers.xml is missing")
      JDOMUtil.load(stream).getChildren("driver").map {
        XmlSerializer.deserialize(it, DbDriverDefinition::class.java).also { d -> d.builtIn = true }
      }
    }
  }
}
