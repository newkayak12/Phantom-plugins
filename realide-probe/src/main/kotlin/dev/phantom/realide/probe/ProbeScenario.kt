// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.realide.probe

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.nio.file.Path

/** What a scenario gets: the first project (fixture alpha), the report and where fixtures and output live. */
class ProbeContext(val project: Project, val report: ProbeReport, val out: Path, val fixtures: Path, val pluginId: String) {
  /** Runs [block] on the EDT; scenarios never block it, they poll with [waitUntil]. */
  suspend fun <T> edt(block: () -> T): T = withContext(Dispatchers.EDT) { block() }

  /**
   * Runs [block] on the EDT in any modality: for reading and answering a modal dialog the scenario opened, nothing that
   * changes the model (that needs a write-safe context, see TransactionGuard).
   */
  suspend fun <T> edtAny(block: () -> T): T = withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) { block() }

  /** [waitUntil] in any modality, see [edtAny]. */
  suspend fun waitUntilAny(timeoutMs: Long = 15_000, condition: () -> Boolean): Boolean {
    val end = System.currentTimeMillis() + timeoutMs
    while (true) {
      if (edtAny(condition)) return true
      if (System.currentTimeMillis() >= end) return false
      delay(100)
    }
  }

  /** Polls [condition] on the EDT every 100 ms until true or [timeoutMs]; returns the last value. */
  suspend fun waitUntil(timeoutMs: Long = 15_000, condition: () -> Boolean): Boolean {
    val end = System.currentTimeMillis() + timeoutMs
    while (true) {
      if (edt(condition)) return true
      if (System.currentTimeMillis() >= end) return false
      delay(100)
    }
  }
}

fun interface ProbeScenario {
  suspend fun run(ctx: ProbeContext)
}

object ProbeScenarios {
  private val all = mutableMapOf<String, ProbeScenario>()

  init {
    all["smoke"] = SmokeScenario
    all["tabs"] = TabsScenario
    all["tabs-restart"] = TabsRestartScenario
    all["workspaces"] = WorkspacesScenario
    all["workspaces-layout"] = WorkspacesLayoutScenario
    all["ai-integrations"] = AiIntegrationsScenario
    all["ai-visualization"] = AiVisualizationScenario
  }

  fun find(name: String): ProbeScenario? = all[name]
  fun names(): Set<String> = all.keys
}
