// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.bytecode

import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.BinaryLightVirtualFile
import com.intellij.ui.content.ContentFactory
import org.jetbrains.org.objectweb.asm.ClassWriter
import org.jetbrains.org.objectweb.asm.Opcodes
import javax.swing.JPanel

class ShowBytecodeToolWindowTest : BasePlatformTestCase() {
  private fun classFile(name: String): BinaryLightVirtualFile {
    val writer = ClassWriter(0)
    writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null)
    writer.visitEnd()
    return BinaryLightVirtualFile("$name.class", writer.toByteArray())
  }

  override fun setUp() {
    super.setUp()
    myFixture.configureByText("A.java", "class A {}")
  }

  override fun tearDown() {
    try {
      ToolWindowManager.getInstance(project).unregisterToolWindow("Bytecode")
      ToolWindowManager.getInstance(project).unregisterToolWindow(BytecodeToolWindowPanel.TOOL_WINDOW_ID)
    }
    catch (e: Throwable) {
      addSuppressedException(e)
    }
    finally {
      super.tearDown()
    }
  }

  private fun showBytecode(file: BinaryLightVirtualFile) {
    val context = SimpleDataContext.builder()
      .add(CommonDataKeys.PROJECT, project)
      .add(CommonDataKeys.EDITOR, myFixture.editor)
      .add(CommonDataKeys.PSI_FILE, myFixture.file)
      .add(CommonDataKeys.VIRTUAL_FILE, file)
      .build()
    ShowBytecodeAction().actionPerformed(TestActionEvent.createTestEvent(context))
  }

  fun `test the bundled Bytecode Viewer's tool window is left alone`() {
    // what the bundled Bytecode Viewer registers when it shows a class
    val bundled = ToolWindowManager.getInstance(project).registerToolWindow("Bytecode") {}
    bundled.contentManager.addContent(ContentFactory.getInstance().createContent(JPanel(), "Other.class", false))

    showBytecode(classFile("Foo"))

    assertEquals(1, bundled.contentManager.contentCount)
    val own = ToolWindowManager.getInstance(project).getToolWindow(BytecodeToolWindowPanel.TOOL_WINDOW_ID)!!
    assertEquals(listOf("Foo.class"), own.contentManager.contents.map { it.displayName })
  }

  fun `test showing the same class again does not leak an editor`() {
    val file = classFile("Bar")
    val editorsBefore = EditorFactory.getInstance().allEditors.size
    showBytecode(file)
    showBytecode(file)

    val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(BytecodeToolWindowPanel.TOOL_WINDOW_ID)!!
    assertEquals(1, toolWindow.contentManager.contentCount)
    toolWindow.contentManager.removeAllContents(true)
    assertEquals(editorsBefore, EditorFactory.getInstance().allEditors.size)
  }
}
