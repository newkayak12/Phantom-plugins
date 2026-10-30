// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.bytecode

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class BytecodeActionInIdeTest : BasePlatformTestCase() {
  fun `test show bytecode is registered under its Phantom id`() {
    val action = ActionManager.getInstance().getAction("Phantom.Bytecode.Show")
    assertInstanceOf(action, ShowBytecodeAction::class.java)
    assertEquals("Show Bytecode", action.templateText)
  }
}
