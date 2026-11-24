// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.usage

import dev.phantom.ai.core.bestEffort
import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.SystemDirs
import dev.phantom.ai.core.rethrowIfCancellation
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

data class CodexUsage(val session: UsageWindow?, val weekly: UsageWindow?, val updatedAtMs: Long)

/** Line-based JSON-RPC channel to a `codex app-server`. [readLine] returns null on timeout or end of output. */
interface RpcChannel : AutoCloseable {
  fun send(line: String)
  fun readLine(timeoutMs: Long): String?
}

/** Starts the channel for a CODEX_HOME. Injected in tests; the real one starts `codex app-server`. */
fun interface CodexLauncher {
  fun launch(codexHome: String?): RpcChannel
}

/**
 * Real launcher: Orca's short-lived read-only `codex app-server` probe with CODEX_HOME, started in a hidden cwd so Codex
 * never scans the IDE's working directory; close() stops the process (destroy, then destroyForcibly).
 */
class ProcessCodexLauncher(private val cwdProvider: () -> File = ::hiddenProbeCwd) : CodexLauncher {
  /** The process to start; pure (nothing is started). [cwd] is refused when root-like or not a directory. */
  fun processBuilder(codexHome: String?, cwd: File = cwdProvider(), executable: String = SystemDirs.cli(AgentKind.CODEX)): ProcessBuilder {
    checkProbeCwd(cwd)
    val pb = ProcessBuilder(listOf(executable) + PROBE_ARGS).directory(cwd).redirectError(ProcessBuilder.Redirect.DISCARD)
    pb.environment().apply {
      if (codexHome != null) put("CODEX_HOME", codexHome)
      put("PATH", (listOfNotNull(get("PATH")?.takeIf { it.isNotEmpty() }) + EXTRA_PATH).joinToString(File.pathSeparator))
    }
    return pb
  }

  override fun launch(codexHome: String?): RpcChannel {
    val pb = processBuilder(codexHome)
    val p = pb.start()
    val lines = LinkedBlockingQueue<String>()
    Thread({
      bestEffort { p.inputStream.bufferedReader().forEachLine { lines.put(it) } }
      lines.put(EOF)
    }, "Phantom AI codex rpc reader").apply { isDaemon = true }.start()
    val out: BufferedWriter = p.outputStream.bufferedWriter()
    return object : RpcChannel {
      override fun send(line: String) { out.write(line); out.write("\n"); out.flush() }
      override fun readLine(timeoutMs: Long): String? {
        val l = lines.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: return null
        if (l === EOF) { lines.put(EOF); return null }
        return l
      }
      override fun close() {
        bestEffort { out.close() }
        p.descendants().forEach { it.destroyForcibly() }
        p.destroy()
        if (!p.waitFor(1, TimeUnit.SECONDS)) p.destroyForcibly()
      }
    }
  }

  companion object {
    /** Orca's CODEX_SHORT_LIVED_PROBE_APP_SERVER_ARGS: no approvals, no plugins, read-only sandbox. */
    val PROBE_ARGS = listOf("-c", "approval_policy=never", "-c", "features.plugins=false", "-s", "read-only", "-a", "never", "app-server")
    private val EXTRA_PATH = listOf("/opt/homebrew/bin", "/usr/local/bin", "${System.getProperty("user.home")}/.local/bin", "${System.getProperty("user.home")}/.npm-global/bin")
    private val EOF = String()

    /** Whether [executable] can run: as given when absolute, else found on [path] or the extra directories the probe adds. */
    fun installed(executable: String = SystemDirs.cli(AgentKind.CODEX), path: String? = System.getenv("PATH")): Boolean {
      val exe = File(executable)
      if (exe.isAbsolute) return exe.canExecute()
      val dirs = path.orEmpty().split(File.pathSeparator).filter { it.isNotEmpty() } + EXTRA_PATH
      return dirs.any { File(it, executable).canExecute() }
    }

    private fun checkProbeCwd(cwd: File) {
      val path = cwd.toPath()
      if (path.nameCount == 0 || path.parent == null || !cwd.isDirectory) throw IOException("refusing probe cwd: $cwd")
    }

    /** PathManager system path/phantom-ai/codex-probe-cwd (tmpdir/phantom-ai-codex-probe-cwd without an application), created and realpath'd. */
    fun hiddenProbeCwd(): File {
      val dir = if (ApplicationManager.getApplication() != null) File(PathManager.getSystemPath(), "phantom-ai/codex-probe-cwd")
      else File(System.getProperty("java.io.tmpdir"), "phantom-ai-codex-probe-cwd")
      dir.mkdirs()
      val real = dir.toPath().toRealPath().toFile()
      checkProbeCwd(real)
      return real
    }
  }
}

