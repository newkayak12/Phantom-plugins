// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.ai.terminal.escapeToAgent
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
}
