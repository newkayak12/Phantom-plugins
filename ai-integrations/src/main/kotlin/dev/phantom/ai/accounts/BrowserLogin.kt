// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.accounts

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.ThrowableComputable
import dev.phantom.ai.core.PhantomAiBundle
import dev.phantom.ai.terminal.Tmux
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** A login that did not happen; [message] is what to tell the user. Nothing of it is kept. */
class LoginFailed(message: String) : Exception(message)

/** The CLI's own login, with no terminal: it opens the browser and exits once the user is through. */
internal object BrowserLogin {
  private const val TIMEOUT_MS = 600_000L
  private const val MAX_OUTPUT = 4_000
  private const val MAX_SHOWN = 300
  private val DENIED = Regex("""\baccess_denied\b|authorization (?:request )?(?:was )?denied|sign-?in (?:was )?denied|login (?:was )?denied""", RegexOption.IGNORE_CASE)
  private val ANSI = Regex("""\u001B\[[0-9;?]*[ -/]*[@-~]""")

  /** Modal progress with Cancel; a cancel comes out as ProcessCanceledException. */
  fun run(command: String, env: Map<String, String>, title: String) {
    ProgressManager.getInstance().runProcessWithProgressSynchronously(ThrowableComputable<Unit, LoginFailed> {
      ProgressManager.getInstance().progressIndicator?.text = PhantomAiBundle.message("accounts.login.browser")
      await(Tmux.shellCommand("exec $command"), env, TIMEOUT_MS) { ProgressManager.checkCanceled() }
    }, title, true, null)
  }

  /** Runs [argv] to its end; its stdin stays open, a CLI reading it must not see EOF. [check] throws to cancel. */
  fun await(argv: List<String>, env: Map<String, String>, timeoutMs: Long, check: () -> Unit) {
    val process = try {
      ProcessBuilder(argv).directory(File(System.getProperty("user.home"))).redirectErrorStream(true).apply { environment().putAll(env) }.start()
    } catch (e: IOException) {
      throw LoginFailed(PhantomAiBundle.message("accounts.login.failed.output", e.message ?: e.toString()))
    }
    val output = StringBuffer()
    val reader = thread(isDaemon = true, name = "Phantom AI login output") {
      runCatching {
        process.inputStream.bufferedReader().use { r ->
          val chunk = CharArray(1024)
          while (true) {
            val n = r.read(chunk)
            if (n < 0) break
            output.append(chunk, 0, n)
            if (output.length > MAX_OUTPUT) output.delete(0, output.length - MAX_OUTPUT)
          }
        }
      }
    }
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    try {
      while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
        check()
        if (DENIED.containsMatchIn(output)) throw LoginFailed(PhantomAiBundle.message("accounts.login.failed.denied"))
        if (System.nanoTime() > deadline) throw LoginFailed(PhantomAiBundle.message("accounts.login.failed.timeout"))
      }
      reader.join(1_000)
      if (DENIED.containsMatchIn(output)) throw LoginFailed(PhantomAiBundle.message("accounts.login.failed.denied"))
      if (process.exitValue() != 0) {
        throw LoginFailed(PhantomAiBundle.message("accounts.login.failed.output", ANSI.replace(output, "").trim().takeLast(MAX_SHOWN)))
      }
    } finally {
      if (process.isAlive) {
        process.toHandle().descendants().forEach { it.destroy() }
        process.destroy()
      }
    }
  }
}
