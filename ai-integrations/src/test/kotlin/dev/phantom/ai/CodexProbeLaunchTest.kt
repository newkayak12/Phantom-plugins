// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import com.intellij.openapi.application.PathManager
import dev.phantom.ai.usage.ProcessCodexLauncher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** Builds the probe process only (processBuilder is pure): no codex is ever started and no real CODEX_HOME is read. */
class CodexProbeLaunchTest {
  private val tmp = Files.createTempDirectory("probe").toFile().canonicalFile
  private val launcher = ProcessCodexLauncher { tmp }

  @Test fun commandIsOrcasShortLivedReadOnlyProbe() {
    val expected = listOf("-c", "approval_policy=never", "-c", "features.plugins=false", "-s", "read-only", "-a", "never", "app-server")
    assertEquals(expected, ProcessCodexLauncher.PROBE_ARGS)
    assertEquals(listOf("codex") + expected, launcher.processBuilder(null, tmp, "codex").command())
  }

  @Test fun cwdIsTheHiddenProbeDirNotTheIdeCwd() {
    assertEquals(tmp, launcher.processBuilder(null, executable = "codex").directory())
    assertNotEquals(File(System.getProperty("user.dir")), launcher.processBuilder(null).directory())
  }

  @Test fun defaultLauncherUsesTheHiddenProbeCwd() {
    val dir = ProcessCodexLauncher().processBuilder(codexHome = null).directory().canonicalFile
    assertTrue(dir.path, dir.name == "codex-probe-cwd" || dir.name == "phantom-ai-codex-probe-cwd")
    assertTrue(dir.path, dir.path.startsWith(File(System.getProperty("java.io.tmpdir")).canonicalPath) || dir.path.startsWith(File(PathManager.getSystemPath()).canonicalPath))
    assertNotEquals(File(System.getProperty("user.dir")).canonicalFile, dir)
    assertTrue(dir.isDirectory)
  }

  @Test fun codexHomeOnlyWhenGiven() {
    assertEquals("/x/codex", launcher.processBuilder("/x/codex").environment()["CODEX_HOME"])
    val saved = System.getenv("CODEX_HOME")
    // never set by the launcher itself: whatever the parent env has is all there can be
    assertEquals(saved, launcher.processBuilder(null).environment()["CODEX_HOME"])
  }

  @Test fun rootCwdIsRefused() {
    for (bad in listOf(File("/"), File(tmp, "missing"))) {
      try {
        launcher.processBuilder(null, bad)
        fail("accepted $bad")
      } catch (e: IOException) { assertNull(null) }
    }
  }

  @Test fun agentBinDirReplacesExecutable() {
    System.setProperty("phantom.ai.agentBinDir", "/sb/bin")
    try {
      assertEquals(File("/sb/bin/codex").path, launcher.processBuilder(null).command().first())
    } finally { System.clearProperty("phantom.ai.agentBinDir") }
    assertEquals("codex", launcher.processBuilder(null).command().first())
    assertFalse(launcher.processBuilder(null).command().contains("--dangerously-bypass-approvals-and-sandbox"))
  }
}
