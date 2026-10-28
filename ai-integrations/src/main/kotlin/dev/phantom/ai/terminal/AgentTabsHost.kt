// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.terminal

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.util.messages.Topic
import javax.swing.JComponent

/** One agent terminal tab. [id] is the session's tab id (a fresh one for a plain terminal); closing disposes [disposable]. */
class AgentTab(
  val id: String,
  val title: String,
  val component: JComponent,
  val preferredFocus: JComponent? = null,
  val io: TerminalIo? = null,
  val disposable: Disposable,
  val icon: javax.swing.Icon? = null,
)

fun interface AgentTabsListener {
  fun changed()
}

/**
 * The agent terminal tabs of a project, in the order they were added, with the selected one. The tool window panel shows
 * them ([AgentTabsPanel]); the terminals never live in a tool window content manager. EDT only.
 */
@Service(Service.Level.PROJECT)
class AgentTabsHost(private val project: Project) : Disposable {
  private val list = ArrayList<AgentTab>()
  private var selectedId: String? = null

  fun tabs(): List<AgentTab> = list.toList()

  fun selected(): AgentTab? = list.firstOrNull { it.id == selectedId }

  fun get(id: String): AgentTab? = list.firstOrNull { it.id == id }

  /** Adds [tab] at the end and selects it. */
  fun add(tab: AgentTab) {
    list += tab
    selectedId = tab.id
    fire()
  }

  /** Selects the tab; false if there is none with [id]. */
  fun select(id: String): Boolean {
    if (list.none { it.id == id }) return false
    if (selectedId != id) {
      selectedId = id
      fire()
    }
    return true
  }

  /** Removes the tab and disposes it; the neighbour on the left (else the first) becomes selected. */
  fun close(id: String) {
    val i = list.indexOfFirst { it.id == id }
    if (i < 0) return
    val tab = list.removeAt(i)
    if (selectedId == id) selectedId = list.getOrNull(maxOf(i - 1, 0))?.id
    try {
      Disposer.dispose(tab.disposable)
    } finally {
      fire()
    }
  }

  override fun dispose() {
    val all = list.toList()
    list.clear()
    selectedId = null
    all.forEach { Disposer.dispose(it.disposable) }
  }

  private fun fire() {
    if (!project.isDisposed) project.messageBus.syncPublisher(TOPIC).changed()
  }

  companion object {
    val TOPIC: Topic<AgentTabsListener> = Topic.create("Phantom AI agent tabs", AgentTabsListener::class.java)

    fun getInstance(project: Project): AgentTabsHost = project.getService(AgentTabsHost::class.java)
  }
}
