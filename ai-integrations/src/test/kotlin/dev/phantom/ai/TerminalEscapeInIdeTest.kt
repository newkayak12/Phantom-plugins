// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import com.intellij.ide.impl.HeadlessDataManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.util.Disposer
import com.intellij.terminal.JBTerminalSystemSettingsProviderBase
import com.intellij.terminal.JBTerminalWidget
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.toolWindow.ToolWindowHeadlessManagerImpl
import dev.phantom.ai.terminal.escapeBeforeThePanel
import dev.phantom.ai.terminal.escapeToAgent
import java.awt.BorderLayout
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.JPanel
import javax.swing.KeyStroke

class TerminalEscapeInIdeTest : BasePlatformTestCase() {
  fun testEscOnTheTerminalGoesToTheAgent() {
    val terminal = JPanel()
    val scope = Disposer.newDisposable(testRootDisposable, "terminal")
    var sent = 0
    escapeToAgent(terminal, scope) { sent++ }
    val esc = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0)
    val action = ActionUtil.getActions(terminal).single { a -> a.shortcutSet.shortcuts.any { (it as? KeyboardShortcut)?.firstKeyStroke == esc } }
    action.actionPerformed(TestActionEvent.createTestEvent(action))
    assertEquals(1, sent)
    Disposer.dispose(scope)
    assertTrue("gone with the terminal", ActionUtil.getActions(terminal).isEmpty())
  }

  /** Outside the Terminal tool window the panel itself moves Esc to the editor, before any action or keymap. */
  fun testEscOnThePanelInAToolWindowGoesToTheAgent() {
    val scope = Disposer.newDisposable(testRootDisposable, "terminal")
    HeadlessDataManager.fallbackToProductionDataManager(scope)
    val panel = JBTerminalWidget(project, JBTerminalSystemSettingsProviderBase(), scope).terminalPanel
    val toolWindow = ToolWindowHeadlessManagerImpl.MockToolWindow(project)
    object : JPanel(BorderLayout()), UiDataProvider {
      override fun uiDataSnapshot(sink: DataSink) {
        sink[PlatformDataKeys.TOOL_WINDOW] = toolWindow
        sink[CommonDataKeys.PROJECT] = project
      }
    }.add(panel)
    val taken = KeyEvent(panel, KeyEvent.KEY_PRESSED, 0, 0, KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED)
    panel.handleKeyEvent(taken)
    assertTrue("the panel takes Esc for the editor by itself", taken.isConsumed)

    var sent = 0
    escapeBeforeThePanel(panel) { sent++ }

    val esc =KeyEvent(panel, KeyEvent.KEY_PRESSED, 0, 0, KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED)
    panel.handleKeyEvent(esc)
    assertEquals(1, sent)
    assertTrue(esc.isConsumed)

    panel.handleKeyEvent(KeyEvent(panel, KeyEvent.KEY_RELEASED, 0, 0, KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED))
    panel.handleKeyEvent(KeyEvent(panel, KeyEvent.KEY_PRESSED, 0, InputEvent.SHIFT_DOWN_MASK, KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED))
    assertEquals("only a plain Esc press", 1, sent)
  }
}
