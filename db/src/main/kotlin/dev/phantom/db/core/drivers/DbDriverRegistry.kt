// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
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
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection
import dev.phantom.db.core.Dbms
import java.util.concurrent.CopyOnWriteArrayList

/** The jars a download fetched for a driver, kept apart from its definition so `drivers.xml` updates still reach a built-in. */
@Tag("pin")
class DbDriverPin {
  var id: String = ""
  var version: String? = null
  @XCollection(style = XCollection.Style.v2)
  var jars: MutableList<String> = ArrayList()

  fun copy(): DbDriverPin = DbDriverPin().also { it.id = id; it.version = version; it.jars = ArrayList(jars) }
}

/**
 * JDBC drivers known to the IDE: the built-in presets from `drivers.xml` (with the user's edits laid over them) and the
 * user's own, each with its download pin. Loaded on first use, never at startup.
 *
 * Read from any thread: [all] and [find] return an immutable snapshot, rebuilt only when something changes; changes
 * replace the state under a lock instead of editing it, so a save in progress never sees a list being changed.
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
    /** Download pins by driver id; laid over the definitions as [DbDriverDefinition.resolvedVersion] and [DbDriverDefinition.resolvedJars]. */
    @XCollection(style = XCollection.Style.v2)
    var pins: MutableList<DbDriverPin> = ArrayList()

    internal fun copy(): State = State().also { s ->
      s.drivers = drivers.mapTo(ArrayList()) { it.copy() }
      s.overrides = overrides.mapTo(ArrayList()) { it.copy() }
      s.pins = pins.mapTo(ArrayList()) { it.copy() }
    }
  }

  private val lock = Any()
  @Volatile private var state = State()
  @Volatile private var snapshot: List<DbDriverDefinition>? = null
  private val listeners = CopyOnWriteArrayList<() -> Unit>()

  override fun getState(): State = state

  /** Pins that older versions kept inside the definitions move to [State.pins]; nothing else changes. */
  override fun loadState(state: State) {
    val loaded = state.copy()
    for (d in loaded.overrides + loaded.drivers) {
      if (d.resolvedJars.isNotEmpty() && loaded.pins.none { it.id == d.id }) {
        loaded.pins += DbDriverPin().also { it.id = d.id; it.version = d.resolvedVersion; it.jars = ArrayList(d.resolvedJars) }
      }
      d.resolvedVersion = null
      d.resolvedJars = ArrayList()
    }
    synchronized(lock) {
      this.state = loaded
      snapshot = null
    }
  }

  /** Built-ins with the user's edits applied, then the user's drivers; each with its pin. Copies: editing one changes nothing here. */
  val all: List<DbDriverDefinition>
    get() = snapshot ?: synchronized(lock) { snapshot ?: build(state).also { snapshot = it } }

  fun find(id: String?): DbDriverDefinition? = id?.let { i -> all.firstOrNull { it.id == i } }

  /** The driver with the longest URL prefix matching [url]. A `jdbc:p6spy:` wrapper is looked through, as in [Dbms.forUrl]. */
  fun forUrl(url: String): DbDriverDefinition? {
    val u = url.trim().removePrefix("jdbc:p6spy:").let { if (it.startsWith("jdbc:")) it else "jdbc:$it" }
    return all
      .mapNotNull { d -> d.urlPrefixes.filter { u.startsWith(it, ignoreCase = true) }.maxOfOrNull { it.length }?.let { d to it } }
      .maxByOrNull { it.second }?.first
  }

  fun builtInFor(dbms: Dbms): DbDriverDefinition? = if (dbms.hasDialect) find(dbms.name.lowercase()) else null

  /**
   * A built-in id is stored as an edit of that built-in; any other id as a user driver. Its [DbDriverDefinition.resolvedJars]
   * become the driver's pin (none: the pin is dropped).
   */
  fun save(definition: DbDriverDefinition) = update { s ->
    val copy = definition.copy().also { it.resolvedVersion = null; it.resolvedJars = ArrayList() }
    val target = if (builtIns.any { it.id == copy.id }) s.overrides.also { copy.builtIn = true } else s.drivers.also { copy.builtIn = false }
    val i = target.indexOfFirst { it.id == copy.id }
    if (i >= 0) target[i] = copy else target.add(copy)
    setPin(s, definition.id, definition.resolvedVersion, definition.resolvedJars)
    true
  }

  /**
   * Pins downloaded jars to the driver [id] without touching its definition; no [jars] drop the pin. False, and nothing
   * changes, when there is no such driver (removed meanwhile).
   */
  fun pin(id: String, version: String?, jars: List<String>): Boolean {
    var pinned = false
    update { s ->
      pinned = builtIns.any { it.id == id } || s.drivers.any { it.id == id }
      if (pinned) setPin(s, id, version, jars)
      pinned
    }
    return pinned
  }

  /** Removes a user driver and its pin. A built-in goes back to its default instead, keeping its pin. */
  fun remove(id: String) {
    if (builtIns.any { it.id == id }) return resetToDefault(id)
    update { s -> s.drivers.removeIf { it.id == id }.also { if (it) s.pins.removeIf { p -> p.id == id } } }
  }

  /** Drops the user's edits of a built-in; its downloaded jars stay pinned. */
  fun resetToDefault(id: String) = update { s -> s.overrides.removeIf { it.id == id } }

  fun addListener(parent: Disposable, listener: () -> Unit) {
    listeners.add(listener)
    Disposer.register(parent) { listeners.remove(listener) }
  }

  private fun changed() = listeners.forEach { it() }

  /** Applies [change] to a copy of the state; if it reports a change, that copy becomes the state and listeners hear of it. */
  private fun update(change: (State) -> Boolean) {
    val done = synchronized(lock) {
      val next = state.copy()
      if (!change(next)) return@synchronized false
      state = next
      snapshot = build(next)
      true
    }
    if (done) changed()
  }

  private fun setPin(s: State, id: String, version: String?, jars: List<String>) {
    s.pins.removeIf { it.id == id }
    if (jars.isNotEmpty()) s.pins += DbDriverPin().also { it.id = id; it.version = version; it.jars = ArrayList(jars) }
  }

  private fun build(s: State): List<DbDriverDefinition> {
    val definitions = builtIns.map { b -> (s.overrides.firstOrNull { it.id == b.id } ?: b).copy() } + s.drivers.map { it.copy() }
    for (d in definitions) {
      val pin = s.pins.firstOrNull { it.id == d.id }
      d.resolvedVersion = pin?.version
      d.resolvedJars = ArrayList(pin?.jars.orEmpty())
    }
    return java.util.List.copyOf(definitions)
  }

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
