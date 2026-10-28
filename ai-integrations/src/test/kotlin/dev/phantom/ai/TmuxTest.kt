// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import dev.phantom.ai.terminal.Tmux
import org.junit.Assert.assertEquals
import org.junit.Test

class TmuxTest {
  @Test fun wrapsTheCommandInAPhantomSessionWithEnvAndMouse() {
    val line = Tmux.wrap("/opt/homebrew/bin/tmux", "phantom-1", "/r/my app", mapOf("B" to "2", "A" to "x y"), "claude '--x'")
    assertEquals(
      "exec '/opt/homebrew/bin/tmux' '-L' 'phantom' 'new-session' '-s' 'phantom-1' '-c' '/r/my app' '-e' 'A=x y' '-e' 'B=2' " +
        "'claude '\\''--x'\\''; exec \"\${SHELL:-/bin/zsh}\" -l' " +
        "';' 'set-option' '-t' 'phantom-1' 'mouse' 'on' ';' 'set-option' '-t' 'phantom-1' 'status' 'off'",
      line,
    )
  }

  @Test fun plainCommandKeepsALoginShellAfterTheAgent() =
    assertEquals("claude; exec \"\${SHELL:-/bin/zsh}\" -l", Tmux.plain("claude"))

  @Test fun theLineIsTheShellsArgumentNotTypedAtItsPrompt() {
    val cmd = Tmux.shellCommand("claude '--x'")
    assertEquals(listOf("-l", "-i", "-c", "claude '--x'"), cmd.drop(1))
  }
}
