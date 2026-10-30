// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.core

import com.intellij.openapi.application.ApplicationManager
import java.awt.event.HierarchyEvent
import javax.swing.JComponent

/**
 * Refreshes a view for an event only while it shows; a hidden one (another project's window, a tab in the background, a
 * closed tool window) remembers it is stale and refreshes once when it shows again. Session and state events reach every
 * view of every project, several a second while agents work. Unit tests have no showing frame: there it always refreshes.
 */
class WhenShown(
  private val component: JComponent,
  private val refresh: () -> Unit,
  private val showing: () -> Boolean = { component.isShowing || ApplicationManager.getApplication().isUnitTestMode },
) {
  @Volatile private var stale = false

  init {
    component.addHierarchyListener { e ->
      if (e.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && component.isShowing && stale) {
        stale = false
        refresh()
      }
    }
  }

  /** An event: refresh now when shown, else on the next show. */
  fun request() {
    if (showing()) refresh() else stale = true
  }
}
