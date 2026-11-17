// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.realide.probe

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ex.ApplicationEx
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Runs the scenario named by -Dphantom.probe.scenario once, in the first project opened (fixture alpha), writes the
 * report into -Dphantom.probe.out and exits the IDE. A watchdog ends a stuck run with timeout:true.
 */
internal class ProbeStartup : ProjectActivity {
  override suspend fun execute(project: Project) {
    val name = System.getProperty("phantom.probe.scenario") ?: return
    val out = System.getProperty("phantom.probe.out")?.let(Path::of) ?: return
    if (!started.compareAndSet(false, true)) return
    val report = ProbeReport(name, out)
    val watchdog = thread(name = "phantom-probe-watchdog", isDaemon = true) {
      try {
        Thread.sleep(WATCHDOG_MS)
        report.timedOut()
        report.finish()
        Runtime.getRuntime().halt(2)
      }
      catch (_: InterruptedException) {}
    }
    try {
      val scenario = ProbeScenarios.find(name)
      if (scenario == null) {
        report.step("scenario.known", mapOf("known" to true), mapOf("known" to false, "names" to ProbeScenarios.names().toString()))
      }
      else {
        val fixtures = Path.of(System.getProperty("phantom.probe.fixtures") ?: "")
        scenario.run(ProbeContext(project, report, out, fixtures, System.getProperty("phantom.probe.plugin") ?: ""))
      }
    }
    catch (e: Throwable) {
      LOG.warn("scenario failed", e)
      report.step("scenario.completed", mapOf("completed" to true), mapOf("completed" to false, "error" to e.toString()))
    }
    finally {
      watchdog.interrupt()
      report.finish()
      ApplicationManager.getApplication().invokeLater { (ApplicationManager.getApplication() as ApplicationEx).exit(true, true) }
    }
  }

  private companion object {
    val started = AtomicBoolean()
    val LOG = Logger.getInstance(ProbeStartup::class.java)
    // long scenarios (tabs: ~40 menu operations) override it with -Dphantom.probe.watchdog.ms
    val WATCHDOG_MS: Long = java.lang.Long.getLong("phantom.probe.watchdog.ms", 230_000L)
  }
}
