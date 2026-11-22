// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.ai.core.AiState
import dev.phantom.ai.terminal.ExternalTerminalStarter
import dev.phantom.ai.terminal.IdeTerminalBackend
import dev.phantom.ai.terminal.TerminalBackend
import dev.phantom.ai.terminal.TerminalBackends
import dev.phantom.ai.terminal.TerminalConfigurable
import dev.phantom.ai.terminal.TerminalOpener
import dev.phantom.ai.terminal.TerminalStarter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import javax.swing.JComboBox

class TerminalBackendInIdeTest : BasePlatformTestCase() {
  private class Fake(override val id: String) : TerminalBackend {
    override val displayName = id
    override fun starter(project: Project): TerminalStarter = error("not used")
  }

  private val ide = Fake(TerminalBackends.IDE)
  private val os = Fake(TerminalBackends.OS)
  private val iterm = Fake("iterm")

  override fun tearDown() {
    try {
      AiState.getInstance().state.terminalBackend = null
    } finally {
      super.tearDown()
    }
  }

  fun testAutomaticIsTheIdeTabWithTmuxElseTheOsTerminal() {
    val all = listOf(ide, iterm, os)
    assertSame(ide, TerminalBackends.current(null, all, tmuxInstalled = true))
    assertSame(os, TerminalBackends.current(null, all, tmuxInstalled = false))
    // no OS terminal here (Windows): the IDE tab
    assertSame(ide, TerminalBackends.current(null, listOf(ide, iterm), tmuxInstalled = false))
  }

  fun testTheChosenBackendWhileItIsAvailable() {
    assertSame(iterm, TerminalBackends.current("iterm", listOf(ide, iterm, os), tmuxInstalled = true))
    // iTerm uninstalled since: automatic
    assertSame(os, TerminalBackends.current("iterm", listOf(ide, os), tmuxInstalled = false))
  }

  fun testBuiltInBackendsRegistered() {
    val ids = TerminalBackend.EP_NAME.extensionList.map { it.id }
    assertEquals(listOf("ide", "iterm", "os"), ids)
    assertTrue(TerminalBackend.EP_NAME.extensionList.first() is IdeTerminalBackend)
  }

  fun testScriptRunsTheLineInCwdWithEnvAndDeletesItself() {
    val dir = Files.createTempDirectory("phantom-ai-test")
    val script = ExternalTerminalStarter.script("/r/my app", mapOf("TOKEN" to "a b"), "claude", dir)
    val text = Files.readString(script)
    assertTrue(text, text.startsWith("#!/bin/sh\nrm -f -- \"$0\"\ncd '/r/my app' || exit 1\nexport TOKEN='a b'\nexec "))
    assertTrue(text, text.trimEnd().endsWith("'-l' '-i' '-c' 'claude'"))
    assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE),
                 Files.getPosixFilePermissions(script))
  }

  fun testWithTmuxTheAgentRunsDetachedAndTheWindowAttaches() {
    val opened = mutableListOf<Path>()
    val ran = mutableListOf<String>()
    val starter = ExternalTerminalStarter("iTerm", TerminalOpener { opened.add(it) }, tmuxBinary = { "/opt/homebrew/bin/tmux" }, runDetached = { ran.add(it) })
    val parent = Disposer.newDisposable()
    try {
      val started = starter.start(parent, "/r", mapOf("A" to "1"), "claude")
      val session = started.tmuxSession!!
      assertTrue(ran.single(), ran.single().contains("'new-session' '-d' '-s' '$session'"))
      // the attach line is the login shell's -c argument, quoted once more
      val script = Files.readString(opened.single())
      assertTrue(script, script.contains("attach-session") && script.contains(session))
      assertNull(started.io)
    } finally {
      Disposer.dispose(parent)
      opened.forEach { Files.deleteIfExists(it) }
    }
  }

  fun testWithoutTmuxTheWindowRunsTheAgent() {
    val opened = mutableListOf<Path>()
    val starter = ExternalTerminalStarter("Terminal", TerminalOpener { opened.add(it) }, tmuxBinary = { null }, runDetached = { fail("no tmux") })
    val parent = Disposer.newDisposable()
    try {
      val started = starter.start(parent, "/r", emptyMap(), "claude")
      assertNull(started.tmuxSession)
      assertTrue(Files.readString(opened.single()).contains("'claude; exec"))
    } finally {
      Disposer.dispose(parent)
      opened.forEach { Files.deleteIfExists(it) }
    }
  }

  fun testSettingsPageKeepsTheChoice() {
    val ep = Configurable.APPLICATION_CONFIGURABLE.extensionList.single { it.instanceClass == TerminalConfigurable::class.java.name }
    assertEquals("dev.phantom.ai.accounts", ep.parentId)
    val page = TerminalConfigurable { listOf(ide, iterm) }
    val root = page.createComponent()
    @Suppress("UNCHECKED_CAST")
    val combo = generateSequence(listOf<java.awt.Component>(root)) { cs -> cs.flatMap { (it as? java.awt.Container)?.components?.toList() ?: emptyList() }.ifEmpty { null } }
      .flatten().filterIsInstance<JComboBox<*>>().single { it.name == "terminal.backend" } as JComboBox<Any>
    assertEquals(3, combo.itemCount)
    assertFalse(page.isModified)
    combo.selectedIndex = 2
    assertTrue(page.isModified)
    page.apply()
    assertEquals("iterm", AiState.getInstance().state.terminalBackend)
    combo.selectedIndex = 0
    page.apply()
    assertNull(AiState.getInstance().state.terminalBackend)
    page.disposeUIResources()
  }
}
