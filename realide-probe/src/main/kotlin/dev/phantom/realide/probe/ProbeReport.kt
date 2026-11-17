// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.realide.probe

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import java.awt.Color
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

/** What a run found: steps with expected and observed values, errors logged by dev.phantom code, and the run's idea.log. */
class ProbeReport(val scenario: String, val out: Path) {
  val nonce: String = UUID.randomUUID().toString()
  private val steps = ArrayList<Map<String, Any?>>()
  private val exceptions = ArrayList<String>()
  @Volatile private var timeout = false
  private val finished = java.util.concurrent.atomic.AtomicBoolean()

  init {
    Files.createDirectories(out)
    LOG.info("PHANTOM-PROBE-START scenario=$scenario nonce=$nonce")
  }

  /**
   * One step. ok is computed here: observed[k] == expected[k] for every key of expected, so a scenario cannot claim a pass.
   * [env] with a failed platform baseline (observed.baseline.ok == false) records ok:null: the environment, not the plugin.
   */
  @Synchronized
  fun step(name: String, expected: Map<String, Any?>, observed: Map<String, Any?>, env: String? = null): Boolean? {
    val baselineFailed = env != null && ((observed["baseline"] as? Map<*, *>)?.get("ok") == false)
    val ok: Boolean? = if (baselineFailed) null else expected.all { (k, v) -> same(observed[k], v) }
    val entry = LinkedHashMap<String, Any?>()
    entry["name"] = name
    entry["ok"] = ok
    entry["expected"] = expected
    entry["observed"] = observed
    if (env != null) entry["env"] = env
    steps.add(entry)
    LOG.info("PHANTOM-PROBE-STEP $name ok=$ok")
    return ok
  }

  @Synchronized
  fun timedOut() {
    timeout = true
  }

  /** Writes report.json and copies idea.log; runs once, from the scenario's end or from the watchdog. */
  fun finish() {
    if (!finished.compareAndSet(false, true)) return
    val log = PathManager.getLogDir().resolve("idea.log")
    try {
      exceptions.addAll(phantomErrors(log))
    }
    catch (e: Exception) {
      exceptions.add("could not read idea.log: $e")
    }
    val json = linkedMapOf<String, Any?>(
      "scenario" to scenario,
      // the build plugins are checked against (IC-262.x), which is not the app's own PH-261026.1
      "platform" to PluginManagerCore.buildNumber.asString(),
      "appBuild" to ApplicationInfo.getInstance().build.asString(),
      "nonce" to nonce,
      "steps" to synchronized(this) { steps.toList() },
      "exceptions" to exceptions,
      "timeout" to timeout,
    )
    Files.writeString(out.resolve("report.json"), ProbeJson.write(json) + "\n")
    LOG.info("PHANTOM-PROBE-END scenario=$scenario nonce=$nonce")
    try {
      Files.copy(log, out.resolve("idea.log"), StandardCopyOption.REPLACE_EXISTING)
    }
    catch (e: Exception) {
      LOG.warn("could not copy idea.log", e)
    }
  }

  companion object {
    private val LOG = Logger.getInstance("PhantomProbe")
    private val ENTRY = Regex("(?m)^(?=\\d{4}-\\d\\d-\\d\\d )")
    private val HEAD = Regex("^\\S+ \\S+ \\[[^\\]]*\\] +(ERROR|SEVERE)")

    /** Logged errors that mention dev.phantom. (not the probe itself), one entry each, cut to 1500 characters. */
    fun phantomErrors(log: Path): List<String> {
      if (!Files.isRegularFile(log)) return emptyList()
      return Files.readString(log).split(ENTRY)
        .filter { HEAD.containsMatchIn(it) && it.contains("dev.phantom.") && !it.contains("dev.phantom.realide.probe") }
        .map { it.trim().take(1500) }
    }

    fun hex(c: Color?): String? = c?.let { "#%02x%02x%02x".format(it.red, it.green, it.blue) }

    private fun same(a: Any?, b: Any?): Boolean = when {
      a is Number && b is Number -> a.toDouble() == b.toDouble()
      a is Iterable<*> && b is Iterable<*> -> a.toList().size == b.toList().size && a.toList().zip(b.toList()).all { (x, y) -> same(x, y) }
      a is Map<*, *> && b is Map<*, *> -> a.keys == b.keys && a.keys.all { same(a[it], b[it]) }
      else -> a == b
    }
  }
}
