// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.ai.core.AiState
import dev.phantom.ai.core.AiStateData
import dev.phantom.ai.core.HookConsent
import dev.phantom.ai.hooks.HookInstallService
import java.nio.file.Files
import java.nio.file.Path

/** ~/.claude/settings.json and ~/.codex/hooks.json (temp dirs here) are only changed after the user allowed it. */
class HookConsentInIdeTest : BasePlatformTestCase() {
  private lateinit var root: Path
  private val state = AiState()
  private val service = HookInstallService { state }
  private val claudeFile get() = root.resolve("claude/settings.json")
  private val codexFile get() = root.resolve("codex/hooks.json")

  override fun setUp() {
    super.setUp()
    root = Files.createTempDirectory("phantom-consent")
    System.setProperty(HookInstallService.CLAUDE_DIR_PROP, root.resolve("claude").toString())
    System.setProperty(HookInstallService.CODEX_DIR_PROP, root.resolve("codex").toString())
  }

  override fun tearDown() {
    try {
      System.clearProperty(HookInstallService.CLAUDE_DIR_PROP); System.clearProperty(HookInstallService.CODEX_DIR_PROP)
      root.toFile().deleteRecursively()
    } finally {
      super.tearDown()
    }
  }

  fun testNotAskedYetChangesNothing() {
    assertEquals(HookConsent.ASK, AiStateData().hookConsent)
    service.installAll()
    assertFalse(Files.exists(claudeFile)); assertFalse(Files.exists(codexFile))
    assertEquals(listOf(claudeFile, codexFile), service.systemFiles())
  }

  fun testDeclinedChangesNothing() {
    Files.createDirectories(claudeFile.parent)
    Files.writeString(claudeFile, """{"theme":"dark"}""")
    service.setConsent(HookConsent.DECLINED)
    service.installAll()
    assertEquals("""{"theme":"dark"}""", Files.readString(claudeFile))
    assertFalse(Files.exists(codexFile))
  }

  fun testAllowInstallsAndTurningItOffTakesThemOut() {
    Files.createDirectories(claudeFile.parent)
    Files.writeString(claudeFile, """{"theme":"dark"}""")
    service.setConsent(HookConsent.ALLOWED)
    assertTrue(Files.readString(claudeFile).contains("claude-hook.sh"))
    assertTrue(Files.readString(claudeFile).contains("claude-statusline.sh"))
    assertTrue(Files.readString(codexFile).contains("codex-hook.sh"))
    service.setConsent(HookConsent.DECLINED)
    assertEquals(HookConsent.DECLINED, state.state.hookConsent)
    assertFalse(Files.readString(claudeFile).contains("claude-hook.sh"))
    assertTrue(Files.readString(claudeFile).contains("\"theme\""))
    assertFalse(Files.readString(codexFile).contains("codex-hook.sh"))
  }
}