/** Codex usage from `codex app-server` account/rateLimits/read, the channel Codex offers its own clients. Null on any failure. */
class CodexUsageClient(
  private val launcher: CodexLauncher = ProcessCodexLauncher(),
  private val now: () -> Long = System::currentTimeMillis,
  private val defaultHome: () -> String = { SystemDirs.dir(AgentKind.CODEX).toString() },
  private val timeoutMs: Long = 10_000,
) {
  /** [codexHome] null = System default. */
  fun fetch(codexHome: String?): CodexUsage? = viaRpc(codexHome ?: defaultHome())

  private fun viaRpc(home: String): CodexUsage? = codexRequest(launcher, home, "account/rateLimits/read", timeoutMs)?.let { parseRpc(it, now()) }

  companion object {
    private const val DRIFT_MINUTES = 1

    /** [result] of account/rateLimits/read: {rateLimits:{primary,secondary}} with usedPercent, windowDurationMins, resetsAt. Null when no window is usable. */
    fun parseRpc(result: com.google.gson.JsonElement?, nowMs: Long): CodexUsage? {
      val rl = result?.takeIf { it.isJsonObject }?.asJsonObject?.get("rateLimits")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
      return classify(raw(rl.get("primary"), "usedPercent", "windowDurationMins", "resetsAt"), raw(rl.get("secondary"), "usedPercent", "windowDurationMins", "resetsAt"), nowMs)
    }

    fun parseRpc(json: String, nowMs: Long): CodexUsage? = parseRpc(bestEffort { JsonParser.parseString(json) }.getOrNull(), nowMs)

    private data class Raw(val pct: Double, val minutes: Int?, val resetsAtMs: Long?)

    private fun raw(e: com.google.gson.JsonElement?, pctKey: String, minKey: String?, resetKey: String): Raw? {
      val o = e?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
      val pct = number(o, pctKey) ?: return null
      return Raw(pct, minKey?.let { number(o, it) }?.toInt(), parseResetTimestamp(o.get(resetKey)))
    }

    private fun kind(r: Raw): Int? = r.minutes?.let {
      when {
        Math.abs(it - SESSION_MINUTES) <= DRIFT_MINUTES -> SESSION_MINUTES
        Math.abs(it - WEEK_MINUTES) <= DRIFT_MINUTES -> WEEK_MINUTES
        else -> null
      }
    }

    /** Orca's classification: by duration, else primary = session and secondary = weekly. */
    private fun classify(primary: Raw?, secondary: Raw?, nowMs: Long): CodexUsage? {
      var session: Raw? = null
      var weekly: Raw? = null
      for (w in listOf(primary, secondary)) {
        if (w == null) continue
        when (kind(w)) {
          SESSION_MINUTES -> if (session == null) session = w
          WEEK_MINUTES -> if (weekly == null) weekly = w
        }
      }
      if (session == null && primary != null && kind(primary) == null) session = primary
      if (weekly == null && secondary != null && kind(secondary) == null) weekly = secondary
      fun map(r: Raw?, expected: Int) = r?.let { UsageWindow(it.pct.coerceIn(0.0, 100.0), expected, it.resetsAtMs) }
      val s = map(session, SESSION_MINUTES)
      val w = map(weekly, WEEK_MINUTES)
      return if (s == null && w == null) null else CodexUsage(s, w, nowMs)
    }
  }
}

/**
 * One request over a short-lived `codex app-server` for [codexHome]: initialize, then [method] without params. Its result,
 * or null on an error, a timeout or any failure.
 */
fun codexRequest(launcher: CodexLauncher, codexHome: String, method: String, timeoutMs: Long): JsonElement? {
  val channel = try { launcher.launch(codexHome) } catch (e: IOException) { return null } catch (e: RuntimeException) { rethrowIfCancellation(e); return null }
  try {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    channel.send(rpcMessage(1, "initialize", JsonObject().apply { add("clientInfo", JsonObject().apply { addProperty("name", "phantom-ai"); addProperty("version", "1.0.0") }) }))
    while (true) {
      val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
      if (left <= 0) return null
      val line = channel.readLine(left)?.trim() ?: return null
      val msg = bestEffort { JsonParser.parseString(line) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject ?: continue
      val id = msg.get("id")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt ?: continue
      when (id) {
        1 -> {
          channel.send(JsonObject().apply { addProperty("jsonrpc", "2.0"); addProperty("method", "initialized"); add("params", JsonObject()) }.toString())
          channel.send(rpcMessage(2, method, JsonObject()))
        }
        2 -> return if (msg.has("error")) null else msg.get("result")
      }
    }
  } catch (e: IOException) {
    return null
  } catch (e: InterruptedException) {
    Thread.currentThread().interrupt()
    return null
  } finally {
    bestEffort { channel.close() }
  }
}

private fun rpcMessage(id: Int, method: String, params: JsonObject) =
  JsonObject().apply { addProperty("jsonrpc", "2.0"); addProperty("id", id); addProperty("method", method); add("params", params) }.toString()
