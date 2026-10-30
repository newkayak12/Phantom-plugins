// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.strings

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.strings.core.Transform
import dev.phantom.strings.core.TransformAction

class StringActionsInIdeTest : BasePlatformTestCase() {
  fun `test the word at each caret is converted`() {
    myFixture.configureByText("a.txt", "val user<caret>Id = other<caret>Name")
    myFixture.testAction(TransformAction(Transform.SNAKE))
    myFixture.checkResult("val user_id<caret> = other_name<caret>")
  }

  fun `test a selection is converted and stays selected`() {
    myFixture.configureByText("a.txt", "<selection>b\na\nb\n</selection>c")
    myFixture.testAction(TransformAction(Transform.DISTINCT))
    myFixture.checkResult("<selection>b\na\n</selection>c")
  }

  fun `test line actions need a selection`() {
    myFixture.configureByText("a.txt", "b\na<caret>")
    val presentation = myFixture.testAction(TransformAction(Transform.SORT))
    assertFalse(presentation.isEnabled)
    myFixture.checkResult("b\na<caret>")
  }

  fun `test the group and the popup action are registered`() {
    assertNotNull(ActionManager.getInstance().getAction("Phantom.Strings"))
    assertNotNull(ActionManager.getInstance().getAction("Phantom.Strings.Popup"))
  }
}
