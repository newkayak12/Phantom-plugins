// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.usage

import dev.phantom.ai.core.bestEffort
import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.SystemDirs
import com.google.gson.JsonParser
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.util.concurrent.TimeUnit

/** Runs argv and returns trimmed stdout on exit 0, else null. Injected in tests; no real keychain. */
fun interface CommandRunner {
  fun run(argv: List<String>): String?
}

/** Runs a real subprocess with a hard timeout: stdout is read on a daemon thread, so a hung child never blocks the caller. */
class TimedProcessRunner(private val timeoutMs: Long) : CommandRunner {
  override fun run(argv: List<String>): String? {
    val p = try {
      ProcessBuilder(argv).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    } catch (e: java.io.IOException) {
      return null
    }
    val out = java.util.concurrent.atomic.AtomicReference<ByteArray>()
    val reader = Thread({ bestEffort { out.set(p.inputStream.readAllBytes()) } }, "Phantom AI process reader").apply { isDaemon = true; start() }
    return try {
      bestEffort { p.outputStream.close() }
      if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
        kill(p)
        null
      } else {
        reader.join(timeoutMs)
        val bytes = out.get()
        if (p.exitValue() == 0 && bytes != null) String(bytes, StandardCharsets.UTF_8).trim() else null
      }
    } catch (e: InterruptedException) {
      kill(p)
      Thread.currentThread().interrupt()
      null
    }
  }

  private fun kill(p: Process) {
    p.descendants().forEach { it.destroyForcibly() }
    p.destroyForcibly()
  }
}

/** The IDE never reads the keychain itself: only /usr/bin/security does, and it is killed after 5 s. */
object ProcessCommandRunner : CommandRunner by TimedProcessRunner(5_000)

/** Claude OAuth access token: macOS keychain via /usr/bin/security, then <configDir>/.credentials.json, else null. No login UI. */
class ClaudeCredentials(
  private val runner: CommandRunner = ProcessCommandRunner,
  private val isMac: Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("mac"),
  private val homeDir: String = System.getProperty("user.home").orEmpty(),
  private val keychainAllowed: () -> Boolean = SystemDirs::keychainAllowed,
  private val redirect: () -> String? = { SystemDirs.redirect(AgentKind.CLAUDE) },
) {
  /** [configDir] null = System default: -Dphantom.ai.claudeDir when set (suffixed keychain service), else ~/.claude. */
  fun readToken(configDir: String?): String? {
    val configDir = configDir ?: redirect()
    if (isMac && keychainAllowed()) {
      val raw = runner.run(listOf(SECURITY, "find-generic-password", "-s", service(configDir), "-w"))
      parseToken(raw)?.let { return it }
    }
    val file = File(configDir ?: "$homeDir/.claude", ".credentials.json")
    val text = bestEffort { file.takeIf { it.isFile }?.readText() }.getOrNull()
    return parseToken(text)
  }

  companion object {
    const val SECURITY = "/usr/bin/security"
    const val DEFAULT_SERVICE = "Claude Code-credentials"

    fun service(configDir: String?): String {
      if (configDir == null) return DEFAULT_SERVICE
      val digest = MessageDigest.getInstance("SHA-256").digest(Normalizer.normalize(configDir, Normalizer.Form.NFC).toByteArray(StandardCharsets.UTF_8))
      return "$DEFAULT_SERVICE-" + digest.joinToString("") { "%02x".format(it) }.take(8)
    }

    fun parseToken(json: String?): String? {
      if (json.isNullOrBlank()) return null
      val o = bestEffort { JsonParser.parseString(json) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
      val oauth = o.get("claudeAiOauth")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
      return oauth.get("accessToken")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
    }
  }
}
