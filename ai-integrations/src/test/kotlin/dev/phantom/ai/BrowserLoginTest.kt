// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import com.intellij.openapi.progress.ProcessCanceledException
import dev.phantom.ai.accounts.BrowserLogin
import dev.phantom.ai.accounts.LoginFailed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class BrowserLoginTest {
  @get:Rule val tmp = TemporaryFolder()

  private fun sh(script: String) = listOf("/bin/sh", "-c", script)

  private fun failure(script: String, timeoutMs: Long = 10_000) =
    assertThrows(LoginFailed::class.java) { BrowserLogin.await(sh(script), emptyMap(), timeoutMs) {} }.message!!

  @Test fun exitZeroIsDoneAndGetsTheEnv() {
    val file = tmp.root.toPath().resolve("seen")
    BrowserLogin.await(sh("""printf %s "${'$'}MY_DIR" > "$file""""), mapOf("MY_DIR" to "/account"), 10_000) {}
    assertEquals("/account", Files.readString(file))
  }

  @Test fun stdinStaysOpen() {
    val file = tmp.root.toPath().resolve("stdin")
    // fd 3: a POSIX sh (dash on Linux) gives a background job /dev/null as stdin before `<&0` is applied
    BrowserLogin.await(sh("""exec 3<&0; cat <&3 >/dev/null & p=${'$'}!; sleep 1; if kill -0 ${'$'}p 2>/dev/null; then echo open; else echo closed; fi > "$file"; kill ${'$'}p 2>/dev/null; exit 0"""), emptyMap(), 10_000) {}
    assertEquals("open", Files.readString(file).trim())
  }

  @Test fun aFailureSaysWhatTheCliSaidWithoutColors() {
    val message = failure("""printf '\033[31mcommand not found: claude\033[0m\n'; exit 127""")
    assertTrue(message, message.endsWith("command not found: claude"))
  }

  @Test fun aDenialEndsTheWaitWithoutTheCliExiting() {
    val started = System.nanoTime()
    assertTrue(failure("echo access_denied; sleep 30").contains("denied"))
    assertTrue(System.nanoTime() - started < 20_000_000_000)
  }

  @Test fun tooLongIsAFailure() = assertTrue(failure("sleep 30", timeoutMs = 300).contains("too long"))

  @Test fun cancelKillsTheProcess() {
    val pid = tmp.root.toPath().resolve("pid")
    var checks = 0
    assertThrows(ProcessCanceledException::class.java) {
      BrowserLogin.await(sh("""echo ${'$'}${'$'} > "$pid"; exec sleep 30"""), emptyMap(), 10_000) {
        if (Files.exists(pid) && Files.size(pid) > 0 && ++checks > 2) throw ProcessCanceledException()
      }
    }
    val handle = ProcessHandle.of(Files.readString(pid).trim().toLong())
    if (handle.isPresent) handle.get().onExit().get(5, java.util.concurrent.TimeUnit.SECONDS)
    assertFalse(handle.map { it.isAlive }.orElse(false))
  }
}
