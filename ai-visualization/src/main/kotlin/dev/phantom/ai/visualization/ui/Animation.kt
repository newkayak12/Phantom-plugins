// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.visualization.ui

import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import javax.swing.JComponent
import javax.swing.Timer

/**
 * The animation clock of [component]: a Swing timer that runs only while the component is showing and is stopped when it is
 * hidden or [dispose]d. [phases] (2..4) and [periodMs] come from the theme. [onPhase] is called on the EDT with the new phase.
 */
class Animation(
  private val component: JComponent,
  private val phases: () -> Int,
  private val periodMs: () -> Int,
  private val onPhase: (Int) -> Unit,
) {
  private val timer = Timer(periodMs()) { tick() }
  private var disposed = false

  var phase = 0
    private set

  val isRunning: Boolean get() = timer.isRunning

  private val listener = HierarchyListener { e ->
    if (e.changeFlags and (HierarchyEvent.SHOWING_CHANGED or HierarchyEvent.DISPLAYABILITY_CHANGED).toLong() != 0L) sync()
  }

  init {
    timer.isRepeats = true
    timer.initialDelay = periodMs()
    component.addHierarchyListener(listener)
  }

  /** Starts or stops the timer to match whether the component is showing. */
  fun sync() {
    if (!disposed && component.isShowing) {
      if (!timer.isRunning) {
        timer.delay = periodMs()
        timer.start()
      }
    } else {
      timer.stop()
    }
  }

  /** The next phase, as one timer tick does. */
  fun tick() {
    phase = (phase + 1) % phases().coerceIn(2, 4)
    onPhase(phase)
  }

  fun dispose() {
    disposed = true
    timer.stop()
    component.removeHierarchyListener(listener)
  }
}
