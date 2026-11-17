// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.visualization

import java.awt.GraphicsEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Shows whether the test JVM is headless and that its home and agent dirs are the sandbox under build/test-home, never the developer's. */
class HeadlessEnvTest {
  @Test
  fun homeIsSandboxed() {
    val home = System.getProperty("user.home")
    println("phantom.headless=${GraphicsEnvironment.isHeadless()}")
    println("phantom.home=$home")
    assertTrue("user.home was $home", home.endsWith("build/test-home"))
    assertTrue(System.getenv("HOME")!!.endsWith("build/test-home"))
    assertTrue(System.getenv("CLAUDE_CONFIG_DIR")!!.endsWith("build/test-home/.claude"))
    assertTrue(System.getenv("CODEX_HOME")!!.endsWith("build/test-home/.codex"))
    assertEquals("false", System.getProperty("phantom.ai.keychain"))
    assertEquals("", System.getProperty("phantom.ai.tmux"))
  }
}
